package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.data.CardKind
import com.example.byokchat.data.HistoryPolicy
import com.example.byokchat.data.RequestCard
import com.example.byokchat.data.ToolsSource
import kotlinx.coroutines.launch

/**
 * Màn "Request Control Panel" (Đợt C): danh sách thẻ từ trên xuống — thứ tự
 * thẻ là thứ tự gửi. Bố cục giống mẫu FPilot: nền trắng, thẻ ngăn cách bằng
 * đường kẻ mỏng, mỗi thẻ có icon riêng. Preview / Request log / Reset nằm
 * trong mục Advanced ở dưới cùng (Preview mở thẳng Inspector của Đợt B).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestPanelScreen(
    conversationId: String?,
    onBack: () -> Unit,
    onEditCard: (String) -> Unit,
    onOpenTools: () -> Unit,
    onOpenInspector: (() -> Unit)?,
    /** Đợt 7: nút Request log dời từ Settings sang đây, vào mục Advanced. */
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
    // Mục Advanced ở dưới cùng (mặc định thu gọn): chứa Preview, Request
    // log, Reset — dời từ top bar xuống đây cho đầu màn hình thoáng như mẫu.
    var advancedExpanded by remember { mutableStateOf(false) }
    // Menu "Add card" (theo FPilot): mở ra để chọn loại thẻ muốn thêm.
    var showAddMenu by remember { mutableStateOf(false) }
    // System/Time/Tools mỗi loại tối đa 1 thẻ — đã có thì không cho thêm
    // nữa (menu ẩn mục đó đi); Free thì thêm bao nhiêu cũng được.
    val hasSystem = layout.cards.any { it.kind == CardKind.SYSTEM }
    val hasTime = layout.cards.any { it.kind == CardKind.TIME }
    val hasTools = layout.cards.any { it.kind == CardKind.TOOLS }
    // Xóa thẻ chỉ còn một đường duy nhất: vuốt sang trái để xóa, xóa ngay
    // + snackbar Undo. Không còn nút thùng rác, nên không còn hộp xác nhận
    // xóa riêng nữa.
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
            // Top bar chỉ còn nút back + tiêu đề, như mẫu FPilot (chỉ có ⋮).
            // Preview / Request log / Reset dời hết xuống mục Advanced ở
            // dưới cùng. Lưu ý: Preview chỉ có khi mở panel từ một cuộc
            // chat (onOpenInspector != null) — mở từ Settings thì không có
            // cuộc nào để xem trước, nên nút Preview không hiện.
            TopAppBar(
                title = { Text("Request Control Panel") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        // Giống mẫu FPilot: không còn đoạn giới thiệu dài ở đầu màn hình.
        // Nội dung đó đã có sẵn ở màn sửa thẻ (VARS_HELP trong
        // CardEditScreen) và ở phần giúp đỡ của thẻ Time, nên bỏ ở đây
        // cho màn hình thoáng, thẻ là nội dung chính ngay từ đầu.
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                val row: @Composable () -> Unit = {
                    CardRow(
                        card = card,
                        isFirst = index == 0,
                        isLast = index == layout.cards.lastIndex,
                        summary = cardSummary(card, metadata, model, currentProvider),
                        onToggle = { store.updateCard(card.id) { it.copy(enabled = !it.enabled) } },
                        onMoveUp = { store.moveCard(card.id, up = true) },
                        onMoveDown = { store.moveCard(card.id, up = false) },
                        onEdit = { onEditCard(card.id) },
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
                // Ngăn cách các thẻ bằng đường kẻ mỏng, như mẫu FPilot.
                HorizontalDivider()
            }
            item {
                Spacer(Modifier.height(8.dp))
                // "Add card" như FPilot: không phải chỉ thêm Free card.
                // Bấm vào mở menu chọn loại thẻ — System / Time / Tools
                // (chỉ khi chưa có) và Free card (chọn vai System/User ở màn
                // sửa). Nhờ vậy thẻ đã xóa thêm lại được từ đây, không cần
                // Reset toàn bộ panel.
                Box {
                    TextButton(onClick = { showAddMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add card")
                    }
                    DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                        if (!hasSystem) {
                            DropdownMenuItem(
                                text = { Text("System card") },
                                onClick = { showAddMenu = false; onEditCard(store.addCard(CardKind.SYSTEM)) },
                            )
                        }
                        if (!hasTime) {
                            DropdownMenuItem(
                                text = { Text("Time card") },
                                onClick = { showAddMenu = false; onEditCard(store.addCard(CardKind.TIME)) },
                            )
                        }
                        if (!hasTools) {
                            DropdownMenuItem(
                                text = { Text("Tools card") },
                                onClick = { showAddMenu = false; onEditCard(store.addCard(CardKind.TOOLS)) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Free card (you choose the role)") },
                            onClick = { showAddMenu = false; onEditCard(store.addCard(CardKind.FREE)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Mục Advanced ở dưới cùng, mặc định thu gọn.
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { advancedExpanded = !advancedExpanded }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Advanced",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (advancedExpanded) "Collapse advanced" else "Expand advanced",
                    )
                }
                if (advancedExpanded) {
                    if (onOpenInspector != null) {
                        AdvancedRow(label = "Preview", onClick = onOpenInspector)
                    }
                    if (onOpenRequestLog != null) {
                        AdvancedRow(label = "Request log", onClick = onOpenRequestLog)
                    }
                    AdvancedRow(label = "Reset", onClick = { confirmReset = true })
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
}

/** Một dòng trong mục Advanced: chữ thường, chạm vào chạy hành động. */
@Composable
private fun AdvancedRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
            "turn tags".takeIf { card.turnTags },
            "includes error turns".takeIf { card.includeErrorTurns },
        )
        if (extras.isEmpty()) base else base + " · " + extras.joinToString(" · ")
    }
    CardKind.LAST_TURN -> "Your latest message and the tool round in progress. Always sent."
    CardKind.TOOLS -> when (card.toolsSource) {
        ToolsSource.TEXT -> {
            val first = card.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80)
            if (first.isNullOrBlank()) "Text input (empty — no tools)."
            else "Text input: $first"
        }
        ToolsSource.FILE -> {
            val files = card.toolFiles()
            if (files.isEmpty()) "No file selected — the send will be blocked. Tap Edit to choose tools.md files."
            else "Tools from: " + files.joinToString(", ") { fileName(it) }
        }
    }
}

/** Chỉ lấy tên file từ đường dẫn trong workspace ("Tov/prompts/system.md" -> "system.md"). */
private fun fileName(path: String): String = path.substringAfterLast('/').ifEmpty { path }

/**
 * Một thẻ trong Panel, bố cục giống mẫu FPilot (ảnh IMG_20261110_235313):
 * nền trắng, các thẻ ngăn cách bằng đường kẻ mỏng (HorizontalDivider đặt ở
 * chỗ gọi CardRow). Hàng trên: icon riêng của từng loại thẻ · tên + tóm tắt
 * (tối đa 2 dòng, cắt bằng "…"), chạm vào mở màn sửa; vùng chữ chỉ chiếm
 * phần trái, không lấn sang nút sửa bên phải. Hàng nút (trừ Metadata và
 * Last turn): lên/xuống · switch nhỏ · … · sửa (bút chì). Không còn nút
 * thùng rác — xóa bằng cách vuốt sang trái (SwipeToDismissBox ở chỗ gọi).
 * Không còn tay cầm kéo — đã có nút lên/xuống để di chuyển. Không còn chữ
 * "Always on" — như mẫu, History chỉ có lên/xuống + sửa, Last turn không
 * có hàng nút nào.
 *
 * Theo từng loại thẻ (như mẫu):
 * - Metadata: không có hàng nút; nút sửa nằm ở hàng trên, bên phải.
 * - History: hàng nút chỉ có lên/xuống + sửa, không có switch.
 * - Last turn: không có hàng nút (luôn gửi, luôn ở cuối).
 * - Còn lại (System/Time/Tools/Free): lên/xuống + switch nhỏ + sửa.
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
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        // Hàng trên: icon + tên/tóm tắt. Metadata có nút sửa ở đây.
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                cardIcon(card.kind),
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Cột chữ: chạm vào mở màn sửa. Vùng chạm chỉ là cột chữ (phần
            // trái), không lấn sang nút sửa bên phải.
            Column(
                Modifier.weight(1f).padding(start = 16.dp).clickable(onClick = onEdit),
            ) {
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
            if (card.kind == CardKind.METADATA) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
            }
        }
        // Hàng nút — Metadata và Last turn không có hàng này (như mẫu).
        // Thụt vào 44dp (rộng bằng icon + khoảng cách) để lên/xuống thẳng
        // hàng với tên thẻ, như mẫu.
        if (card.kind != CardKind.METADATA && card.kind != CardKind.LAST_TURN) {
            Row(
                Modifier.padding(start = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(enabled = !isFirst, onClick = onMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(enabled = !isLast, onClick = onMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
                // History không có switch (như mẫu); các thẻ còn lại dùng
                // switch nhỏ (SmallSwitch) cho cân với các icon.
                if (card.kind != CardKind.HISTORY) {
                    // Switch thu NHỎ PHẦN HÌNH (scale 0.8) cho cân với các icon,
                    // nhưng đặt trong hộp 48dp và hộp cũng chạm được để bật/tắt,
                    // nên vùng chạm vẫn đủ 48dp (xem SmallSwitch).
                    SmallSwitch(checked = card.enabled, onToggle = onToggle)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
            }
        }
    }
}

/** Icon riêng của từng loại thẻ, như mẫu FPilot. */
private fun cardIcon(kind: CardKind): ImageVector = when (kind) {
    CardKind.METADATA -> Icons.Default.Layers
    CardKind.SYSTEM -> Icons.Default.Settings
    CardKind.TIME -> Icons.Default.Schedule
    CardKind.TOOLS -> Icons.Default.Build
    CardKind.HISTORY -> Icons.Default.History
    CardKind.LAST_TURN -> Icons.Default.PlayArrow
    CardKind.FREE -> Icons.Default.Description
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
