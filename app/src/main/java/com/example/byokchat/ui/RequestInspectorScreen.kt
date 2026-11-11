package com.example.byokchat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.byokchat.ai.Role
import com.example.byokchat.ai.ToolDef
import com.example.byokchat.chat.AssembledMessage
import com.example.byokchat.chat.AssembledRequest
import com.example.byokchat.chat.SystemSource
import com.example.byokchat.chat.TokenEstimator
import com.example.byokchat.container
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Văn bản dài thì thu gọn, bấm để đọc hết (dùng chung cho Inspector và Request log). */
@Composable
internal fun ExpandableText(text: String, previewChars: Int = 500) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded || text.length <= previewChars) text else text.take(previewChars) + "…"
    Column {
        Text(shown, style = MaterialTheme.typography.bodySmall)
        if (text.length > previewChars) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show all (${text.length} characters)")
            }
        }
    }
}

/** "What the AI will see": đúng thứ sẽ gửi cho AI ở lần gửi kế tiếp, kèm nhãn nguồn và token ước lượng. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestInspectorScreen(conversationId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val clipboard = LocalClipboardManager.current
    val vm: ChatViewModel = viewModel(key = conversationId, factory = viewModelFactory {
        initializer { ChatViewModel(container, conversationId) }
    })
    var preview by remember { mutableStateOf<PreviewResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    // Bố cục không hợp lệ thì Inspector hiện request của bố cục mặc định + báo lỗi.
    val layoutError by container.layoutStore.layoutError.collectAsState()
    val fileErrors by container.layoutStore.fileErrors.collectAsState()
    LaunchedEffect(conversationId) {
        preview = vm.previewAssembled()
        loading = false
    }
    // Đọc DB trực tiếp (xem ChatViewModel.previewAssembled) nên một lần gọi là đủ.
    val ready = preview as? PreviewResult.Ready
    val assembled = ready?.request

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What the AI will see") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    val a = assembled
                    if (a != null && ready != null) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(inspectorMarkdown(a, ready.providerName, ready.model))) }) { Text("Copy") }
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) { Text("Loading…") }
            preview is PreviewResult.Unavailable -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text(
                    when (preview.reason) {
                        PreviewUnavailable.CONVERSATION_NOT_FOUND ->
                            "This chat no longer exists, so there is nothing to preview."
                        PreviewUnavailable.NO_PROVIDER ->
                            "No AI provider is selected yet, so there is nothing to send. " +
                                "Go to Settings → AI providers to add one, then tap the model name in the message box to choose a model."
                        PreviewUnavailable.PROVIDER_UNAVAILABLE ->
                            "The selected provider is no longer available (it may have been deleted). " +
                                "Tap the model name in the message box to choose another one."
                        PreviewUnavailable.NO_MODEL ->
                            "No model is selected yet, so there is nothing to send. " +
                                "Tap the model name in the message box to choose one."
                    }
                )
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                if (layoutError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "Request panel problem: $layoutError Showing the default layout instead.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                if (fileErrors.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            fileErrors.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                InspectorContent(assembled!!, ready!!.providerName, ready.model, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InspectorContent(a: AssembledRequest, providerName: String, model: String, modifier: Modifier = Modifier) {
    // Nhãn lượt lấy từ số lượt ổn định (Đợt 4) mà RequestAssembler tính từ
    // turnNo đã lưu, không đếm lại theo vị trí: tin user thứ k lấy số thứ k.
    // Lượt bị cắt (đứng đầu danh sách) giữ số của nó.
    var userIndex = 0
    var current = 0
    val numbered = a.messages.map { m ->
        if (m.message.role == Role.USER) {
            current = a.turnNumbers.getOrNull(userIndex)?.takeIf { it > 0 } ?: (userIndex + 1)
            userIndex++
        }
        current to m
    }
    val sentGroups = numbered.filter { it.second.sent }.groupBy({ it.first }, { it.second })
    val droppedGroups = numbered.filter { !it.second.sent }.groupBy({ it.first }, { it.second })

    LazyColumn(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Text(
                "Provider: $providerName · Model: $model",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Estimated tokens: ${formatTokens(a.estimatedTokens)} " +
                    "(system ${formatTokens(TokenEstimator.text(a.system))} · " +
                    "history ${formatTokens(a.historyTokens)})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
        }
        item { SectionTitle("System") }
        if (a.systemParts.isEmpty()) {
            item { Text("No system prompt.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(a.systemParts) { part ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (part.source == SystemSource.USER_PROMPT) "You wrote" else "Added by the app",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        ExpandableText(part.text)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Messages")
        }
        if (sentGroups.isEmpty()) {
            item { Text("No messages will be sent.", style = MaterialTheme.typography.bodySmall) }
        } else {
            sentGroups.forEach { (t, msgs) ->
                item { Text("Turn T$t", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(msgs) { m -> MessageCard(t, m) }
            }
        }
        if (droppedGroups.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                SectionTitle("Not sent (over budget)")
                Text(
                    "The ${a.droppedTurnIds.size} oldest turn(s) are left out so the chat fits the limit. " +
                        "Turn IDs: ${a.droppedTurnIds.joinToString { it.take(8) }}.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            droppedGroups.forEach { (t, msgs) ->
                item { Text("Turn T$t (not sent)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(msgs) { m -> MessageCard(t, m) }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SectionTitle("Tools (${a.toolDefs.size})")
        }
        if (a.toolDefs.isEmpty()) {
            item { Text("No tools will be offered.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(a.toolDefs) { t -> ToolCard(t) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MessageCard(turn: Int, m: AssembledMessage) {
    val msg = m.message
    val label = when (msg.role) {
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant" + if (msg.toolCalls.isNotEmpty()) " · calls ${msg.toolCalls.joinToString { it.name }}" else ""
        Role.TOOL -> "tool" + (msg.toolName?.let { " · $it" } ?: "") + if (msg.isError) " · error" else ""
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "T$turn · $label" + if (!m.sent) " · not sent (${m.dropReason ?: "dropped"})" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (msg.toolCalls.isNotEmpty() && msg.text.isBlank()) {
                msg.toolCalls.forEach { tc -> Text("${tc.name}(${tc.argsJson})", style = MaterialTheme.typography.bodySmall) }
            } else {
                ExpandableText(msg.text)
            }
        }
    }
}

@Composable
private fun ToolCard(t: ToolDef) {
    var showParams by remember { mutableStateOf(false) }
    val pretty = remember(t.schema) {
        Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), t.schema)
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(t.name, style = MaterialTheme.typography.titleSmall)
            Text(t.description, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showParams = !showParams }) {
                Text(if (showParams) "Hide parameters" else "Show parameters")
            }
            if (showParams) ExpandableText(pretty, previewChars = 1000)
        }
    }
}

/** Bản Markdown để Copy: cùng nội dung với màn hình, để đối chiếu với Request log. */
private fun inspectorMarkdown(a: AssembledRequest, providerName: String, model: String): String = buildString {
    appendLine("# What the AI will see")
    appendLine("Provider: $providerName · Model: $model")
    appendLine("Estimated tokens: ${a.estimatedTokens} (history ${a.historyTokens})")
    appendLine()
    appendLine("## System")
    if (a.systemParts.isEmpty()) appendLine("(no system prompt)") else {
        a.systemParts.forEach { part ->
            appendLine("### " + if (part.source == SystemSource.USER_PROMPT) "You wrote" else "Added by the app")
            appendLine(part.text)
        }
    }
    appendLine()
    appendLine("## Messages")
    // Nhãn lượt lấy từ số lượt ổn định (Đợt 4), cùng cách đánh số với màn hình.
    var userIndex = 0
    var current = 0
    a.messages.forEach { m ->
        if (m.message.role == Role.USER) {
            current = a.turnNumbers.getOrNull(userIndex)?.takeIf { it > 0 } ?: (userIndex + 1)
            userIndex++
        }
        val turn = current
        val label = when (m.message.role) {
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.TOOL -> "tool" + (m.message.toolName?.let { " ($it)" } ?: "")
        }
        appendLine("### T$turn · $label" + if (!m.sent) " · not sent (${m.dropReason ?: "dropped"})" else "")
        appendLine(m.message.text)
    }
    if (a.droppedTurnIds.isNotEmpty()) {
        appendLine()
        appendLine("## Not sent (over budget)")
        appendLine(a.droppedTurnIds.joinToString())
    }
    appendLine()
    appendLine("## Tools")
    a.toolDefs.forEach { t ->
        appendLine("### ${t.name}")
        appendLine(t.description)
        appendLine("Parameters: ${t.schema}")
    }
}
