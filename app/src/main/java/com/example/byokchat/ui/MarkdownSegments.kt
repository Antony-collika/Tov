package com.example.byokchat.ui

/** Một đoạn của tin nhắn: chữ Markdown thường hoặc một khối code. */
sealed interface MdSegment {
    data class Text(val markdown: String) : MdSegment

    /** closed = false: khối chưa có dòng đóng (thường là AI đang viết dở). */
    data class Code(val language: String, val code: String, val closed: Boolean) : MdSegment
}

private val OPEN_FENCE = Regex("^\\s*(`{3,})([^`]*)$")
private val CLOSE_FENCE = Regex("^\\s*(`{3,})\\s*$")

/**
 * Tách Markdown thành các đoạn chữ và khối code (```).
 * - Dòng mở: từ 3 dấu huyền trở lên ở đầu dòng (cho phép thụt lề), phần sau không chứa dấu huyền.
 * - Dòng đóng: chỉ gồm dấu huyền, số lượng không ít hơn dòng mở.
 * - Khối chưa đóng kéo dài đến hết chuỗi.
 */
fun parseMarkdownSegments(src: String): List<MdSegment> {
    val lines = src.replace("\r", "").split("\n")
    val out = ArrayList<MdSegment>()
    val text = ArrayList<String>()

    fun flushText() {
        if (text.isNotEmpty()) {
            val t = text.joinToString("\n")
            if (t.isNotBlank()) out.add(MdSegment.Text(t))
            text.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val open = OPEN_FENCE.matchEntire(lines[i])
        if (open == null) {
            text.add(lines[i])
            i++
            continue
        }
        flushText()
        val fenceLen = open.groupValues[1].length
        val language = open.groupValues[2].trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
        val body = ArrayList<String>()
        var closed = false
        i++
        while (i < lines.size) {
            val line = lines[i]
            val close = CLOSE_FENCE.matchEntire(line)
            i++
            if (close != null && close.groupValues[1].length >= fenceLen) {
                closed = true
                break
            }
            body.add(line)
        }
        // Chuỗi đang stream thường kết thúc bằng xuống dòng: bỏ dòng trống cuối để khối không nhảy.
        if (!closed && body.isNotEmpty() && body[body.size - 1].isEmpty()) body.removeAt(body.size - 1)
        out.add(MdSegment.Code(language, body.joinToString("\n"), closed))
    }
    flushText()
    return out
}
