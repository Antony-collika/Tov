package com.example.byokchat.ui

/** Một đoạn của tin nhắn: chữ Markdown thường, một khối code, hoặc một ảnh. */
sealed interface MdSegment {
    data class Text(val markdown: String) : MdSegment

    /** closed = false: khối chưa có dòng đóng (thường là AI đang viết dở). */
    data class Code(val language: String, val code: String, val closed: Boolean) : MdSegment

    /** Link ảnh trong tin nhắn AI — MarkdownBody vẽ bằng Coil, bấm để xem to. */
    data class Image(val url: String) : MdSegment
}

private val OPEN_FENCE = Regex("^\\s*(`{3,})([^`]*)$")
private val CLOSE_FENCE = Regex("^\\s*(`{3,})\\s*$")
private val WHITESPACE = Regex("\\s+")
private const val FENCE_MARK = "```"

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
        // Dòng không có ``` thì chắc chắn không phải hàng rào: bỏ qua biểu thức chính quy cho nhanh.
        val open = if (lines[i].contains(FENCE_MARK)) OPEN_FENCE.matchEntire(lines[i]) else null
        if (open == null) {
            text.add(lines[i])
            i++
            continue
        }
        flushText()
        val fenceLen = open.groupValues[1].length
        val language = open.groupValues[2].trim().split(WHITESPACE).firstOrNull().orEmpty().lowercase()
        val body = ArrayList<String>()
        var closed = false
        i++
        while (i < lines.size) {
            val line = lines[i]
            val close = if (line.contains(FENCE_MARK)) CLOSE_FENCE.matchEntire(line) else null
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
    // Ảnh trong tin nhắn AI: tách link ảnh khỏi đoạn chữ (khối code giữ nguyên).
    return out.flatMap { seg ->
        if (seg is MdSegment.Text) splitImagesInText(seg.markdown) else listOf(seg)
    }
}

private val MD_IMAGE_RX = Regex("!\\[[^\\]\n]*\\]\\(\\s*([^\\s)]+)[^)]*\\)")
private val MD_LINK_RX = Regex("\\[[^\\]\n]*\\]\\(\\s*[^\\s)]+[^)]*\\)")
private val BARE_URL_RX = Regex("https?://[^\\s<>\"')]+")
private val INLINE_CODE_RX = Regex("`[^`\\n]*`")
private val IMAGE_EXT_RX = Regex("\\.(jpe?g|png|gif|webp)([?#].*)?$", RegexOption.IGNORE_CASE)

private fun isImageUrl(url: String): Boolean = IMAGE_EXT_RX.containsMatchIn(url)

/**
 * Tách link ảnh ra khỏi một đoạn chữ:
 * - `![alt](url)`: cả cụm biến mất khỏi chữ, thành MdSegment.Image ngay tại chỗ đó.
 * - Link trần `https://...jpg`: giữ nguyên trong chữ (vẫn bấm được), ảnh hiện ngay sau
 *   đoạn chữ chứa link đó.
 * - `[text](url)` thường và URL trong `code` inline: giữ nguyên, không đụng.
 * Chỉ nhận đuôi .jpg/.jpeg/.png/.gif/.webp (kèm ?query hoặc #fragment đều được).
 */
private fun splitImagesInText(text: String): List<MdSegment> {
    val out = ArrayList<MdSegment>()
    val buf = StringBuilder()
    fun flushText() {
        if (buf.isNotBlank()) out.add(MdSegment.Text(buf.toString()))
        buf.clear()
    }
    var pos = 0
    while (pos < text.length) {
        val next = listOfNotNull(
            INLINE_CODE_RX.find(text, pos)?.let { "code" to it },
            MD_IMAGE_RX.find(text, pos)?.let { "image" to it },
            MD_LINK_RX.find(text, pos)?.let { "link" to it },
            BARE_URL_RX.find(text, pos)?.let { "bare" to it },
        ).minByOrNull { it.second.range.first } ?: break
        val (kind, m) = next
        when (kind) {
            "code", "link" -> {
                buf.append(text, pos, m.range.last + 1)
                pos = m.range.last + 1
            }
            "image" -> {
                buf.append(text, pos, m.range.first)
                flushText()
                out.add(MdSegment.Image(m.groupValues[1]))
                pos = m.range.last + 1
            }
            "bare" -> {
                val raw = m.value
                val url = raw.trimEnd('.', ',', ';', ':', '!', '?')
                if (isImageUrl(url)) {
                    buf.append(text, pos, m.range.first + url.length)
                    flushText()
                    out.add(MdSegment.Image(url))
                    // Dấu câu cuối URL (nếu có) giữ lại làm chữ.
                    buf.append(raw.substring(url.length))
                    pos = m.range.last + 1
                } else {
                    buf.append(text, pos, m.range.last + 1)
                    pos = m.range.last + 1
                }
            }
        }
    }
    buf.append(text, pos, text.length)
    flushText()
    return out
}
