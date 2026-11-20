package com.example.byokchat.chat

import com.example.byokchat.data.MessageEntity

/**
 * Số token thật đã dùng cho lượt sắp gửi, tính từ tổng input + output mà
 * server đếm ở lượt Main gần nhất (20/11/2026).
 *
 * - Câu trả lời của chính lượt đã đếm KHÔNG cộng thêm: nó là output, đã nằm
 *   trong tổng rồi. Reasoning cũng vậy — nó đã nằm trong output
 *   (Usage.reasoningTokens chỉ là một phần của outputTokens), cộng nữa là
 *   đếm hai lần.
 * - Lượt mới hơn lượt đã đếm (vd lượt gửi lỗi nên không có usage): cộng ước
 *   lượng của nó — lượt này chưa nằm trong tổng đã đếm.
 * - Lượt bị cắt khỏi request sắp gửi (droppedTurnIds): trừ ước lượng của nó
 *   — nó từng nằm trong prompt đã đếm nhưng sẽ không được gửi nữa.
 * Ước lượng từng hàng dùng chung TokenEstimator như RequestAssembler; ảnh và
 * tool call trong các hàng điều chỉnh này không tính (sai số nhỏ, chỉ xuất
 * hiện khi có lượt mới hoặc lượt bị cắt).
 */
fun realUsedTokens(
    lastTotal: Int,
    lastTurnNo: Int,
    rows: List<MessageEntity>,
    droppedTurnIds: Set<String>,
): Int {
    var used = lastTotal
    for (row in rows) {
        if (row.turnNo > lastTurnNo) used += estimateRow(row)
        if (row.turnId in droppedTurnIds) used -= estimateRow(row)
    }
    return maxOf(used, 0)
}

private fun estimateRow(row: MessageEntity): Int =
    TokenEstimator.text(row.text) + 6
