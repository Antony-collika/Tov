package com.example.byokchat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = ListSerializer(ProviderConfig.serializer())

    // API key nằm trong kho được mã hóa (khóa mã hóa do Android Keystore giữ).
    // Mở kho này khá chậm nên chỉ mở khi cần, và đọc nội dung ở luồng nền (xem init ở cuối class).
    private val secure: SharedPreferences by lazy { createSecurePrefs(appContext) }
    private val plain: SharedPreferences = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Danh sách nhà cung cấp và API key tìm kiếm chỉ có sau khi kho mã hóa được đọc xong (xem ready).
    private val _providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** True khi kho mã hóa (nhà cung cấp, API key) đã đọc xong. Trước đó danh sách nhà cung cấp còn rỗng. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(plain.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _defaultPrompt = MutableStateFlow(plain.getString("default_prompt", "") ?: "")
    val defaultSystemPrompt: StateFlow<String> = _defaultPrompt.asStateFlow()

    private val _activeProviderId = MutableStateFlow(plain.getString("active_provider", null))
    val activeProviderId: StateFlow<String?> = _activeProviderId.asStateFlow()

    /** Lựa chọn mô hình toàn cục: mô hình user chọn lần cuối ở màn hình chat. Chat mới dùng lựa chọn này nếu còn hợp lệ. */
    private val _currentModelProviderId = MutableStateFlow(plain.getString("current_model_provider", null))
    val currentModelProviderId: StateFlow<String?> = _currentModelProviderId.asStateFlow()

    private val _currentModel = MutableStateFlow(plain.getString("current_model", null))
    val currentModel: StateFlow<String?> = _currentModel.asStateFlow()

    fun setCurrentModel(providerId: String?, model: String?) {
        _currentModelProviderId.value = providerId
        _currentModel.value = model
        plain.edit().putString("current_model_provider", providerId).putString("current_model", model).apply()
    }

    /** Nhà cung cấp của lựa chọn mô hình toàn cục (null nếu chưa chọn hoặc provider đã bị xóa). */
    fun currentModelProvider(): ProviderConfig? = currentModelProviderId.value?.let { provider(it) }

    private val _workspaceUri = MutableStateFlow(plain.getString("workspace_uri", null))
    val workspaceUri: StateFlow<String?> = _workspaceUri.asStateFlow()

    fun setWorkspaceUri(uri: String?) {
        _workspaceUri.value = uri
        plain.edit().putString("workspace_uri", uri).apply()
    }

    private val _searchProvider = MutableStateFlow(plain.getString("search_provider", "BRAVE") ?: "BRAVE")
    val searchProvider: StateFlow<String> = _searchProvider.asStateFlow()

    fun setSearchProvider(p: String) {
        _searchProvider.value = p
        plain.edit().putString("search_provider", p).apply()
    }

    private val _searchKey = MutableStateFlow("")
    val searchApiKey: StateFlow<String> = _searchKey.asStateFlow()

    fun setSearchApiKey(k: String) {
        ensureSecureLoaded() // không ghi đè khi chưa đọc xong
        _searchKey.value = k
        secure.edit().putString("search_key", k).apply()
    }

    private val _onboardingDone = MutableStateFlow(plain.getBoolean("onboarding_done", false))
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    fun setOnboardingDone(done: Boolean) {
        _onboardingDone.value = done
        plain.edit().putBoolean("onboarding_done", done).apply()
    }

    private val _notifPermissionAsked = MutableStateFlow(plain.getBoolean("notif_permission_asked", false))
    val notifPermissionAsked: StateFlow<Boolean> = _notifPermissionAsked.asStateFlow()

    fun setNotifPermissionAsked(asked: Boolean) {
        _notifPermissionAsked.value = asked
        plain.edit().putBoolean("notif_permission_asked", asked).apply()
    }

    /** Nhật ký request (Đợt B): ghi mỗi lần gọi model ra file, mặc định bật. */
    private val _requestLogEnabled = MutableStateFlow(plain.getBoolean("request_log_enabled", true))
    val requestLogEnabled: StateFlow<Boolean> = _requestLogEnabled.asStateFlow()

    fun setRequestLogEnabled(enabled: Boolean) {
        _requestLogEnabled.value = enabled
        plain.edit().putBoolean("request_log_enabled", enabled).apply()
    }

    /**
     * OpenAI: xin thêm số token thật trong luồng stream (`stream_options.include_usage`).
     * Mặc định bật; máy chủ tự dựng có thể từ chối trường này thì tắt đi để gửi như cũ.
     */
    private val _openAiIncludeUsage = MutableStateFlow(plain.getBoolean("openai_include_usage", true))
    val openAiIncludeUsage: StateFlow<Boolean> = _openAiIncludeUsage.asStateFlow()

    fun setOpenAiIncludeUsage(enabled: Boolean) {
        _openAiIncludeUsage.value = enabled
        plain.edit().putBoolean("openai_include_usage", enabled).apply()
    }

    fun provider(id: String?): ProviderConfig? = _providers.value.firstOrNull { it.id == id }

    fun defaultProvider(): ProviderConfig? = provider(_activeProviderId.value) ?: _providers.value.firstOrNull()

    fun saveProvider(p: ProviderConfig, makeDefault: Boolean = false) {
        ensureSecureLoaded() // không ghi đè danh sách bằng rỗng khi chưa đọc xong
        val list = _providers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        _providers.value = list
        secure.edit().putString("providers", json.encodeToString(listSer, list)).apply()
        if (makeDefault || _activeProviderId.value == null) setActiveProvider(p.id)
    }

    fun deleteProvider(id: String) {
        ensureSecureLoaded()
        val list = _providers.value.filter { it.id != id }
        _providers.value = list
        secure.edit().putString("providers", json.encodeToString(listSer, list)).apply()
        if (_activeProviderId.value == id) setActiveProvider(list.firstOrNull()?.id)
        // Lựa chọn model toàn cục đang trỏ tới provider vừa xóa thì cũng
        // phải dọn, không thì preview/gửi đều kẹt ở provider không còn tồn tại.
        if (_currentModelProviderId.value == id) setCurrentModel(null, null)
    }

    fun setActiveProvider(id: String?) {
        _activeProviderId.value = id
        plain.edit().putString("active_provider", id).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        _theme.value = mode
        plain.edit().putString("theme", mode.name).apply()
    }

    fun setDefaultSystemPrompt(text: String) {
        _defaultPrompt.value = text
        plain.edit().putString("default_prompt", text).apply()
    }

    private fun loadProviders(): List<ProviderConfig> {
        val raw = secure.getString("providers", null) ?: return emptyList()
        return runCatching { json.decodeFromString(listSer, raw) }.getOrDefault(emptyList())
    }

    /** Đọc nhà cung cấp và API key tìm kiếm từ kho mã hóa, đúng một lần. Gọi nhiều lần cũng được. */
    @Synchronized
    private fun ensureSecureLoaded() {
        if (_ready.value) return
        _providers.value = loadProviders()
        _searchKey.value = secure.getString("search_key", "") ?: ""
        _ready.value = true
    }

    init {
        // Đọc kho mã hóa ở luồng nền để không làm đứng luồng chính khi mở app.
        // (Khối init đặt ở cuối class để mọi biến đã được khởi tạo trước khi luồng nền chạy.)
        CoroutineScope(Dispatchers.IO).launch { ensureSecureLoaded() }
    }

    private companion object {
        fun createSecurePrefs(ctx: Context): SharedPreferences {
            fun build(): SharedPreferences {
                return EncryptedSharedPreferences.create(
                    "secure_prefs",
                    "secure_prefs_master_key",
                    ctx,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                build()
            } catch (e: Exception) {
                // Kho mã hóa bị hỏng (hiếm gặp, thường do khôi phục máy): tạo lại để app không bị treo
                ctx.deleteSharedPreferences("secure_prefs")
                build()
            }
        }
    }
}
