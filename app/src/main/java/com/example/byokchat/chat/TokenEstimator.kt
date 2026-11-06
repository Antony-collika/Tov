package com.example.byokchat.chat

import com.example.byokchat.ai.AiMessage

/** Đếm token ước lượng (thiên về dư, để không bị tràn giới hạn của mô hình). */
object TokenEstimator {
    fun text(s: String): Int {
        var t = 0.0
        for (c in s) {
            val code = c.code
            t += when {
                code < 128 -> 0.28
                code in 0x2E80..0xD7AF || code in 0xF900..0xFAFF -> 1.0
                else -> 0.5
            }
        }
        return Math.ceil(t).toInt()
    }

    fun message(m: AiMessage): Int =
        6 + text(m.text) + m.imagePaths.size * 1100 + m.toolCalls.sumOf { text(it.name) + text(it.argsJson) + 8 }
}
