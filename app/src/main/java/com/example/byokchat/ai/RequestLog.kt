package com.example.byokchat.ai

import com.example.byokchat.util.fmtTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    /**
     * Đợt fix 11/11/2026 (F1/F2): chat chứa request này + số lượt của nó,
     * ghi lúc bắt đầu gọi (ChatEngine biết cả hai). null = mục cũ ghi trước
     * khi log theo chat (nằm ở thư mục gốc, xem ở mục "Older logs").
     */
    val sessionId: String? = null,
    val turnNo: Int? = null,
)

/** Một mục log dưới dạng Markdown — dùng chung cho "Copy turn" và xuất file (F3/F4). */
fun RequestLogEntry.toMarkdown(): String = buildString {
    append("## ")
    if (turnNo != null) append("Turn $turnNo — ")
    append("${fmtTime(timeMillis)} · $provider · $model\n\n")
    append("HTTP ${httpStatus ?: "?"}")
    usage?.let { append(" · ${it.summaryLine()}") }
    if (error != null) append("\nError: $error")
    append("\n\n### Request\n\n```json\n$requestBody\n```\n\n### Response\n\n")
    append(responseText?.ifBlank { "(no response recorded)" } ?: "(no response recorded)")
    append("\n")
}

/**
 * Nhật ký request/response ghi ra FILE trong bộ nhớ riêng của app (mỗi mục
 * một file JSON), giữ tối đa 50 mục / 20 MB gần nhất cho mỗi chat. Chỉ ghi
 * phần thân (body) — API key nằm ở header nên không bao giờ lọt vào log.
 *
 * Đợt fix 11/11/2026 (F1): log theo từng chat — mỗi chat một thư mục con
 * trong thư mục log (tên thư mục = id chat); mở một chat thì chỉ thấy log
 * của chat đó. Mục cũ ghi trước đợt này vẫn nằm ở thư mục gốc và xem được
 * ở mục "Older logs". Xuất file chỉ khi user yêu cầu (màn log, nút Export).
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
    /** Chat của mỗi request đang chạy — finishRequest cần để tìm đúng thư mục con. */
    private val sessionOf = mutableMapOf<Long, String?>()

    companion object {
        /** Phản hồi thô giữ tối đa ngần này ký tự (phần còn lại ghi chú đã cắt). */
        const val MAX_RESPONSE_CHARS = 200_000
        /** Chuỗi base64 dài hơn ngần này ký tự được coi là ảnh và rút gọn. */
        const val MAX_INLINE_STRING_CHARS = 2_000
    }

    /** Bắt đầu ghi một mục: lưu JSON gửi đi, trả về id để `finishRequest` cập nhật. -1 khi log đang tắt. */
    fun beginRequest(provider: String, model: String, body: JsonElement, sessionId: String? = null, turnNo: Int? = null): Long {
        if (!enabled()) return -1
        val now = System.currentTimeMillis()
        val id = synchronized(lock) {
            lastId = maxOf(now, lastId + 1)
            sessionOf[lastId] = sessionId
            lastId
        }
        val entry = RequestLogEntry(
            id = id,
            timeMillis = now,
            provider = provider,
            model = model,
            requestBody = json.encodeToString(JsonElement.serializer(), shortenForLog(body)),
            sessionId = sessionId,
            turnNo = turnNo,
        )
        writeEntry(entry)
        return id
    }

    /** Kết thúc một mục: ghi mã HTTP, phản hồi thô, token thật và lỗi (nếu có). */
    fun finishRequest(id: Long, httpStatus: Int?, responseText: String?, usage: Usage?, error: String?) {
        if (id < 0) return
        synchronized(lock) {
            val sessionId = sessionOf.remove(id)
            val file = entryFile(id, sessionId)
            if (!file.exists()) return
            val entry = runCatching { json.decodeFromString<RequestLogEntry>(file.readText()) }.getOrNull() ?: return
            val capped = responseText?.let {
                if (it.length > MAX_RESPONSE_CHARS) {
                    it.take(MAX_RESPONSE_CHARS) + "\n…[response truncated, ${it.length - MAX_RESPONSE_CHARS} more characters]"
                } else it
            }
            writeEntry(entry.copy(httpStatus = httpStatus, responseText = capped, usage = usage, error = error))
            trimLocked(sessionDir(sessionId))
        }
    }

    /**
     * Các mục mới nhất trước, của một chat. `sessionId = null` = mục cũ
     * (ghi trước khi log theo chat) ở thư mục gốc.
     */
    fun entries(sessionId: String? = null): List<RequestLogEntry> {
        val folder = sessionDir(sessionId)
        val files = synchronized(lock) { folder.listFiles()?.filter { it.name.startsWith("entry-") } ?: emptyList() }
        return files.mapNotNull { runCatching { json.decodeFromString<RequestLogEntry>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.id }
    }

    /** Xóa nhật ký của một chat (null = chỉ mục cũ ở thư mục gốc). */
    fun clear(sessionId: String? = null) {
        synchronized(lock) {
            sessionDir(sessionId).listFiles()?.filter { it.name.startsWith("entry-") }?.forEach { it.delete() }
        }
    }

    /** Thư mục chứa log của một chat (null = thư mục gốc, mục cũ). */
    private fun sessionDir(sessionId: String?): File =
        if (sessionId == null) dir else File(dir, sessionId)

    private fun entryFile(id: Long, sessionId: String? = null): File = File(sessionDir(sessionId), "entry-$id.json")

    private fun writeEntry(entry: RequestLogEntry) {
        try {
            sessionDir(entry.sessionId).mkdirs()
            entryFile(entry.id, entry.sessionId).writeText(json.encodeToString(RequestLogEntry.serializer(), entry))
        } catch (_: Exception) {
            // Nhật ký không bao giờ được làm hỏng cuộc trò chuyện: ghi lỗi thì bỏ qua.
        }
    }

    private fun trimLocked(folder: File) {
        val files = folder.listFiles()?.filter { it.name.startsWith("entry-") }?.sortedByDescending { it.name } ?: return
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
