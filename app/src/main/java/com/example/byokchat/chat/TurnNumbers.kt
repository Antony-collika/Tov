package com.example.byokchat.chat

import com.example.byokchat.data.MessageEntity

/**
 * Số lượt ổn định (Đợt 4): mỗi lượt có một số [turnNo] lưu trong DB, chỉ tăng
 * không giảm — xóa lượt không làm các số còn lại dồn lại, số đã xóa không
 * bao giờ dùng lại. Mọi chỗ từng đếm lượt theo vị trí đều dùng số này.
 *
 * Hàng chưa có số (turnNo == 0 — chỉ gặp ở dữ liệu cũ nếu migration chưa
 * chạy, hoặc ở test) được đánh số dự phòng theo thứ tự xuất hiện, lấy số
 * dương nhỏ nhất chưa ai dùng, để hiển thị không bao giờ trống số.
 */
object TurnNumbers {

    /** Số lượt của từng turnId, theo thứ tự xuất hiện đầu tiên trong [rows] (đã xếp theo seq). */
    fun effectiveTurnNumbers(rows: List<MessageEntity>): Map<String, Int> {
        val stored = rows.groupBy({ it.turnId }, { it.turnNo })
            .mapValues { (_, nos) -> nos.firstOrNull { it > 0 } ?: 0 }
        val used = stored.values.filter { it > 0 }.toMutableSet()
        var nextFallback = 1
        val out = LinkedHashMap<String, Int>()
        for ((turnId, n) in stored) {
            if (n > 0) {
                out[turnId] = n
            } else {
                while (nextFallback in used) nextFallback++
                out[turnId] = nextFallback
                used += nextFallback
                nextFallback++
            }
        }
        return out
    }

    /** Số lượt tiếp theo cho một lượt mới: số lớn nhất hiện có + 1. */
    fun nextTurnNo(rows: List<MessageEntity>): Int =
        (effectiveTurnNumbers(rows).values.maxOrNull() ?: 0) + 1
}
