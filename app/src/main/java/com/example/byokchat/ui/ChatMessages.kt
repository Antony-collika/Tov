package com.example.byokchat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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

/** Nút ⋮ kèm menu (dùng cho tin của AI). Vùng chạm đủ 48dp, chỉ biểu tượng bên trong được thu nhỏ. */
@Composable
private fun MessageMenu(enabled: Boolean, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Tùy chọn tin nhắn",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier.size(20.dp),
            )
        }
        MessageMenuItems(open, { open = false }, items)
    }
}

@Composable
private fun MessageMenuItems(open: Boolean, onDismiss: () -> Unit, items: List<Pair<String, () -> Unit>>) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        items.forEach { (label, action) ->
            DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); action() })
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(20.dp),
        )
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

@OptIn(ExperimentalFoundationApi::class)
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
    var menuOpen by remember { mutableStateOf(false) }
    var selectOpen by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (edit != null) {
            MessageEditor(edit, if (edit.regen) "Lưu & tạo lại" else "Lưu", onEditChange, onEditSave, onEditCancel)
        } else {
            val menuItems = buildList<Pair<String, () -> Unit>> {
                add("Sao chép" to { actions.copy(m) })
                if (m.text.isNotBlank()) add("Chọn chữ" to { selectOpen = true })
                add("Sửa tin nhắn" to { actions.edit(m) })
                add("Sửa & tạo lại" to { actions.editRegen(m) })
                add("Tạo lại" to { actions.regen(m) })
                add("Xóa" to { actions.delete(m) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Top) {
                IconButton(onClick = { menuOpen = true }, enabled = !generating) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Tùy chọn tin nhắn",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (!generating) 1f else 0.38f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                // Menu neo vào bong bóng; cả nút ⋮ và chạm giữ đều mở cùng menu này.
                Box {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                        modifier = Modifier.widthIn(max = 300.dp).combinedClickable(
                            enabled = !generating,
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuOpen = true
                            },
                        ),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val imgs = m.images()
                            if (imgs.isNotEmpty()) {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    imgs.forEachIndexed { idx, path ->
                                        AsyncImage(
                                            model = File(path),
                                            contentDescription = "Ảnh đính kèm",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).clickable { viewerIndex = idx },
                                        )
                                    }
                                }
                            }
                            if (m.text.isNotEmpty()) {
                                Text(highlighted(m.text, hl), color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                    MessageMenuItems(menuOpen, { menuOpen = false }, menuItems)
                }
            }
            if (m.edited) EditedLabel()
        }
    }
    viewerIndex?.let { idx -> ImageViewerDialog(m.images(), idx) { viewerIndex = null } }
    if (selectOpen) SelectTextSheet(m.text, onCopyAll = { actions.copy(m) }, onDismiss = { selectOpen = false })
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
    showActions: Boolean,
    onEditChange: (String) -> Unit,
    onEditSave: (EditState) -> Unit,
    onEditCancel: () -> Unit,
    liveText: String? = null,
) {
    var selectOpen by remember { mutableStateOf(false) }
    // While streaming, the in-memory text is newer than what is in the database.
    val shownText = if (liveText != null && liveText.length >= m.text.length) liveText else m.text
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (edit != null) {
            MessageEditor(edit, "Lưu", onEditChange, onEditSave, onEditCancel)
            return@Column
        }
        if (shownText.isNotEmpty()) {
            MarkdownBody(shownText, color = MaterialTheme.colorScheme.onSurface, highlight = hl)
        } else if (m.status == "streaming") {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (m.toolCallsJson != null) ToolCards(m, toolResults, generating)
        if (m.status == "error") {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Chuỗi lỗi có hai phần: câu thân thiện, rồi (sau dấu xuống dòng đầu tiên) dòng "Chi tiết: ..."
                    val err = m.error ?: "Đã xảy ra lỗi."
                    val nl = err.indexOf('\n')
                    if (nl < 0) {
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(err.substring(0, nl), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            err.substring(nl + 1).trim(),
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FilledTonalButton(onClick = { actions.regen(m) }, enabled = !generating) { Text("Thử lại") }
                }
            }
        }
        if (m.status != "streaming") {
            if (showActions) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.status == "error") {
                        // Thẻ lỗi đã có nút "Thử lại" nên menu chỉ còn Sao chép / Xóa.
                        MessageMenu(
                            enabled = !generating,
                            items = buildList<Pair<String, () -> Unit>> {
                                if (m.text.isNotBlank()) add("Sao chép" to { actions.copy(m) })
                                add("Xóa" to { actions.delete(m) })
                            },
                        )
                    } else {
                        if (m.text.isNotBlank()) ActionIcon(Icons.Default.ContentCopy, "Sao chép", !generating) { actions.copy(m) }
                        ActionIcon(Icons.Default.Refresh, "Tạo lại", !generating) { actions.regen(m) }
                        ActionIcon(Icons.Default.CallSplit, "Rẽ nhánh (tạo cuộc trò chuyện mới từ đây)", !generating) { actions.branch(m) }
                        MessageMenu(
                            enabled = !generating,
                            items = buildList<Pair<String, () -> Unit>> {
                                if (m.text.isNotBlank()) add("Chọn chữ" to { selectOpen = true })
                                if (m.text.isNotBlank()) add("Sửa tin nhắn" to { actions.edit(m) })
                                add("Xóa" to { actions.delete(m) })
                            },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (m.edited) EditedLabel()
                }
            } else if (m.edited) {
                EditedLabel()
            }
        }
    }
    if (selectOpen) SelectTextSheet(m.text, onCopyAll = { actions.copy(m) }, onDismiss = { selectOpen = false })
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
