package com.example.byokchat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.tools.Risk

/** Hộp xác nhận hiện ở mọi màn hình khi AI muốn chạy một công cụ ở chế độ "Hỏi trước". */
@Composable
fun ConfirmationHost() {
    val broker = LocalContext.current.container.confirmations
    val pending by broker.pending.collectAsState()
    val req = pending.firstOrNull() ?: return
    val danger = req.risk == Risk.DELETE
    AlertDialog(
        onDismissRequest = { /* bắt buộc chọn Cho phép hoặc Từ chối */ },
        title = { Text(if (danger) "AI muốn XÓA dữ liệu" else "AI muốn thực hiện thao tác") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Công cụ: ${req.toolName}", style = MaterialTheme.typography.labelLarge)
                Text(
                    req.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (pending.size > 1) {
                    Text(
                        "Còn ${pending.size - 1} yêu cầu đang chờ.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { broker.resolve(req, true) }) {
                Text(if (danger) "Xóa" else "Cho phép", color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = { broker.resolve(req, false) }) { Text("Từ chối") } },
    )
}
