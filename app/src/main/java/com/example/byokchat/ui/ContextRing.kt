package com.example.byokchat.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Vòng tròn nhỏ cho biết cuộc trò chuyện đã dùng bao nhiêu phần "trí nhớ" của AI. Bấm để đọc giải thích. */
@Composable
fun ContextRing(used: Int, limit: Int, dropped: Int) {
    if (limit <= 0) return
    var open by remember { mutableStateOf(false) }
    val fraction = (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    val color = when {
        fraction > 0.9f -> MaterialTheme.colorScheme.error
        fraction >= 0.7f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    IconButton(onClick = { open = true }) {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(24.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 3.dp,
        )
    }
    if (open) {
        val message = buildString {
            append("AI nhớ được khoảng ${formatTokens(limit)} đơn vị chữ (token) trong một cuộc trò chuyện. ")
            append("Hiện đã dùng khoảng ${formatTokens(used)}.")
            if (dropped > 0) append("\n\n$dropped lượt cũ nhất không còn được gửi cho AI để vừa giới hạn.")
            append("\n\nĐây là số ước lượng.")
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Bộ nhớ của cuộc trò chuyện") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Đóng") } },
        )
    }
}
