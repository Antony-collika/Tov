package com.example.byokchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
    refreshing: Boolean,
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
                Text("No AI connected yet", style = MaterialTheme.typography.titleMedium)
                Text("Connect an AI (OpenAI, Gemini, or a compatible server...) to choose a model.")
                Button(onClick = onConnect) { Text("Connect AI") }
            }
        } else {
            PickerBody(providers, providerId, model, refreshing, onSelect, onTogglePin)
        }
    }
}

@Composable
private fun PickerBody(
    providers: List<ProviderConfig>,
    providerId: String,
    model: String,
    refreshing: Boolean,
    onSelect: (String, String) -> Unit,
    onTogglePin: (String, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    val q = query.trim()
    fun matches(m: String) = q.isEmpty() || m.contains(q, ignoreCase = true)
    // Ghim ở trên cùng, gom từ mọi provider (mỗi hàng ghi rõ provider của nó).
    val pinned = providers.flatMap { p -> p.pinnedModels.filter(::matches).map { p to it } }
    // Nhập tay một tên mô hình thì áp cho provider của cuộc trò chuyện hiện tại.
    val manualProvider = providers.firstOrNull { it.id == providerId } ?: providers.first()

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Choose a model", style = MaterialTheme.typography.titleMedium)
        if (refreshing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Updating model list…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search models") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            if (pinned.isNotEmpty()) {
                item(key = "h-pin") { SectionLabel("Pinned") }
                items(pinned, key = { (p, m) -> "pin:${p.id}:$m" }) { (p, m) ->
                    ModelRow(m, subtitle = p.name, selected = p.id == providerId && m == model, pinned = true,
                        onClick = { onSelect(p.id, m) }, onTogglePin = { onTogglePin(p.id, m) })
                }
            }
            providers.forEach { p ->
                val list = p.models.filter(::matches)
                item(key = "h:${p.id}") { SectionLabel(p.name) }
                if (list.isEmpty()) {
                    item(key = "empty:${p.id}") {
                        Text(
                            if (q.isEmpty()) "No models saved yet. You can enter a model name in the row at the bottom."
                            else "No models match.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(list, key = { m -> "all:${p.id}:$m" }) { m ->
                    ModelRow(m, subtitle = null, selected = p.id == providerId && m == model, pinned = m in p.pinnedModels,
                        onClick = { onSelect(p.id, m) }, onTogglePin = { onTogglePin(p.id, m) })
                }
            }
            item(key = "manual") {
                Text(
                    "Enter another model name…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { manual = true }.padding(vertical = 14.dp, horizontal = 12.dp),
                )
            }
        }
    }

    if (manual) {
        var typed by remember { mutableStateOf(if (manualProvider.id == providerId) model else "") }
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text("Enter a model name") },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Model name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { manual = false; onSelect(manualProvider.id, typed.trim()) },
                    enabled = typed.isNotBlank(),
                ) { Text("Select") }
            },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("Cancel") } },
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
private fun ModelRow(model: String, subtitle: String?, selected: Boolean, pinned: Boolean, onClick: () -> Unit, onTogglePin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                model,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else null,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                if (pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (pinned) "Unpin model" else "Pin model",
            )
        }
    }
}
