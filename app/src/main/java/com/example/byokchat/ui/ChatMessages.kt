package com.example.byokchat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.images
import com.example.byokchat.util.TextUtil
import java.io.File

internal data class EditState(val messageId: String, val turnId: String, val text: String, val regen: Boolean, val allowBlank: Boolean)

internal class Actions(
    /** Copies [text] to the clipboard (always allowed, even while the AI is answering). */
    val copy: (String) -> Unit,
    val edit: (MessageEntity) -> Unit,
    val editRegen: (MessageEntity) -> Unit,
    val regen: (MessageEntity) -> Unit,
    val delete: (MessageEntity) -> Unit,
    val branch: (MessageEntity) -> Unit,
)

private const val LOCKED_NOTE = "Editing, deleting and regenerating are locked while the AI is answering."

// ============ Small pieces ============

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

@Composable
private fun EditedLabel() {
    Text("edited", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ============ Your message ============

/**
 * Your message. Press and hold opens the menu (bottom sheet): Copy | Select text | Edit message | Edit & regenerate | Delete.
 * [canRegen] adds a plain "Regenerate" only for a turn that has no AI answer at all (so it can always be retried).
 * [turnLabel] is the stable turn number ("T1") shown above the bubble (Đợt 4, like FPilot).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserMessage(
    m: MessageEntity,
    generating: Boolean,
    hl: Highlight?,
    edit: EditState?,
    actions: Actions,
    canRegen: Boolean,
    turnLabel: String? = null,
    onEditChange: (String) -> Unit,
    onEditSave: (EditState) -> Unit,
    onEditCancel: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var selectOpen by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // Decoded once per change instead of on every redraw.
    val imgs = remember(m.imagePaths) { m.images() }
    val annotated = remember(m.text, hl) { highlighted(m.text, hl) }
    val haptic = LocalHapticFeedback.current
    // Position of the last finger-down inside this message (px, relative to the
    // outer Column). combinedClickable's onLongClick does not report where the
    // finger is, so pointerInput records it; it only observes, never consumes.
    // Note: combinedClickable consumes the down event before it reaches this
    // parent pointerInput, so awaitFirstDown must not require it unconsumed —
    // otherwise the position is never recorded and the menu falls back to
    // offset zero (top-left corner).
    var pressOffset by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val menuOffset = with(density) { pressOffset?.let { DpOffset(it.x.toDp(), it.y.toDp()) } ?: DpOffset.Zero }
    Column(
        Modifier.fillMaxWidth().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    pressOffset = awaitFirstDown(requireUnconsumed = false).position
                }
            }
        },
        horizontalAlignment = Alignment.End,
    ) {
        if (turnLabel != null) {
            Text(turnLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Zero-size anchor at the top-start of the message; the dropdown opens at
        // the recorded press point (offset from the anchor).
        if (menuOpen) {
            Box(Modifier.align(Alignment.Start)) {
                val items = buildList {
                    if (m.text.isNotBlank()) {
                        add(MenuAction(Icons.Default.ContentCopy, "Copy") { actions.copy(m.text) })
                        add(MenuAction(Icons.Default.SelectAll, "Select text") { selectOpen = true })
                    }
                    add(MenuAction(Icons.Default.Edit, "Edit message", enabled = !generating) { actions.edit(m) })
                    add(MenuAction(Icons.Default.Autorenew, "Edit & regenerate", enabled = !generating) { actions.editRegen(m) })
                    if (canRegen) add(MenuAction(Icons.Default.Refresh, "Regenerate", enabled = !generating) { actions.regen(m) })
                    add(MenuAction(Icons.Default.Delete, "Delete", enabled = !generating, destructive = true) { actions.delete(m) })
                }
                MessageMenuDropdown(items, LOCKED_NOTE, offset = menuOffset) { menuOpen = false }
            }
        }
        if (edit != null) {
            MessageEditor(edit, if (edit.regen) "Save & regenerate" else "Save", onEditChange, onEditSave, onEditCancel)
        } else {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 300.dp).combinedClickable(
                    onClick = {},
                    onLongClickLabel = "Message options",
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (imgs.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            imgs.forEachIndexed { idx, path ->
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = "Attached image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).clickable { viewerIndex = idx },
                                )
                            }
                        }
                    }
                    if (m.text.isNotEmpty()) {
                        Text(annotated, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            if (m.edited) EditedLabel()
        }
    }
    viewerIndex?.let { idx -> ImageViewerDialog(imgs, idx) { viewerIndex = null } }
    if (selectOpen) SelectTextSheet(m.text, onCopyAll = { actions.copy(m.text) }, onDismiss = { selectOpen = false })
}

// ============ AI message ============

/**
 * The AI's message. Press and hold anywhere on it opens the menu:
 * Copy | Select text | Edit message | Branch in new chat | Regenerate | Delete.
 * The Copy / Regenerate / Branch row underneath is only shown for the last answer ([showActions]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantMessage(
    m: MessageEntity,
    generating: Boolean,
    hl: Highlight?,
    edit: EditState?,
    actions: Actions,
    toolResults: ToolResults,
    showActions: Boolean,
    errorFix: ErrorFix?,
    onErrorFix: (ErrorFix) -> Unit,
    onEditChange: (String) -> Unit,
    onEditSave: (EditState) -> Unit,
    onEditCancel: () -> Unit,
    liveText: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Đợt provider 12/11/2026: khối gập "Suy luận" mở hay đóng (theo từng tin).
    var reasoningOpen by remember(m.id) { mutableStateOf(false) }
    // Snapshot of the text taken when "Select text" is chosen, so it does not change under your finger while the AI is still typing.
    var selectText by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    // Position of the last finger-down inside this message (px, relative to the
    // outer Box below). Presses on Compose parts are recorded by pointerInput on
    // the Box; presses on the markdown TextView are recorded by its
    // OnTouchListener and converted into this same space (see MarkdownText).
    // Same note as in UserMessage: the down is consumed by combinedClickable
    // first, so awaitFirstDown must not require it unconsumed.
    var pressOffset by remember { mutableStateOf<Offset?>(null) }
    // Coordinates of the outer Box, passed down so the TextView can convert a
    // touch position into this Box's space.
    var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    val menuOffset = with(density) { pressOffset?.let { DpOffset(it.x.toDp(), it.y.toDp()) } ?: DpOffset.Zero }
    // While streaming, the in-memory text is newer than what is in the database.
    val shownText = if (liveText != null && liveText.length >= m.text.length) liveText else m.text
    // Opens the menu at [offset] (px, relative to the outer Box). Also handed to
    // the markdown body so a long-press on the AI text itself (a real Android
    // TextView, which swallows the Compose gesture) opens it too. A null offset
    // keeps the last recorded press position.
    val openMenu: (Offset?) -> Unit = { offset ->
        if (edit == null) {
            if (offset != null) pressOffset = offset
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menuOpen = true
        }
    }
    // The menu anchor lives in this outer Box, not inside the spaced Column below:
    // adding it as a child of the Column would insert an extra 8.dp gap and push
    // the whole message down when the menu opens. A Box stacks its children, so
    // toggling the menu never moves the message content.
    Box(
        Modifier.fillMaxWidth()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        pressOffset = awaitFirstDown(requireUnconsumed = false).position
                    }
                }
            }
            .onGloballyPositioned { boxCoords = it },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClickLabel = "Message options",
                    onLongClick = { openMenu(pressOffset) },
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (edit != null) {
                MessageEditor(edit, "Save", onEditChange, onEditSave, onEditCancel)
                return@Column
            }
            if (shownText.isNotEmpty()) {
                MarkdownBody(
                    shownText, color = MaterialTheme.colorScheme.onSurface, highlight = hl,
                    onLongPress = openMenu, anchorCoordinates = boxCoords,
                    streaming = m.status == "streaming",
                )
            } else if (m.status == "streaming") {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            // Đợt provider 12/11/2026: khối gập "Suy luận" — chữ suy luận của
            // hãng (OpenAI / máy chủ tương thích) lưu cùng tin, hiện dạng text
            // thường (không markdown — suy luận không phải câu trả lời).
            // Chỉ hiện khi tin đã lưu xong: lúc đang stream suy luận chưa
            // lưu, tránh hiện nội dung nửa chừng.
            if (!m.reasoningText.isNullOrBlank() && m.status != "streaming") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.clickable { reasoningOpen = !reasoningOpen },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (reasoningOpen) "▾ Suy luận" else "▸ Suy luận",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (reasoningOpen) {
                        Text(
                            m.reasoningText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (m.toolCallsJson != null) ToolCards(m, toolResults, generating)
            if (m.status == "error") {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The error text has two parts: a friendly sentence, then (after the first line break) a details line.
                        val err = m.error ?: "Something went wrong."
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = { actions.regen(m) }, enabled = !generating) { Text("Retry") }
                            if (errorFix != null) {
                                OutlinedButton(onClick = { onErrorFix(errorFix) }) { Text(errorFix.label()) }
                            }
                        }
                    }
                }
            }
            if (m.status != "streaming" && m.status != "error") {
                if (showActions) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Copy stays available while the AI is answering; the others change data, so they are locked.
                        if (shownText.isNotBlank()) ActionIcon(Icons.Default.ContentCopy, "Copy", true) { actions.copy(shownText) }
                        ActionIcon(Icons.Default.Refresh, "Regenerate", !generating) { actions.regen(m) }
                        ActionIcon(Icons.Default.CallSplit, "Branch in new chat", !generating) { actions.branch(m) }
                        Spacer(Modifier.weight(1f))
                        if (m.edited) EditedLabel()
                    }
                } else if (m.edited) {
                    EditedLabel()
                }
            } else if (m.edited) {
                EditedLabel()
            }
        }
        // Zero-size anchor at the top-start of the message; the dropdown opens at
        // the recorded press point (offset from the anchor).
        if (menuOpen) {
            Box(Modifier.align(Alignment.TopStart)) {
                val items = buildList {
                    if (shownText.isNotBlank()) {
                        add(MenuAction(Icons.Default.ContentCopy, "Copy") { actions.copy(shownText) })
                        add(MenuAction(Icons.Default.SelectAll, "Select text") { selectText = shownText })
                        if (m.status != "error") {
                            add(MenuAction(Icons.Default.Edit, "Edit message", enabled = !generating) { actions.edit(m) })
                        }
                    }
                    if (m.status != "error") {
                        add(MenuAction(Icons.Default.CallSplit, "Branch in new chat", enabled = !generating) { actions.branch(m) })
                    }
                    add(MenuAction(Icons.Default.Refresh, "Regenerate", enabled = !generating) { actions.regen(m) })
                    add(MenuAction(Icons.Default.Delete, "Delete", enabled = !generating, destructive = true) { actions.delete(m) })
                }
                MessageMenuDropdown(items, LOCKED_NOTE, offset = menuOffset) { menuOpen = false }
            }
        }
    }
    selectText?.let { snap -> SelectTextSheet(snap, onCopyAll = { actions.copy(snap) }, onDismiss = { selectText = null }) }
}

// ============ Editing ============

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
            label = { Text("Edit message (text only; images stay the same)") },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { full = true }) { Text("Expand") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
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
                        Text("Edit message", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { full = false }) { Text("Collapse") }
                    }
                    OutlinedTextField(value = state.text, onValueChange = onChange, modifier = Modifier.weight(1f).fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                        TextButton(onClick = { full = false; onCancel() }) { Text("Cancel") }
                        Button(onClick = { full = false; onSave(state) }, enabled = canSave) { Text(saveLabel) }
                    }
                }
            }
        }
    }
}
