package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.byokchat.data.ProviderConfig

@Composable
fun TextEditDialog(
    title: String,
    initial: String,
    multiline: Boolean = false,
    hint: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = !multiline,
                    minLines = if (multiline) 4 else 1,
                    maxLines = if (multiline) 12 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Đồng ý",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

@Composable
fun ModelPickerDialog(
    providers: List<ProviderConfig>,
    providerId: String,
    model: String,
    onConfirm: (providerId: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pid by remember { mutableStateOf(providerId.ifBlank { providers.firstOrNull()?.id ?: "" }) }
    var mdl by remember { mutableStateOf(model) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chọn mô hình") },
        text = {
            if (providers.isEmpty()) {
                Text("Bạn chưa thêm nhà cung cấp AI nào. Hãy vào Cài đặt → Nhà cung cấp AI để thêm.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Nhà cung cấp", style = MaterialTheme.typography.labelLarge)
                    providers.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                pid = p.id
                                mdl = p.defaultModel.ifBlank { p.models.firstOrNull() ?: "" }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = pid == p.id, onClick = null, modifier = Modifier.padding(8.dp))
                            Text(p.name)
                        }
                    }
                    val current = providers.firstOrNull { it.id == pid }
                    Text("Mô hình", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    current?.models?.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable { mdl = m },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mdl == m, onClick = null, modifier = Modifier.padding(8.dp))
                            Text(m)
                        }
                    }
                    OutlinedTextField(
                        value = mdl,
                        onValueChange = { mdl = it },
                        label = { Text("Hoặc tự nhập tên mô hình") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pid, mdl.trim()) },
                enabled = pid.isNotBlank() && mdl.isNotBlank(),
            ) { Text("Chọn") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}
