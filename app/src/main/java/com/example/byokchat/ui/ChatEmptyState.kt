package com.example.byokchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val suggestions = listOf(
    "Giải thích một khái niệm thật dễ hiểu",
    "Giúp mình soạn một email lịch sự",
    "Tóm tắt đoạn văn mình sắp dán vào",
    "Lên kế hoạch cho một ngày làm việc",
)

/**
 * Màn trò chuyện trống.
 * - chưa có nhà cung cấp: thẻ "Chưa có AI nào được kết nối";
 * - có nhà cung cấp nhưng chưa chọn mô hình: thẻ "Hãy chọn mô hình";
 * - còn lại: lời chào và 4 gợi ý (bấm chỉ điền vào ô nhập, không tự gửi).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatEmptyState(
    noProvider: Boolean,
    noModel: Boolean,
    onSuggestion: (String) -> Unit,
    onConnect: () -> Unit,
    onPickModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            noProvider -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Chưa có AI nào được kết nối", style = MaterialTheme.typography.titleMedium)
                    Text("Hãy kết nối một AI (Claude, OpenAI, Gemini...) để bắt đầu trò chuyện.")
                    Button(onClick = onConnect) { Text("Kết nối AI") }
                }
            }
            noModel -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hãy chọn mô hình", style = MaterialTheme.typography.titleMedium)
                    Text("Chọn một mô hình để AI có thể trả lời bạn.")
                    Button(onClick = onPickModel) { Text("Chọn mô hình") }
                }
            }
            else -> {
                Text(
                    "Xin chào! Bạn muốn hỏi gì?",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                suggestions.forEach { s ->
                    AssistChip(onClick = { onSuggestion(s) }, label = { Text(s) })
                }
            }
        }
    }
}
