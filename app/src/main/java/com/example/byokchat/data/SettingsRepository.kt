package com.example.byokchat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.example.byokchat.ai.MetadataParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/**
 * Chuẩn hóa thư mục con cho phép ghi (Đợt E, quyết định 6): trim, '\' -> '/',
 * bỏ '/' ở hai đầu, gộp '/' lặp. Rỗng = ghi anywhere bên trong các folder playground.
 */
fun normalizeWriteSubfolder(raw: String): String =
    raw.trim().replace('\\', '/').trim('/').replace(Regex("/+"), "/")

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
        // Mặc định effort theo hãng (quyết định 15): khi user chưa từng
        // lưu Metadata thì mặc định đi theo provider vừa chọn (NONE cho
        // OpenAI, MEDIUM cho Gemini/Custom). Chỉ đổi giá trị trong bộ
        // nhớ, KHÔNG ghi ra file — giá trị user đã lưu luôn được tôn trọng.
        if (plain.getString("metadata_json", null) == null) {
            _metadata.value = MetadataParams.defaultFor(provider(providerId)?.type)
        }
    }

    /** Nhà cung cấp của lựa chọn mô hình toàn cục (null nếu chưa chọn hoặc provider đã bị xóa). */
    fun currentModelProvider(): ProviderConfig? = currentModelProviderId.value?.let { provider(it) }

    /**
     * AI playground: danh sách URI các folder user đã chọn (handoff
     * playground nhiều folder). Key mới "playground_uris", lưu JSON array,
     * giữ thứ tự thêm. Key cũ "workspace_uri" (một folder) bị bỏ qua hoàn
     * toàn — không migration (quyền SAF cũ còn trong hệ thống là chấp nhận
     * được). Tên folder không lưu cứng mà tính lúc chạy từ DocumentFile.
     */
    private val _playgroundUris = MutableStateFlow(loadPlaygroundUris())
    val playgroundUris: StateFlow<List<String>> = _playgroundUris.asStateFlow()

    private fun loadPlaygroundUris(): List<String> = runCatching {
        plain.getString("playground_uris", null)?.let {
            json.decodeFromString(ListSerializer(String.serializer()), it)
        }
    }.getOrNull() ?: emptyList()

    fun setPlaygroundUris(uris: List<String>) {
        _playgroundUris.value = uris
        plain.edit().putString("playground_uris", json.encodeToString(ListSerializer(String.serializer()), uris)).apply()
    }

    /**
     * Thư mục Session storage (Đợt E, quyết định 6 + mục 11): chỗ app lưu
     * sessions/<ngày>_<session-id>/history.md + history.meta.json, tách
     * riêng khỏi playground (chỗ AI chơi). Chưa chọn thì không có session
     * storage: không ghi history.md, không đồng bộ ngoài app — chat vẫn
     * chạy, màn chat hiện banner báo (đợt fix 11/11/2026, C2 — không tự
     * lấy playground làm thay).
     */
    private val _sessionStorageUri = MutableStateFlow(plain.getString("session_storage_uri", null))
    val sessionStorageUri: StateFlow<String?> = _sessionStorageUri.asStateFlow()

    fun setSessionStorageUri(uri: String?) {
        _sessionStorageUri.value = uri
        plain.edit().putString("session_storage_uri", uri).apply()
    }

    /**
     * Thư mục con AI được phép ghi (Đợt E, quyết định 6): rỗng = ghi anywhere
     * bên trong các folder playground; khác rỗng thì đường dẫn ghi phải nằm
     * dưới thư mục này trong một folder playground.
     * CHƯA CÓ UI — thuộc Đợt 7. Thực thi ở ToolRegistry.execute (tầng tool).
     */
    private val _allowedWriteSubfolder = MutableStateFlow(
        normalizeWriteSubfolder(plain.getString("allowed_write_subfolder", "") ?: ""),
    )
    val allowedWriteSubfolder: StateFlow<String> = _allowedWriteSubfolder.asStateFlow()

    fun setAllowedWriteSubfolder(subfolder: String) {
        val normalized = normalizeWriteSubfolder(subfolder)
        _allowedWriteSubfolder.value = normalized
        plain.edit().putString("allowed_write_subfolder", normalized).apply()
    }

    private val _searchProvider = MutableStateFlow(plain.getString("search_provider", "BRAVE") ?: "BRAVE")
    val searchProvider: StateFlow<String> = _searchProvider.asStateFlow()

    fun setSearchProvider(p: String) {
        ensureSecureLoaded() // không ghi đè key cũ khi chưa đọc xong
        val old = _searchProvider.value
        if (old != p) {
            // Cất key của engine cũ vào ngăn của nó, rồi lấy key của engine
            // mới ra — ô Key trên màn hình đổi theo engine đang chọn.
            if (old in searchKeys) {
                searchKeys[old] = _searchKey.value
                secure.edit().putString("search_key_${old.lowercase()}", _searchKey.value).apply()
            }
            _searchProvider.value = p
            plain.edit().putString("search_provider", p).apply()
            _searchKey.value = searchKeys[p] ?: ""
        }
    }

    /**
     * API key tìm kiếm web, MỖI ENGINE MỘT KEY (2026-11-14): Brave, Tavily
     * và Serper là ba dịch vụ khác nhau nên không dùng chung một key được
     * (key Tavily gửi sang Serper sẽ lỗi). DuckDuckGo không cần key.
     * `_searchKey` / [searchApiKey] luôn là key của engine ĐANG CHỌN nên
     * mọi chỗ đọc cũ (web_search, tóm tắt Settings) không phải đổi.
     */
    private val searchKeys = mutableMapOf<String, String>()

    private val _searchKey = MutableStateFlow("")
    val searchApiKey: StateFlow<String> = _searchKey.asStateFlow()

    fun setSearchApiKey(k: String) {
        ensureSecureLoaded() // không ghi đè khi chưa đọc xong
        val provider = _searchProvider.value
        if (provider in searchKeys) {
            searchKeys[provider] = k
            secure.edit().putString("search_key_${provider.lowercase()}", k).apply()
            _searchKey.value = k
        }
        // DuckDuckGo không có ngăn key: ô nhập bị ẩn nên không gọi tới đây.
    }

    /**
     * YouTube Data API v3 key (YouTube tools, 2026-11-13): nhập trong màn
     * Web search, cùng chỗ với key tìm kiếm web — không làm màn riêng.
     * Chỉ youtube_search / youtube_comments cần key này; tải về và phát
     * audio dùng NewPipeExtractor nên không cần key.
     */
    private val _youtubeKey = MutableStateFlow("")
    val youtubeApiKey: StateFlow<String> = _youtubeKey.asStateFlow()

    fun setYoutubeApiKey(k: String) {
        ensureSecureLoaded() // không ghi đè khi chưa đọc xong
        _youtubeKey.value = k
        secure.edit().putString("youtube_key", k).apply()
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

    /** Thông báo một lần khi provider đã lưu được migrate (xem [loadProviders]): null = không có gì để báo. */
    private val _providerMigrationNotice = MutableStateFlow(plain.getString("provider_migration_notice", null))
    val providerMigrationNotice: StateFlow<String?> = _providerMigrationNotice.asStateFlow()

    fun dismissProviderMigrationNotice() {
        _providerMigrationNotice.value = null
        plain.edit().remove("provider_migration_notice").apply()
    }

    /** Nhật ký request (Đợt B): ghi mỗi lần gọi model ra file, mặc định bật. */
    private val _requestLogEnabled = MutableStateFlow(plain.getBoolean("request_log_enabled", true))
    val requestLogEnabled: StateFlow<Boolean> = _requestLogEnabled.asStateFlow()

    fun setRequestLogEnabled(enabled: Boolean) {
        _requestLogEnabled.value = enabled
        plain.edit().putBoolean("request_log_enabled", enabled).apply()
    }

    /** Nhật ký request (Đợt B): ghi mỗi lần gọi model ra file, mặc định bật. */

    /**
     * Metadata của thẻ Metadata (Đợt 6): effort, stop sequences, tool
     * choice, structured output, service tier — lưu TOÀN CỤC (mọi provider
     * dùng chung, mỗi hãng tự map sang trường riêng, xem MetadataParams).
     * max output tokens KHÔNG ở đây: vẫn theo từng provider
     * (ProviderConfig.maxOutputTokens). Stream luôn bật nên không lưu.
     */
    private val metadataJson = Json { ignoreUnknownKeys = true }
    private val _metadata = MutableStateFlow(
        runCatching {
            plain.getString("metadata_json", null)?.let {
                metadataJson.decodeFromString(MetadataParams.serializer(), it)
            }
        }.getOrNull() ?: MetadataParams()
    )
    val metadata: StateFlow<MetadataParams> = _metadata.asStateFlow()

    fun setMetadata(params: MetadataParams) {
        _metadata.value = params
        plain.edit().putString(
            "metadata_json",
            metadataJson.encodeToString(MetadataParams.serializer(), params),
        ).apply()
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

    /**
     * Đợt 7: include_usage từng là cài đặt toàn cục (key
     * "openai_include_usage", mặc định bật). Nay nó là trường theo từng
     * provider (ProviderConfig.includeUsage). Migrate một lần khi đọc kho:
     * giá trị toàn cục cũ áp cho mọi provider OpenAI/Custom hiện có, rồi
     * xóa key cũ để không migrate lại. Provider mới thêm sau này mặc định
     * bật. Trường hợp góc: user từng tắt toàn cục mà chưa có provider nào
     * thì không còn gì để migrate — provider thêm sau mặc định bật.
     */
    private fun migrateIncludeUsage(list: List<ProviderConfig>): List<ProviderConfig> {
        if (!plain.contains("openai_include_usage")) return list
        val global = plain.getBoolean("openai_include_usage", true)
        plain.edit().remove("openai_include_usage").apply()
        val migrated = list.map {
            if (it.type == ProviderType.OPENAI || it.type == ProviderType.CUSTOM) it.copy(includeUsage = global) else it
        }
        if (migrated != list) {
            secure.edit().putString("providers", json.encodeToString(listSer, migrated)).apply()
        }
        return migrated
    }

    /**
     * Đợt bỏ provider 12/11/2026: hãng Anthropic và loại DeepSeek riêng lẻ
     * đã bỏ. Kho lưu cũ vẫn còn JSON với "type":"CLAUDE" / "DEEPSEEK" — enum
     * mới không còn hai giá trị đó nên decode cả danh sách sẽ lỗi và mất
     * hết provider đã lưu. Vì vậy đọc từng provider một, ghi nhận số lượng
     * để báo user một lần (xem [providerMigrationNotice]):
     * - DeepSeek → chuyển sang Custom, giữ nguyên key + địa chỉ (địa chỉ
     *   trống thì ghi https://api.deepseek.com, vì Custom không có địa chỉ
     *   mặc định); cuộc chat DeepSeek cũ tiếp tục chạy;
     * - Anthropic → bỏ khỏi danh sách dùng được (cuộc chat cũ giữ nguyên
     *   tin nhắn, chỉ không gửi tới Claude được nữa).
     * Ghi đè danh sách đã migrate xuống kho (xem ensureSecureLoaded) nên
     * lần sau không migrate lại.
     */
    private var migrationConvertedDeepSeek = 0
    private var migrationRemovedAnthropic = 0
    private var migrationRemovedIds: Set<String> = emptySet()

    private fun loadProviders(): List<ProviderConfig> {
        val raw = secure.getString("providers", null) ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ProviderConfig>()
        val removedIds = mutableSetOf<String>()
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            // Đọc chuỗi an toàn: kho hỏng (kiểu sai) thì bỏ qua provider đó
            // thay vì làm hỏng cả danh sách.
            fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
            when (str("type")) {
                "CLAUDE" -> {
                    migrationRemovedAnthropic++
                    str("id")?.let { removedIds += it }
                }
                "DEEPSEEK" -> {
                    val base = str("baseUrl").orEmpty()
                    var fixed: Map<String, JsonElement> = obj + ("type" to JsonPrimitive(ProviderType.CUSTOM.name))
                    if (base.isBlank()) fixed = fixed + ("baseUrl" to JsonPrimitive("https://api.deepseek.com"))
                    runCatching { json.decodeFromJsonElement(ProviderConfig.serializer(), JsonObject(fixed)) }
                        .getOrNull()?.let { out += it; migrationConvertedDeepSeek++ }
                }
                else -> runCatching { json.decodeFromJsonElement(ProviderConfig.serializer(), el) }
                    .getOrNull()?.let { out += it }
            }
        }
        migrationRemovedIds = removedIds
        return out
    }

    /** Đọc nhà cung cấp và API key tìm kiếm từ kho mã hóa, đúng một lần. Gọi nhiều lần cũng được. */
    @Synchronized
    private fun ensureSecureLoaded() {
        if (_ready.value) return
        _providers.value = migrateIncludeUsage(loadProviders())
        // Key tìm kiếm web theo từng engine (2026-11-14): key cũ dùng chung
        // "search_key" chuyển một lần vào ngăn của engine đang chọn (key đó
        // nhập lúc đang dùng engine này), rồi xóa key cũ.
        val legacyKey = secure.getString("search_key", "") ?: ""
        for (engine in listOf("BRAVE", "TAVILY", "SERPER")) {
            searchKeys[engine] = secure.getString("search_key_${engine.lowercase()}", "") ?: ""
        }
        if (legacyKey.isNotBlank() && searchKeys.values.all { it.isBlank() }) {
            val current = _searchProvider.value
            if (current in searchKeys) {
                searchKeys[current] = legacyKey
                secure.edit().putString("search_key_${current.lowercase()}", legacyKey).apply()
            }
        }
        if (secure.contains("search_key")) secure.edit().remove("search_key").apply()
        _searchKey.value = searchKeys[_searchProvider.value] ?: ""
        _youtubeKey.value = secure.getString("youtube_key", "") ?: ""
        // Đợt bỏ provider 12/11/2026 (xem loadProviders): danh sách đã
        // migrate thì ghi đè xuống kho; lựa chọn model toàn cục / provider
        // mặc định đang trỏ tới provider Anthropic đã bỏ thì dọn, rồi báo
        // user một lần bằng lời dễ hiểu (hiện ở màn AI providers cho tới
        // khi user tắt).
        var modelCleared = false
        if (migrationRemovedIds.isNotEmpty()) {
            if (_currentModelProviderId.value in migrationRemovedIds) {
                setCurrentModel(null, null)
                modelCleared = true
            }
            if (_activeProviderId.value in migrationRemovedIds) {
                setActiveProvider(_providers.value.firstOrNull()?.id)
            }
        }
        if (migrationConvertedDeepSeek > 0 || migrationRemovedAnthropic > 0) {
            secure.edit().putString("providers", json.encodeToString(listSer, _providers.value)).apply()
            val parts = mutableListOf<String>()
            if (migrationConvertedDeepSeek > 0) {
                parts += "DeepSeek is now set up as a Custom provider with the same API key and address (https://api.deepseek.com) — your DeepSeek chats keep working."
            }
            if (migrationRemovedAnthropic > 0) {
                parts += "Anthropic (Claude) is no longer available in this app, so its saved provider was removed. Your old Claude chats are kept, but they can't send to Claude anymore."
            }
            if (modelCleared) {
                parts += "The model you had chosen was a Claude model, so it was cleared — pick another model in the chat."
            }
            val notice = parts.joinToString(" ")
            _providerMigrationNotice.value = notice
            plain.edit().putString("provider_migration_notice", notice).apply()
        }
        // Mặc định effort theo hãng (quyết định 15): khi user chưa từng
        // lưu Metadata thì mặc định đi theo provider đang chọn (NONE cho
        // OpenAI, MEDIUM cho Gemini/Custom). Chỉ đổi giá trị trong bộ
        // nhớ, KHÔNG ghi ra file — để mặc định vẫn đi theo provider khi
        // user đổi provider, cho tới khi user tự chọn rồi lưu.
        if (plain.getString("metadata_json", null) == null) {
            _metadata.value = MetadataParams.defaultFor(currentModelProvider()?.type)
        }
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
