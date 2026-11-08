package com.example.byokchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.data.ProviderConfig

/** Khung trượt từ dưới lên để chọn mô hình cho cuộc trò chuyện hiện tại. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    providerId: String,
    model: String,
    onSelect: (providerId: String, model: String) -> Unit,
    onTogglePin: (providerId: String, model: String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (providers.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Chưa có AI nào được kết nối", style = MaterialTheme.typography.titleMedium)
                Text("Hãy kết nối một AI (Claude, OpenAI, Gemini...) để chọn mô hình.")
                Button(onClick = onConnect) { Text("Kết nối AI") }
            }
        } else {
            PickerBody(providers, providerId, model, onSelect, onTogglePin)
        }
    }
}

@Composable
private fun PickerBody(
    providers: List<ProviderConfig>,
    providerId: String,
    model: String,
    onSelect: (String, String) -> Unit,
    onTogglePin: (String, String) -> Unit,
) {
    var pid by remember { mutableStateOf(providerId.takeIf { id -> providers.any { it.id == id } } ?: providers.first().id) }
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    val cur = providers.firstOrNull { it.id == pid } ?: providers.first()
    val q = query.trim()
    val pinned = cur.pinnedModels.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
    val all = cur.models.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Chọn mô hình", style = MaterialTheme.typography.titleMedium)
        if (providers.size >= 2) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { p ->
                    FilterChip(selected = p.id == cur.id, onClick = { pid = p.id }, label = { Text(p.name) })
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Tìm mô hình") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            if (pinned.isNotEmpty()) {
                item(key = "h-pin") { SectionLabel("Đã ghim") }
                items(pinned, key = { "pin:$it" }) { m ->
                    ModelRow(m, selected = cur.id == providerId && m == model, pinned = true,
                        onClick = { onSelect(cur.id, m) }, onTogglePin = { onTogglePin(cur.id, m) })
                }
            }
            item(key = "h-all") { SectionLabel("Tất cả") }
            if (all.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (q.isEmpty()) "Nhà cung cấp này chưa có mô hình nào. Hãy nhập tên mô hình ở dòng bên dưới."
                        else "Không có mô hình nào khớp.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(all, key = { "all:$it" }) { m ->
                ModelRow(m, selected = cur.id == providerId && m == model, pinned = m in cur.pinnedModels,
                    onClick = { onSelect(cur.id, m) }, onTogglePin = { onTogglePin(cur.id, m) })
            }
            item(key = "manual") {
                Text(
                    "Nhập tên mô hình khác…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { manual = true }.padding(vertical = 14.dp, horizontal = 12.dp),
                )
            }
        }
    }

    if (manual) {
        var typed by remember { mutableStateOf(if (cur.id == providerId) model else "") }
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text("Nhập tên mô hình") },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Tên mô hình") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { manual = false; onSelect(cur.id, typed.trim()) },
                    enabled = typed.isNotBlank(),
                ) { Text("Chọn") }
            },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("Hủy") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModelRow(model: String, selected: Boolean, pinned: Boolean, onClick: () -> Unit, onTogglePin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            model,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else null,
        )
        IconButton(onClick = onTogglePin) {
            Icon(
                if (pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (pinned) "Bỏ ghim mô hình" else "Ghim mô hình",
            )
        }
    }
}
