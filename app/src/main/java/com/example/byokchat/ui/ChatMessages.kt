package com.example.byokchat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.images
import com.example.byokchat.util.TextUtil
import java.io.File

internal data class EditState(val messageId: String, val turnId: String, val text: String, val regen: Boolean, val allowBlank: Boolean)

internal class Actions(
    val copy: (MessageEntity) -> Unit,
    val edit: (MessageEntity) -> Unit,
    val editRegen: (MessageEntity) -> Unit,
    val regen: (MessageEntity) -> Unit,
    val delete: (MessageEntity) -> Unit,
    val branch: (MessageEntity) -> Unit,
)

// ============ Tin nhắn ============

@Composable
private fun MessageMenu(enabled: Boolean, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "Tùy chọn tin nhắn", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
        }
    }
}

private fun highlighted(text: String, hl: Highlight?): AnnotatedString {
    if (hl == null || hl.query.isEmpty()) return AnnotatedString(text)
    val hits = TextUtil.findAll(text, hl.query)
    return buildAnnotatedString {
        append(text)
        hits.forEachIndexed { i, s ->
            val c = if (i == hl.currentOccurrence) Color(0xFFFF9800) else Color(0x99FFEB3B)
            addStyle(SpanStyle(background = c, color = Color.Black), s, minOf(text.length, s + hl.query.length))
        }
    }
}

@Composable
internal fun UserMessage(
    m: MessageEntity,
    generating: Boolean,
    hl: Highlight?,
    edit: EditState?,
    actions: Actions,
    onEditChange: (String) -> Unit,
    onEditSave: (EditState) -> Unit,
    onEditCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (edit != null) {
            MessageEditor(edit, if (edit.regen) "Lưu & tạo lại" else "Lưu", onEditChange, onEditSave, onEditCancel)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Top) {
                MessageMenu(
                    enabled = !generating,
                    items = listOf(
                        "Sao chép" to { actions.copy(m) },
                        "Sửa tin nhắn" to { actions.edit(m) },
                        "Sửa & tạo lại" to { actions.editRegen(m) },
                        "Tạo lại" to { actions.regen(m) },
                        "Xóa" to { actions.delete(m) },
                    ),
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    modifier = Modifier.widthIn(max = 300.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val imgs = m.images()
                        if (imgs.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                imgs.forEach { path ->
                                    AsyncImage(
                                        model = File(path),
                                        contentDescription = "Ảnh đính kèm",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)),
                                    )
                                }
                            }
                        }
                        if (m.text.isNotEmpty()) {
                            Text(highlighted(m.text, hl), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
            if (m.edited) EditedLabel()
        }
    }
}

@Composable
private fun EditedLabel() {
    Text("đã chỉnh sửa", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun AssistantMessage(
    m: MessageEntity,
    generating: Boolean,
    hl: Highlight?,
    edit: EditState?,
    actions: Actions,
    toolResults: Map<String, MessageEntity>,
    onEditChange: (String) -> Unit,
    onEditSave: (EditState) -> Unit,
    onEditCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (edit != null) {
            MessageEditor(edit, "Lưu", onEditChange, onEditSave, onEditCancel)
            return@Column
        }
        if (m.text.isNotEmpty()) {
            MarkdownText(m.text, color = MaterialTheme.colorScheme.onSurface, highlight = hl)
        } else if (m.status == "streaming") {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (m.toolCallsJson != null) ToolCards(m, toolResults, generating)
        if (m.status == "error") {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Text(
                    m.error ?: "Đã xảy ra lỗi.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (m.status != "streaming") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.edited) EditedLabel()
                Spacer(Modifier.weight(1f))
                val items = when {
                    m.status == "error" -> buildList {
                        if (m.text.isNotBlank()) add("Sao chép" to { actions.copy(m) })
                        add("Tạo lại" to { actions.regen(m) })
                        add("Xóa" to { actions.delete(m) })
                    }
                    else -> buildList {
                        if (m.text.isNotBlank()) add("Sao chép" to { actions.copy(m) })
                        if (m.text.isNotBlank()) add("Sửa tin nhắn" to { actions.edit(m) })
                        add("Branch (tạo cuộc mới từ đây)" to { actions.branch(m) })
                        add("Xóa" to { actions.delete(m) })
                    }
                }
                MessageMenu(enabled = !generating, items = items)
            }
        }
    }
}

// ============ Soạn tin ============

@Composable
private fun MessageEditor(
    state: EditState,
    saveLabel: String,
    onChange: (String) -> Unit,
    onSave: (EditState) -> Unit,
    onCancel: () -> Unit,
) {
    var full by remember { mutableStateOf(false) }
    val canSave = state.text.isNotBlank() || state.allowBlank
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = state.text,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 12,
            label = { Text("Sửa tin nhắn (chỉ phần chữ; ảnh giữ nguyên)") },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { full = true }) { Text("Phóng to") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Hủy") }
            Button(onClick = { onSave(state) }, enabled = canSave) { Text(saveLabel) }
        }
    }
    if (full) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { full = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sửa tin nhắn", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { full = false }) { Text("Thu nhỏ") }
                    }
                    OutlinedTextField(value = state.text, onValueChange = onChange, modifier = Modifier.weight(1f).fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                        TextButton(onClick = { full = false; onCancel() }) { Text("Hủy") }
                        Button(onClick = { full = false; onSave(state) }, enabled = canSave) { Text(saveLabel) }
                    }
                }
            }
        }
    }
}
