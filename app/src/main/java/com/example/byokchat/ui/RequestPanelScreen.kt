package com.example.byokchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.data.CardKind
import com.example.byokchat.data.HistoryPolicy
import com.example.byokchat.data.RequestCard

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

    Scaffold(
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
                CardRow(
                    card = card,
                    isFirst = index == 0,
                    isLast = index == layout.cards.lastIndex,
                    summary = cardSummary(card, metadata, model, currentProvider),
                    onToggle = { store.updateCard(card.id) { it.copy(enabled = !it.enabled) } },
                    onMoveUp = { store.moveCard(card.id, up = true) },
                    onMoveDown = { store.moveCard(card.id, up = false) },
                    onEdit = { onEditCard(card.id) },
                    onDelete = { store.deleteCard(card.id) },
                    // Metadata phải luôn đầu tiên (luật kiểm tra): khóa nút di chuyển của nó.
                    lockPosition = card.kind == CardKind.METADATA,
                )
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
    CardKind.SYSTEM -> card.file?.let { "From file: $it" }
        ?: card.text.lineSequence().firstOrNull()?.take(80)?.ifBlank { "(no system prompt)" }
        ?: "(no system prompt)"
    CardKind.TIME -> {
        "Adds \"Current time: YYYY-MM-DD HH:MM\" at the end of the system prompt when on. " +
            "When off, no time is added — the model can still call the time.now tool."
    }
    CardKind.FREE -> (if (card.role.name == "USER") "User" else "System") + " · " +
        (card.file?.let { "From file: $it" }
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
        else "Tools from: " + files.joinToString(", ")
    }
}

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
    /** True = khóa vị trí thẻ (Metadata luôn đầu tiên): ẩn nút lên/xuống. */
    lockPosition: Boolean = false,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.displayName(), style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (card.isFixed()) {
                    Text("Always on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Switch(checked = card.enabled, onCheckedChange = { onToggle() })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!lockPosition) {
                    IconButton(enabled = !isFirst, onClick = onMoveUp) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(enabled = !isLast, onClick = onMoveDown) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                    }
                }
                TextButton(onClick = onEdit) { Text("Edit") }
                if (!card.isFixed()) {
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}
