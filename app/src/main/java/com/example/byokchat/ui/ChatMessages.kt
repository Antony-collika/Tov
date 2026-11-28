package com.example.byokchat.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.byokchat.container
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
                    // No timestamp on a user message (28/11/2026: it
                    // wastes space). The turn label above the bubble
                    // stays.
                }
            }
            if (m.edited) EditedLabel()
        }
    }
    viewerIndex?.let { idx -> ImageViewerDialog(imgs.map { File(it) }, idx) { viewerIndex = null } }
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
    /** The work segments whose stream renders on this message (the
     * bursts attached to it, in order; empty = no work here). A burst
     * with no following text renders on its own anchor row instead. */
    workSegments: List<WorkSegment> = emptyList(),
    /** Phase 4: the name of the agent this segment's sub is waiting for (null = not waiting) — the collapsed line says "đang chờ [tên] nói xong". */
    pillWaitingForName: String? = null,
    /** Files this turn created (txt.create / txt.write paths): one card per file, under the turn's last text message. */
    fileCards: List<String> = emptyList(),
    /** Who sent this message (Main when null sender, else the sub-agent). Null = unknown, no name/avatar. */
    senderMember: GroupMember? = null,
    /** Tapping a sub-agent's avatar opens its Main↔sub conversation; null = avatar does nothing (Main). */
    onAvatarClick: (() -> Unit)? = null,
    /** Whether this message's error card was dismissed (state lives in the
     * ViewModel, not in this composable's remember — a LazyColumn item's
     * composition is disposed when it scrolls off-screen, so a remember
     * here is lost on scroll and the card reappears; the ViewModel set
     * survives scrolling and dies with the chat = session only). */
    errorDismissed: Boolean = false,
    /** Dismiss this message's error card (records its id in the ViewModel). */
    onDismissError: (() -> Unit)? = null,
    showActions: Boolean,
    errorFix: ErrorFix?,
    onErrorFix: (ErrorFix) -> Unit,
    onEditChange: (String) -> Unit,
    onEditSave: (EditState) -> Unit,
    onEditCancel: () -> Unit,
    liveText: String? = null,
    /** Câu giải thích nguyên nhân lỗi bằng lời dễ hiểu (đợt bỏ provider 12/11/2026) — null = lỗi đã tự giải thích. */
    errorExplanation: String? = null,
    /** Retry trong bong bóng lỗi: gửi lại request đầy đủ (null = Regenerate thường). */
    onRetryError: (() -> Unit)? = null,
    /** Nút Retry đã BỚT, ghi rõ (dữ liệu đã mất, không khôi phục được) — null = không có nút này. */
    reducedRetryLabel: String? = null,
    /** Hành động cho nút Retry đã BỚT. */
    onReducedRetry: (() -> Unit)? = null,
    /** Lượt này đã Retry một lần mà vẫn lỗi: thêm nút mở cuộc mới. */
    showNewChat: Boolean = false,
    onNewChat: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Dismissing the error card hides it for this session: the dismissed
    // ids live in the ViewModel ([errorDismissed] / [onDismissError]),
    // so scrolling does not bring the card back. Nothing is deleted or
    // retried; reopening the chat shows it again.
    val context = LocalContext.current
    // Opens a created file with the app's shared opener; a missing file
    // only shows a toast, never a crash.
    val openFile: (String) -> Unit = { path ->
        // onError must be named: the last parameter of openFileAt is the
        // Boolean inSessionStorage, so a trailing lambda cannot bind to it.
        openFileAt(context, context.container.fs, path, onError = { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        })
    }
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
            // Work stream order (28/11/2026 revision, Duy): one
            // block per agent — Avatar + Name first, then that agent's
            // work, then its text. The work is inline text lines under
            // its own header, at the place it happened — never a bare
            // "X Worked for …" floating above the header, and never
            // merged across the turn. Reasoning only appears when the
            // provider really returned readable text (never invented). A
            // turn with no tools and no reasoning has no segment, so no
            // line at all.
            if (workSegments.isNotEmpty() || shownText.isNotEmpty()) {
                // Header row (avatar + agent name + time) sits above
                // both the work and the answer. A work-only row still
                // gets its header, so the work is always under the
                // agent it belongs to.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (senderMember != null) {
                        MemberAvatar(senderMember, size = 32.dp, onClick = onAvatarClick)
                    }
                    if (senderMember != null) {
                        // The name is tappable with the same action as
                        // the avatar (28/11/2026, Duy): a sub's name opens
                        // its Main↔sub conversation, Main's name opens
                        // Main's inbox — same as [onAvatarClick].
                        Text(
                            senderMember.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = memberColor(senderMember),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                                .then(if (onAvatarClick != null) Modifier.clickable(onClick = onAvatarClick) else Modifier),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Text(
                        formatClock(m.createdAt),
                        fontSize = 12.sp,
                        color = Color(0xFF8A8D94),
                    )
                }
                workSegments.forEach { segment ->
                    WorkStream(segment, generating, waitingForName = pillWaitingForName, senderMember = senderMember)
                }
            }
            if (shownText.isNotEmpty()) {
                // Late result of a sub-agent (Phase 4): the task title as
                // an anchor, so a message that arrives after the
                // conversation moved on still says which task it belongs to.
                if (!m.taskTitle.isNullOrBlank()) {
                    Text(
                        "↩ ${m.taskTitle}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MarkdownBody(
                    shownText, color = MaterialTheme.colorScheme.onSurface, highlight = hl,
                    onLongPress = openMenu, anchorCoordinates = boxCoords,
                    streaming = m.status == "streaming",
                )
            } else if (m.status == "streaming") {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            // Files this turn created: one card per file, right under the
            // answer (or under the work lines when there is no text), and
            // above the actions row.
            if (fileCards.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fileCards.forEach { path ->
                        FileCard(path, onOpen = { openFile(path) })
                    }
                }
            }
            if (m.status == "error" && !errorDismissed) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            }
                            // Dismiss (chat redesign 28/11/2026, fixed
                            // 28/11/2026): hides this card for this session
                            // — no retry, nothing deleted, the message
                            // itself is untouched.
                            IconButton(onClick = { onDismissError?.invoke() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss error", modifier = Modifier.size(20.dp))
                            }
                        }
                        if (errorExplanation != null) {
                            Text(
                                errorExplanation,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = { onRetryError?.invoke() ?: actions.regen(m) }, enabled = !generating) { Text("Retry") }
                            if (errorFix != null) {
                                OutlinedButton(onClick = { onErrorFix(errorFix) }) { Text(errorFix.label()) }
                            }
                        }
                        // Nút Retry đã BỚT (dữ liệu đã mất, không khôi phục
                        // được): tách riêng, ghi rõ — không phải Retry mặc định.
                        if (reducedRetryLabel != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { onReducedRetry?.invoke() }, enabled = !generating) { Text(reducedRetryLabel) }
                            }
                        }
                        // Retry một lần mà vẫn lỗi thì mở cuộc mới (lịch sử
                        // cũ có thể đã hỏng tới mức không sửa được nữa).
                        if (showNewChat && onNewChat != null) {
                            OutlinedButton(onClick = onNewChat, enabled = !generating) { Text("Start a new chat") }
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

/**
 * One card for a file an agent created in this turn (chat redesign
 * 28/11/2026): a blue document tile, the file name and its type from the
 * extension, and a chevron. Tapping it opens the file with the app's
 * shared opener.
 */
@Composable
private fun FileCard(path: String, onOpen: () -> Unit) {
    val name = path.substringAfterLast('/')
    val ext = name.substringAfterLast('.', "").lowercase()
    val typeLabel = when (ext) {
        "md" -> "Markdown"
        "txt" -> "Text"
        "csv" -> "CSV"
        "json" -> "JSON"
        "" -> "File"
        else -> ext.uppercase()
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEAF2FF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = Color(0xFF2B7FFF),
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    typeLabel,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
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
