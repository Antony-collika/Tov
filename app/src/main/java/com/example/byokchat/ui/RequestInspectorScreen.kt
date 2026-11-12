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
import com.example.byokchat.ai.ClaudeThinkingMode
import com.example.byokchat.ai.MetadataParams
import com.example.byokchat.ai.Role
import com.example.byokchat.ai.ToolDef
import com.example.byokchat.ai.claudeThinkingModeFor
import com.example.byokchat.chat.AssembledMessage
import com.example.byokchat.chat.AssembledRequest
import com.example.byokchat.chat.SystemSource
import com.example.byokchat.chat.TokenEstimator
import com.example.byokchat.container
import com.example.byokchat.data.ProviderType
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

/** "Preview request": đúng thứ sẽ gửi cho AI ở lần gửi kế tiếp, kèm nhãn nguồn và token ước lượng. */
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
                title = { Text("Preview request") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    val a = assembled
                    if (a != null && ready != null) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(inspectorMarkdown(a, ready))) }) { Text("Copy") }
                    }
                },
            )
        },
    ) { padding ->
        val p = preview
        when {
            loading -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) { Text("Loading…") }
            p is PreviewResult.Unavailable -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text(
                    when (p.reason) {
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
                        PreviewUnavailable.TOOLS_FILE_ERROR ->
                            "The Tools card file could not be read, so there is nothing to send. " +
                                "Fix the file and try again."
                    }
                )
                if (fileErrors.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(fileErrors.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
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
                InspectorContent(assembled!!, ready!!, Modifier.weight(1f))
            }
        }
    }
}

/** Đợt provider 12/11/2026: dòng thinking của Claude cho Preview (màn hình + bản Markdown). */
private fun thinkingLine(metadata: MetadataParams, type: ProviderType, model: String): String? {
    if (type != ProviderType.CLAUDE) return null
    val mode = claudeThinkingModeFor(model)
    return when {
        !metadata.thinkingEnabled && mode == ClaudeThinkingMode.ADAPTIVE_ALWAYS_ON ->
            "Thinking: off in this app — but this model thinks by default and can't be turned off"
        !metadata.thinkingEnabled -> "Thinking: off"
        mode == ClaudeThinkingMode.MANUAL -> "Thinking: on (manual, budget ${metadata.thinkingBudget} tokens)"
        mode == ClaudeThinkingMode.ADAPTIVE_ALWAYS_ON ->
            "Thinking: on (adaptive — this model always thinks; depth set by \"How hard the model thinks\" above)"
        else -> "Thinking: on (adaptive — depth set by \"How hard the model thinks\" above)"
    }
}

/** Đợt provider 12/11/2026: dòng stop sequences — Responses API (OpenAI) không có param stop nên không gửi. */
private fun stopLine(metadata: MetadataParams, type: ProviderType): String =
    if (type == ProviderType.OPENAI) "Stop sequences: not sent — the Responses API doesn't support stop phrases"
    else "Stop sequences: " + if (metadata.stopSequences.isEmpty()) "none" else metadata.stopSequences.joinToString(", ")

@Composable
private fun InspectorContent(a: AssembledRequest, ready: PreviewResult.Ready, modifier: Modifier = Modifier) {
    val providerName = ready.providerName
    val model = ready.model
    val metadata = ready.metadata
    val type = ready.providerType
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
        item { SectionTitle("Metadata") }
        item {
            // Đợt 6: hiện đúng metadata lần gửi này dùng — cùng giá trị
            // ChatEngine gửi. Tool luôn được gửi đủ, với mọi hãng.
            // Đợt provider 12/11/2026: thêm dòng thinking của Claude, ghi chú
            // stop của Responses và định dạng request của OpenAI.
            val effortValue = metadata.effortValueFor(type)
            val toolsLine = "Tools: ${a.toolDefs.size} tool(s) offered."
            val thinking = thinkingLine(metadata, type, model)?.let { "\n$it" } ?: ""
            val formatNote = if (type == ProviderType.OPENAI) {
                "\nRequest format: Responses API — reasoning, messages and tool calls are sent as items in order"
            } else ""
            Text(
                "Model: $model\n" +
                    "Reasoning effort: ${metadata.effectiveEffort(type).name.lowercase()}" +
                    (if (effortValue != null) " (${metadata.effortFieldName(type)}: $effortValue)" else " (not sent — the provider uses its default)") + "\n" +
                    "Max output tokens: ${ready.maxOutputTokens}\n" +
                    "Stream: on (always on — this app only streams)\n" +
                    stopLine(metadata, type) + thinking + formatNote + "\n" +
                    toolsLine,
                style = MaterialTheme.typography.bodySmall,
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
            // Đợt provider 12/11/2026: OpenAI gửi dạng items của Responses API,
            // không phải danh sách message thường — ghi rõ để user không nhầm.
            if (type == ProviderType.OPENAI) {
                Text(
                    "Sent as Responses API items: reasoning, messages and tool calls are replayed in order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            // Đợt provider 12/11/2026: suy luận của lượt này cũng là một phần
            // của request gửi đi (hãng nào cũng gửi lại) nên Preview hiện nó.
            // Không có chữ đọc được (Claude display omitted) thì ghi rõ là
            // khối thinking vẫn được gửi lại, chỉ không có bản tóm tắt.
            if (!msg.reasoningText.isNullOrBlank()) {
                Text("Suy luận", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                ExpandableText(msg.reasoningText)
            } else if (msg.reasoningReplay != null) {
                Text(
                    "Thinking was used in this turn — sent back to the provider, no readable summary.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
private fun inspectorMarkdown(a: AssembledRequest, ready: PreviewResult.Ready): String = buildString {
    val providerName = ready.providerName
    val model = ready.model
    val metadata = ready.metadata
    val type = ready.providerType
    appendLine("# Preview request")
    appendLine("Provider: $providerName · Model: $model")
    appendLine("Estimated tokens: ${a.estimatedTokens} (history ${a.historyTokens})")
    appendLine()
    appendLine("## Metadata")
    val effortValue = metadata.effortValueFor(type)
    appendLine("Model: $model")
    appendLine("Reasoning effort: ${metadata.effectiveEffort(type).name.lowercase()}" + (if (effortValue != null) " (${metadata.effortFieldName(type)}: $effortValue)" else " (not sent — the provider uses its default)"))
    appendLine("Max output tokens: ${ready.maxOutputTokens}")
    appendLine("Stream: on (always on)")
    appendLine(stopLine(metadata, type))
    thinkingLine(metadata, type, model)?.let { appendLine(it) }
    if (type == ProviderType.OPENAI) appendLine("Request format: Responses API — reasoning, messages and tool calls are sent as items in order")
    appendLine("Tools: ${a.toolDefs.size} tool(s) offered")
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
        // Đợt provider 12/11/2026: suy luận của lượt này (như trên màn hình).
        if (!m.message.reasoningText.isNullOrBlank()) {
            appendLine("Suy luận:")
            appendLine(m.message.reasoningText)
        } else if (m.message.reasoningReplay != null) {
            appendLine("(Thinking was used in this turn — sent back to the provider, no readable summary.)")
        }
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
