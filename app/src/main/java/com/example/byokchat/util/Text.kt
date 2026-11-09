package com.example.byokchat.util

import java.text.SimpleDateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale

object TextUtil {
    /** Cách chậm nhưng đúng: bỏ dấu một ký tự bằng Normalizer. Chỉ dùng để dựng bảng tra. */
    private fun foldCharSlow(c: Char): Char {
        if (c == 'đ' || c == 'Đ') return 'd'
        if (c.code < 128) return c.lowercaseChar()
        val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        return if (d.isEmpty()) c else d[0].lowercaseChar()
    }

    // Bảng tra cho khối chữ Latin có dấu (U+0080..U+024F) và chữ Việt tổ hợp sẵn (U+1E00..U+1EFF).
    // Dựng một lần, lười; sau đó mỗi ký tự chỉ là một lần tra mảng thay vì gọi Normalizer.
    private val latinTable: CharArray by lazy { CharArray(0x024F - 0x0080 + 1) { foldCharSlow((0x0080 + it).toChar()) } }
    private val vietTable: CharArray by lazy { CharArray(0x1EFF - 0x1E00 + 1) { foldCharSlow((0x1E00 + it).toChar()) } }

    private fun foldChar(c: Char): Char {
        val code = c.code
        return when {
            code < 128 -> c.lowercaseChar()
            code in 0x0080..0x024F -> latinTable[code - 0x0080]
            code in 0x1E00..0x1EFF -> vietTable[code - 0x1E00]
            else -> foldCharSlow(c)
        }
    }

    /** Bỏ dấu + chữ thường, giữ nguyên độ dài chuỗi (để vị trí khớp với bản gốc). */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(foldChar(c))
        return sb.toString()
    }

    /**
     * Như [findAll] nhưng nhận chuỗi ĐÃ bỏ dấu (qua [fold]) sẵn. Dùng khi cùng một chuỗi
     * được tìm nhiều lần, để không phải bỏ dấu lại mỗi lần.
     */
    fun findAllFolded(foldedText: String, foldedQuery: String): List<Int> {
        if (foldedQuery.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        var i = foldedText.indexOf(foldedQuery)
        while (i >= 0) {
            out.add(i)
            i = foldedText.indexOf(foldedQuery, i + foldedQuery.length)
        }
        return out
    }

    /** Vị trí bắt đầu của mọi chỗ khớp (không phân biệt hoa thường và dấu). */
    fun findAll(text: String, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        return findAllFolded(fold(text), fold(query))
    }
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
}

fun String.cap(max: Int = 30000): String =
    if (length <= max) this else take(max) + "\n…[truncated, ${length - max} more characters]"

fun fmtTime(ms: Long): String =
    if (ms <= 0) "?" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    for (c in glob) {
        when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(c.toString()))
        }
    }
    sb.append('$')
    return Regex(sb.toString(), RegexOption.IGNORE_CASE)
}
