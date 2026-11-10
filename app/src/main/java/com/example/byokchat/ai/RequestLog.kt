package com.example.byokchat.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Một lần gọi model: JSON gửi đi, phản hồi thô và token thật (nếu hãng báo). */
@Serializable
data class RequestLogEntry(
    val id: Long,
    val timeMillis: Long,
    val provider: String,
    val model: String,
    val httpStatus: Int? = null,
    val requestBody: String,
    val responseText: String? = null,
    val usage: Usage? = null,
    val error: String? = null,
)

/**
 * Nhật ký request/response ghi ra FILE trong bộ nhớ riêng của app (mỗi mục
 * một file JSON), giữ tối đa 50 mục / 20 MB gần nhất. Chỉ ghi phần thân
 * (body) — API key nằm ở header nên không bao giờ lọt vào log.
 *
 * Ảnh base64 trong body được rút gọn thành "[image N KB]" trước khi ghi.
 */
class RequestLog(
    private val dir: File,
    private val enabled: () -> Boolean = { true },
    private val maxEntries: Int = 50,
    private val maxBytes: Long = 20L * 1024 * 1024,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var lastId = 0L

    companion object {
        /** Phản hồi thô giữ tối đa ngần này ký tự (phần còn lại ghi chú đã cắt). */
        const val MAX_RESPONSE_CHARS = 200_000
        /** Chuỗi base64 dài hơn ngần này ký tự được coi là ảnh và rút gọn. */
        const val MAX_INLINE_STRING_CHARS = 2_000
    }

    /** Bắt đầu ghi một mục: lưu JSON gửi đi, trả về id để `finishRequest` cập nhật. -1 khi log đang tắt. */
    fun beginRequest(provider: String, model: String, body: JsonElement): Long {
        if (!enabled()) return -1
        val now = System.currentTimeMillis()
        val id = synchronized(lock) {
            lastId = maxOf(now, lastId + 1)
            lastId
        }
        val entry = RequestLogEntry(
            id = id,
            timeMillis = now,
            provider = provider,
            model = model,
            requestBody = json.encodeToString(JsonElement.serializer(), shortenForLog(body)),
        )
        writeEntry(entry)
        return id
    }

    /** Kết thúc một mục: ghi mã HTTP, phản hồi thô, token thật và lỗi (nếu có). */
    fun finishRequest(id: Long, httpStatus: Int?, responseText: String?, usage: Usage?, error: String?) {
        if (id < 0) return
        synchronized(lock) {
            val file = entryFile(id)
            if (!file.exists()) return
            val entry = runCatching { json.decodeFromString<RequestLogEntry>(file.readText()) }.getOrNull() ?: return
            val capped = responseText?.let {
                if (it.length > MAX_RESPONSE_CHARS) {
                    it.take(MAX_RESPONSE_CHARS) + "\n…[response truncated, ${it.length - MAX_RESPONSE_CHARS} more characters]"
                } else it
            }
            writeEntry(entry.copy(httpStatus = httpStatus, responseText = capped, usage = usage, error = error))
            trimLocked()
        }
    }

    /** Các mục mới nhất trước. */
    fun entries(): List<RequestLogEntry> {
        val files = synchronized(lock) { dir.listFiles()?.filter { it.name.startsWith("entry-") } ?: emptyList() }
        return files.mapNotNull { runCatching { json.decodeFromString<RequestLogEntry>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.id }
    }

    /** Xóa toàn bộ nhật ký. */
    fun clear() {
        synchronized(lock) {
            dir.listFiles()?.filter { it.name.startsWith("entry-") }?.forEach { it.delete() }
        }
    }

    private fun entryFile(id: Long): File = File(dir, "entry-$id.json")

    private fun writeEntry(entry: RequestLogEntry) {
        try {
            dir.mkdirs()
            entryFile(entry.id).writeText(json.encodeToString(RequestLogEntry.serializer(), entry))
        } catch (_: Exception) {
            // Nhật ký không bao giờ được làm hỏng cuộc trò chuyện: ghi lỗi thì bỏ qua.
        }
    }

    private fun trimLocked() {
        val files = dir.listFiles()?.filter { it.name.startsWith("entry-") }?.sortedByDescending { it.name } ?: return
        var total = files.sumOf { it.length() }
        files.forEachIndexed { index, file ->
            if (index >= maxEntries || total > maxBytes) {
                total -= file.length()
                file.delete()
            }
        }
    }

    /**
     * Rút gọn ảnh base64 trong body thành "[image N KB]" (đệ quy qua object/array).
     * Chỉ rút chuỗi dài trông giống base64 hoặc data-URL ảnh; chữ thường giữ nguyên.
     */
    fun shortenForLog(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.mapValues { shortenForLog(it.value) })
        is JsonArray -> JsonArray(el.map { shortenForLog(it) })
        is JsonPrimitive -> {
            val s = el.contentOrNull ?: return el
            val imageBytes = imageByteCount(s)
            if (s.length > MAX_INLINE_STRING_CHARS && imageBytes != null) {
                JsonPrimitive("[image ${imageBytes / 1024} KB]")
            } else el
        }
        else -> el
    }

    /** Số byte ảnh trong một chuỗi base64 hoặc data-URL ảnh; null nếu không phải ảnh. */
    private fun imageByteCount(s: String): Long? {
        val b64 = when {
            s.startsWith("data:image/") -> s.substringAfter("base64,", "")
            else -> s
        }
        if (b64.length <= MAX_INLINE_STRING_CHARS) return null
        if (b64.length % 4 != 0) return null
        if (!b64.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }) return null
        val padding = b64.takeLast(2).count { it == '=' }
        return b64.length * 3L / 4 - padding
    }
}
