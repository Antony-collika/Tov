package com.example.byokchat.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Tùy chỉnh của người dùng cho từng công cụ, lưu theo UID. null = dùng mặc định. */
@Serializable
data class ToolPref(
    val mode: String? = null,
    val name: String? = null,
    val description: String? = null,
    /** Mô tả từng tham số đã sửa (Đợt C); rỗng = dùng mô tả gốc trong code. */
    val paramDescriptions: Map<String, String> = emptyMap(),
)

class ToolPrefs(context: Context) {
    private val plain = context.applicationContext.getSharedPreferences("tool_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = MapSerializer(String.serializer(), ToolPref.serializer())

    private val _prefs = MutableStateFlow(load())
    val prefs: StateFlow<Map<String, ToolPref>> = _prefs.asStateFlow()

    fun get(uid: String): ToolPref = _prefs.value[uid] ?: ToolPref()

    fun update(uid: String, change: (ToolPref) -> ToolPref) {
        val next = change(get(uid))
        val map = _prefs.value.toMutableMap()
        if (next.mode == null && next.name == null && next.description == null && next.paramDescriptions.isEmpty()) map.remove(uid) else map[uid] = next
        _prefs.value = map
        plain.edit().putString("prefs", json.encodeToString(ser, map)).apply()
    }

    private fun load(): Map<String, ToolPref> {
        val raw = plain.getString("prefs", null) ?: return emptyMap()
        return runCatching { json.decodeFromString(ser, raw) }.getOrDefault(emptyMap())
    }
}
