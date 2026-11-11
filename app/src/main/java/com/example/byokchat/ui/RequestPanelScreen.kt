package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.data.CardKind
import com.example.byokchat.data.HistoryPolicy
import com.example.byokchat.data.RequestCard
import kotlinx.coroutines.launch

/**
 * Màn "Request panel" (Đợt C): danh sách thẻ từ trên xuống — thứ tự thẻ là
 * thứ tự gửi. Mỗi thẻ có công tắc bật/tắt, nút đưa lên/xuống, nút sửa.
 * Nút Preview mở thẳng Inspector của Đợt B.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestPanelScreen(
    conversationId: String?,
    onBack: () -> Unit,
    onEditCard: (String) -> Unit,
    onOpenTools: () -> Unit,
    onOpenInspector: (() -> Unit)?,
    /** Đợt 7: nút Request log dời từ Settings sang đây, cạnh nút Preview. */
    onOpenRequestLog: (() -> Unit)?,
) {
    val context = LocalContext.current
    val container = context.container
    val store = container.layoutStore
    val layout by store.storedLayout.collectAsState()
    val layoutError by store.layoutError.collectAsState()
    val fileErrors by store.fileErrors.collectAsState()
    // Đợt 6: tóm tắt thẻ Metadata cần giá trị metadata + model + max tokens
    // của provider đang dùng.
    val metadata by container.settings.metadata.collectAsState()
    val model by container.settings.currentModel.collectAsState()
    val currentProvider = container.settings.currentModelProvider()
    var confirmReset by remember { mutableStateOf(false) }
    // Đợt 8: xóa thẻ — nút thùng rác hỏi xác nhận trước, vuốt xóa thì xóa
    // ngay + snackbar Undo (hành vi xóa trước đây xóa ngay, không hỏi,
    // không hoàn tác). Cả hai đường đều qua deleteWithUndo.
    var confirmDelete by remember { mutableStateOf<RequestCard?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun deleteWithUndo(card: RequestCard) {
        val index = layout.cards.indexOfFirst { it.id == card.id }
        if (index < 0) return
        store.deleteCard(card.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Card deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) store.insertCard(card, index)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Request panel") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (onOpenInspector != null) {
                        TextButton(onClick = onOpenInspector) { Text("Preview") }
                    }
                    if (onOpenRequestLog != null) {
                        TextButton(onClick = onOpenRequestLog) { Text("Request log") }
                    }
                    TextButton(onClick = { confirmReset = true }) { Text("Reset") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Text(
                    "Cards are sent top to bottom. The layout applies to every chat. " +
                        "You can use {date}, {time}, {model} and {provider} in card text — " +
                        "they are replaced when sending. Note: {time} changes every minute, " +
                        "so the AI's cache is not reused while it is in the prompt. " +
                        "The Time card has the same effect when it is on: its time line changes every minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (layoutError != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(
                            "Layout problem: $layoutError Sending with the default layout until you fix it.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (fileErrors.isNotEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(
                            fileErrors.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            items(layout.cards, key = { it.id }) { card ->
                val index = layout.cards.indexOf(card)
                val row = @Composable {
                    CardRow(
                        card = card,
                        isFirst = index == 0,
                        isLast = index == layout.cards.lastIndex,
                        summary = cardSummary(card, metadata, model, currentProvider),
                        onToggle = { store.updateCard(card.id) { it.copy(enabled = !it.enabled) } },
                        onMoveUp = { store.moveCard(card.id, up = true) },
                        onMoveDown = { store.moveCard(card.id, up = false) },
                        onEdit = { onEditCard(card.id) },
                        onDelete = { confirmDelete = card },
                        // Metadata phải luôn đầu tiên (luật kiểm tra): khóa nút di chuyển của nó.
                        lockPosition = card.kind == CardKind.METADATA,
                    )
                }
                if (card.isFixed()) {
                    // Thẻ cố định không xóa được nên không vuốt xóa.
                    Box(Modifier.animateItem()) { row() }
                } else {
                    // Đợt 8: vuốt sang trái để xóa (vuốt sang phải không làm gì).
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                deleteWithUndo(card)
                                true
                            } else {
                                false
                            }
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        modifier = Modifier.animateItem(),
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                                Box(
                                    Modifier.fillMaxSize().padding(vertical = 4.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Box(
                                            Modifier.fillMaxSize().padding(end = 20.dp),
                                            contentAlignment = Alignment.CenterEnd,
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    ) { row() }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onEditCard(store.addFreeCard()) }) {
                    Text("Add free card")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset the request panel?",
            message = "The card list returns to the default (Metadata, System, Time, Tools, History, Last turn). Your system prompt text is kept.",
            confirmLabel = "Reset",
            onConfirm = { confirmReset = false; store.reset() },
            onDismiss = { confirmReset = false },
        )
    }

    val cardToDelete = confirmDelete
    if (cardToDelete != null) {
        ConfirmDialog(
            title = "Delete this card?",
            message = "The ${cardToDelete.displayName()} card will be removed from the panel.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = null; deleteWithUndo(cardToDelete) },
            onDismiss = { confirmDelete = null },
        )
    }
}

/** Một dòng tóm tắt cho từng thẻ. */
private fun cardSummary(
    card: RequestCard,
    metadata: com.example.byokchat.ai.MetadataParams,
    model: String?,
    provider: com.example.byokchat.data.ProviderConfig?,
): String = when (card.kind) {
    CardKind.METADATA -> {
        // vd "gpt-4o · effort medium · max 8192 · stream on"; ghi thêm khi
        // tool bị tắt vì reasoning đang bật với OpenAI.
        val base = metadata.summary(model.orEmpty(), provider?.maxOutputTokens ?: 8192)
        val note = when {
            provider != null && metadata.toolsSuppressedByReasoning(provider.type) ->
                " · tools off (reasoning is on)"
            metadata.toolChoice == com.example.byokchat.ai.ToolChoiceMode.NONE -> " · tools off for this request"
            else -> ""
        }
        base + note
    }
    CardKind.SYSTEM -> card.file?.let { "File: ${fileName(it)}" }
        ?: card.text.lineSequence().firstOrNull()?.take(80)?.ifBlank { "(no system prompt)" }
        ?: "(no system prompt)"
    CardKind.TIME -> {
        "Adds \"Current time: YYYY-MM-DD HH:MM\" at the end of the system prompt when on. " +
            "When off, no time is added — the model can still call the time.now tool."
    }
    CardKind.FREE -> (if (card.role.name == "USER") "User" else "System") + " · " +
        (card.file?.let { "File: ${fileName(it)}" }
            ?: card.text.lineSequence().firstOrNull()?.take(80)?.ifBlank { "(empty)" } ?: "(empty)")
    CardKind.HISTORY -> {
        val base = when (card.policy) {
            HistoryPolicy.BUDGET -> "Cut the oldest turns to fit the token budget"
            HistoryPolicy.LAST_N -> "Keep only the last ${card.lastTurns.coerceAtLeast(1)} turns"
            HistoryPolicy.NO_CUT -> "Send all history, no cutting"
        }
        val extras = listOfNotNull(
            "shortens old tool results".takeIf { card.compactToolResults },
            "turn tags [Tn]".takeIf { card.turnTags },
            "includes error turns".takeIf { card.includeErrorTurns },
        )
        if (extras.isEmpty()) base else base + " · " + extras.joinToString(" · ")
    }
    CardKind.LAST_TURN -> "Your latest message and the tool round in progress. Always sent."
    CardKind.TOOLS -> {
        val files = card.toolFiles()
        if (files.isEmpty()) "All tools, with their original names. Tap Edit to choose tools.md files."
        else "Tools from: " + files.joinToString(", ") { fileName(it) }
    }
}

/** Chỉ lấy tên file từ đường dẫn trong workspace ("Tov/prompts/system.md" -> "system.md"). */
private fun fileName(path: String): String = path.substringAfterLast('/').ifEmpty { path }

/**
 * Đợt 8: một thẻ trong Panel, bố cục kiểu FPilot — tên + một dòng tóm tắt
 * (tối đa 2 dòng, cắt bằng "…"), rồi MỘT hàng nút duy nhất, trái sang phải:
 * tay cầm kéo (nhấn giữ rồi kéo lên/xuống để di chuyển) · lên/xuống ·
 * switch · … · sửa (bút chì) · xóa (thùng rác, chỉ thẻ không cố định).
 * Chạm vào tên/tóm tắt mở màn sửa. Nút lên/xuống vẫn giữ làm dự phòng
 * khi kéo khó dùng. Không thêm trang trí: thoáng, tiết chế.
 */
@Composable
private fun CardRow(
    card: RequestCard,
    isFirst: Boolean,
    isLast: Boolean,
    summary: String,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** True = khóa vị trí thẻ (Metadata luôn đầu tiên): ẩn tay cầm kéo và nút lên/xuống. */
    lockPosition: Boolean = false,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Cột chữ: chạm vào mở màn sửa. Tóm tắt tối đa 2 dòng, cắt bằng "…".
            Column(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
                Text(
                    card.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!lockPosition) {
                    DragHandle(onMoveUp = onMoveUp, onMoveDown = onMoveDown)
                    IconButton(enabled = !isFirst, onClick = onMoveUp) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(enabled = !isLast, onClick = onMoveDown) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                    }
                }
                if (card.kind == CardKind.HISTORY || card.kind == CardKind.LAST_TURN) {
                    // Thẻ cố định: không có switch, chỉ ghi "Always on".
                    Text(
                        "Always on",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                } else if (card.kind != CardKind.METADATA) {
                    // Switch thu NHỎ PHẦN HÌNH (scale 0.8) cho cân với các icon,
                    // nhưng đặt trong hộp 48dp và hộp cũng chạm được để bật/tắt,
                    // nên vùng chạm vẫn đủ 48dp (xem SmallSwitch).
                    SmallSwitch(checked = card.enabled, onToggle = onToggle)
                }
                // Metadata không có switch cũng không có chữ "Always on": nó
                // luôn ở đầu và luôn bật, như FPilot.
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
                if (!card.isFixed()) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }
}

/**
 * Switch với hình thu nhỏ (scale 0.8) nhưng vùng chạm giữ 48dp: switch nằm
 * giữa một hộp 48x48dp, chạm vào hộp (phần đệm quanh switch) cũng bật/tắt.
 * Chạm đúng vào switch thì chỉ switch nhận (clickable trong cùng thắng),
 * chạm vào đệm thì hộp nhận — mỗi lần chạm chỉ bật/tắt một lần.
 */
@Composable
private fun SmallSwitch(checked: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.scale(0.8f),
        )
    }
}

/**
 * Tay cầm kéo để di chuyển thẻ. Kích hoạt: NHẤN GIỮ tay cầm rồi kéo lên
 * hoặc xuống (không phải kéo ngay — kéo ngay vẫn là cuộn danh sách).
 * Mỗi 64dp kéo thì thẻ dịch một nấc (dùng đúng moveCard của nút lên/xuống,
 * nên luật kiểm tra bố cục áp dụng y như cũ). Thả tay là xong; không cần
 * thao tác gì thêm. Nút lên/xuống vẫn ở đó cho trường hợp kéo khó dùng.
 */
@Composable
private fun DragHandle(onMoveUp: () -> Unit, onMoveDown: () -> Unit) {
    val stepPx = with(LocalDensity.current) { 64.dp.toPx() }
    Box(
        Modifier.size(48.dp).pointerInput(Unit) {
            // Biến cục bộ của khối pointerInput (không phải state): chỉ dùng
            // trong lúc kéo, không cần recompose.
            var dragged = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = { dragged = 0f },
                onDrag = { _, dragAmount ->
                    dragged += dragAmount.y
                    while (dragged >= stepPx) {
                        onMoveDown()
                        dragged -= stepPx
                    }
                    while (dragged <= -stepPx) {
                        onMoveUp()
                        dragged += stepPx
                    }
                },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Drag to reorder: long-press, then drag up or down",
        )
    }
}
