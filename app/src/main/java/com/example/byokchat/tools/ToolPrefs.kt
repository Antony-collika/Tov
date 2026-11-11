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
    /**
     * Giữ lại chỉ để đọc được JSON cũ (Đợt E, quyết định 5): tên/mô tả và mô
     * tả tham số user từng sửa KHÔNG còn hiệu lực nữa — tên/mô tả model thấy
     * chỉ còn đến từ file tools.md của thẻ Tools. ToolPrefs.load/update luôn
     * lược bỏ các trường này nên chúng không bao giờ được lưu lại.
     */
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
        val next = stripOverrides(change(get(uid)))
        val map = _prefs.value.toMutableMap()
        if (next.mode == null) map.remove(uid) else map[uid] = next
        _prefs.value = map
        plain.edit().putString("prefs", json.encodeToString(ser, map)).apply()
    }

    private fun load(): Map<String, ToolPref> {
        val raw = plain.getString("prefs", null) ?: return emptyMap()
        return runCatching { json.decodeFromString(ser, raw) }.getOrDefault(emptyMap())
            .mapValues { (_, pref) -> stripOverrides(pref) }
            .filterValues { it.mode != null }
    }

    companion object {
        /** Bỏ các override tên/mô tả/mô tả tham số cũ (Đợt E, quyết định 5): chỉ giữ mode. */
        fun stripOverrides(pref: ToolPref): ToolPref =
            pref.copy(name = null, description = null, paramDescriptions = emptyMap())
    }
}
