package com.example.byokchat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class ThemeMode(val label: String) { SYSTEM("Theo hệ thống"), LIGHT("Sáng"), DARK("Tối") }

class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = ListSerializer(ProviderConfig.serializer())

    // API key nằm trong kho được mã hóa (khóa mã hóa do Android Keystore giữ)
    private val secure: SharedPreferences = createSecurePrefs(appContext)
    private val plain: SharedPreferences = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _providers = MutableStateFlow(loadProviders())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(plain.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _defaultPrompt = MutableStateFlow(plain.getString("default_prompt", "") ?: "")
    val defaultSystemPrompt: StateFlow<String> = _defaultPrompt.asStateFlow()

    private val _activeProviderId = MutableStateFlow(plain.getString("active_provider", null))
    val activeProviderId: StateFlow<String?> = _activeProviderId.asStateFlow()

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

    private val _searchKey = MutableStateFlow(secure.getString("search_key", "") ?: "")
    val searchApiKey: StateFlow<String> = _searchKey.asStateFlow()

    fun setSearchApiKey(k: String) {
        _searchKey.value = k
        secure.edit().putString("search_key", k).apply()
    }

    fun provider(id: String?): ProviderConfig? = _providers.value.firstOrNull { it.id == id }

    fun defaultProvider(): ProviderConfig? = provider(_activeProviderId.value) ?: _providers.value.firstOrNull()

    fun saveProvider(p: ProviderConfig, makeDefault: Boolean = false) {
        val list = _providers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        _providers.value = list
        secure.edit().putString("providers", json.encodeToString(listSer, list)).apply()
        if (makeDefault || _activeProviderId.value == null) setActiveProvider(p.id)
    }

    fun deleteProvider(id: String) {
        val list = _providers.value.filter { it.id != id }
        _providers.value = list
        secure.edit().putString("providers", json.encodeToString(listSer, list)).apply()
        if (_activeProviderId.value == id) setActiveProvider(list.firstOrNull()?.id)
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

    private companion object {
        fun createSecurePrefs(ctx: Context): SharedPreferences {
            fun build(): SharedPreferences {
                val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(
                    ctx,
                    "secure_prefs",
                    masterKey,
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
