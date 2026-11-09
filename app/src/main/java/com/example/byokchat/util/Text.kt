package com.example.byokchat.util

import java.text.SimpleDateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale

object TextUtil {
    private fun foldChar(c: Char): Char {
        if (c == 'đ' || c == 'Đ') return 'd'
        if (c.code < 128) return c.lowercaseChar()
        val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        return if (d.isEmpty()) c else d[0].lowercaseChar()
    }

    /** Bỏ dấu + chữ thường, giữ nguyên độ dài chuỗi (để vị trí khớp với bản gốc). */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(foldChar(c))
        return sb.toString()
    }

    /** Vị trí bắt đầu của mọi chỗ khớp (không phân biệt hoa thường và dấu). */
    fun findAll(text: String, query: String): List<Int> {
        if (query.isEmpty()) return emptyList()
        val t = fold(text)
        val q = fold(query)
        val out = mutableListOf<Int>()
        var i = t.indexOf(q)
        while (i >= 0) {
            out.add(i)
            i = t.indexOf(q, i + q.length)
        }
        return out
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
