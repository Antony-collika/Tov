package com.example.byokchat.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.ai.ClaudeThinkingMode
import com.example.byokchat.ai.MetadataParams
import com.example.byokchat.ai.claudeThinkingModeFor
import com.example.byokchat.ai.ReasoningEffort
import com.example.byokchat.chat.THINKING_SEAL_CARD_BLOCK_MESSAGE
import com.example.byokchat.data.CardKind
import com.example.byokchat.data.RequestCard
import com.example.byokchat.data.FreeRole
import com.example.byokchat.data.HistoryPolicy
import com.example.byokchat.data.ProviderType
import com.example.byokchat.data.ToolsSource
import com.example.byokchat.tools.ToolsMdParseException
import com.example.byokchat.tools.ToolsMdParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VARS_HELP = "You can use {date}, {time}, {model} and {provider} — they are replaced when sending. " +
    "Note: {time} changes every minute, so the AI's cache is not reused while it is in the prompt."

/**
 * Đợt 8: nguồn nội dung của thẻ System/Free — gõ trực tiếp hay đọc file
 * ngoài (như SourceRadio của FPilot). Chỉ là trạng thái nháp của màn sửa:
 * lúc Save, TEXT ghi file = null (bỏ liên kết file), FILE ghi đường dẫn
 * vào trường file của thẻ (có file = External file, không file = Text input)
 * — mô hình thẻ lưu trữ không đổi, cơ chế đọc lại file mỗi lần gửi giữ nguyên.
 */
private enum class CardSource { TEXT, FILE }

/** Sửa một thẻ trong Request panel (Đợt C). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditScreen(cardId: String, onBack: () -> Unit, onOpenMessages: (() -> Unit)? = null, conversationId: String? = null, onOpenConversation: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val container = context.container
    val store = container.layoutStore
    val layout by store.storedLayout.collectAsState()
    val card = layout.card(cardId)

    var text by remember(cardId) { mutableStateOf(card?.text.orEmpty()) }
    var label by remember(cardId) { mutableStateOf(card?.label.orEmpty()) }
    var role by remember(cardId) { mutableStateOf(card?.role ?: FreeRole.SYSTEM) }
    var policy by remember(cardId) { mutableStateOf(card?.policy ?: HistoryPolicy.BUDGET) }
    var lastTurns by remember(cardId) { mutableStateOf(card?.lastTurns?.toString() ?: "10") }
    var compact by remember(cardId) { mutableStateOf(card?.compactToolResults ?: false) }
    var turnTags by remember(cardId) { mutableStateOf(card?.turnTags ?: false) }
    var includeErrors by remember(cardId) { mutableStateOf(card?.includeErrorTurns ?: false) }
    // Đợt D: file mà thẻ trỏ tới (trống = không trỏ file).
    var file by remember(cardId) { mutableStateOf(card?.file.orEmpty()) }
    var fileError by remember(cardId) { mutableStateOf<String?>(null) }
    // Đợt provider 12/11/2026 (KHÓA CỨNG): thông báo chặn khi sửa thẻ trong
    // cuộc có thinking của Claude (hiện dưới nút Save), hoặc lỗi khác lúc Save.
    var lockError by remember(cardId) { mutableStateOf<String?>(null) }
    // Đợt 8: nguồn nội dung của thẻ System/Free — khởi đầu theo file đã trỏ
    // (có file = External file, không file = Text input).
    var source by remember(cardId) {
        mutableStateOf(if (card?.file != null) CardSource.FILE else CardSource.TEXT)
    }
    // Đợt E: thẻ Tools giữ NHIỀU file tools.md (file cũ chuyển thành danh sách 1 file).
    var toolFiles by remember(cardId) { mutableStateOf(card?.toolFiles().orEmpty()) }
    // Sửa luật nguồn 11/11/2026: thẻ Tools có nguồn Text (gõ trực tiếp) hoặc
    // File (file .md đã chọn); mặc định File như thẻ cũ.
    var toolsSource by remember(cardId) { mutableStateOf(card?.toolsSource ?: ToolsSource.FILE) }
    var toolsPreview by remember(cardId) { mutableStateOf<String?>(null) }
    var toolsPreviewError by remember(cardId) { mutableStateOf<String?>(null) }
    // Đợt fix 11/11/2026 (A2): picker chọn file tools.md — như picker của
    // thẻ System, không gõ đường dẫn tay nữa.
    val pickToolFile = rememberCardFilePicker(
        onPicked = { path ->
            if (path !in toolFiles) toolFiles = toolFiles + path
            fileError = null
        },
        onPickError = { fileError = it },
    )
    // Đợt 6: thẻ Metadata — giá trị lưu toàn cục ở SettingsRepository (trừ
    // max tokens, theo từng provider). Chỉ hiện trường nào hãng đang dùng
    // hỗ trợ; trường hãng không có thì ẩn, không gửi bừa.
    val savedMetadata = container.settings.metadata.value
    val currentProvider = container.settings.currentModelProvider()
    val providerType = currentProvider?.type
    val currentModel = container.settings.currentModel.value
    var effort by remember(cardId) { mutableStateOf(savedMetadata.effort) }
    var stopText by remember(cardId) { mutableStateOf(savedMetadata.stopSequences.joinToString("\n")) }
    var keepSignature by remember(cardId) { mutableStateOf(savedMetadata.keepThoughtSignature) }
    // Đợt provider 12/11/2026: thinking của Claude (mặc định TẮT — câu hỏi mở
    // cho Duy, xem báo cáo cuối).
    var thinkingEnabled by remember(cardId) { mutableStateOf(savedMetadata.thinkingEnabled) }
    var thinkingBudgetText by remember(cardId) { mutableStateOf(savedMetadata.thinkingBudget.toString()) }
    var maxTokens by remember(cardId) { mutableStateOf((currentProvider?.maxOutputTokens ?: 8192).toString()) }
    val stopLines = stopText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val stopsValid = stopLines.size <= 4
    val maxTokensValid = currentProvider == null || maxTokens.toIntOrNull()?.let { it >= 256 } == true
    var confirmDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val lastTurnsValid = lastTurns.toIntOrNull()?.let { it in 1..100 } == true
    // Đợt fix 11/11/2026 (I1): file thẻ trỏ tới có thể ở bất cứ đâu, đuôi
    // bất kỳ — không còn kiểm tra đuôi .md nữa.
    // Đợt provider 12/11/2026: chế độ thinking của model Claude đang chọn
    // (để quyết định có hiện ô budget không). Model chưa chọn thì coi như
    // cần budget (còn đặt được giá trị trước).
    val thinkingMode = if (providerType == ProviderType.CLAUDE) claudeThinkingModeFor(currentModel ?: "") else null
    val showThinkingBudget = thinkingEnabled && (thinkingMode == ClaudeThinkingMode.MANUAL || currentModel.isNullOrBlank())
    // Đợt provider 12/11/2026: budget thinking (chỉ khi ô budget đang hiện)
    // phải ≥ 1024 token và nhỏ hơn độ dài tối đa của câu trả lời — quy định
    // của Anthropic, sai thì báo rõ chứ không tự sửa.
    val thinkingBudgetValid = !showThinkingBudget ||
        (thinkingBudgetText.toIntOrNull()?.let { it in 1024 until (maxTokens.toIntOrNull() ?: 8192) } ?: false)
    val dirty = card != null && (
        text != card.text || label != card.label || role != card.role || policy != card.policy ||
            lastTurns != card.lastTurns.toString() || compact != card.compactToolResults ||
            turnTags != card.turnTags || includeErrors != card.includeErrorTurns ||
            file.trim() != (card.file ?: "") || toolFiles != card.toolFiles() ||
            toolsSource != (card?.toolsSource ?: ToolsSource.FILE) ||
            source != (if (card.file != null) CardSource.FILE else CardSource.TEXT) ||
            effort != savedMetadata.effort || stopText != savedMetadata.stopSequences.joinToString("\n") ||
            keepSignature != savedMetadata.keepThoughtSignature ||
            thinkingEnabled != savedMetadata.thinkingEnabled ||
            thinkingBudgetText != savedMetadata.thinkingBudget.toString() ||
            (currentProvider != null && maxTokens != currentProvider.maxOutputTokens.toString())
        )
    val saveValid = (card?.kind != CardKind.HISTORY || policy != HistoryPolicy.LAST_N || lastTurnsValid) && stopsValid && maxTokensValid && thinkingBudgetValid
    // Thẻ Time/Last turn không có gì để Save (sửa trực tiếp hoặc chỉ đọc).
    // Thẻ Tools CÓ Save (danh sách file) từ Đợt E.
    val hasSave = card != null && card.kind != CardKind.TIME && card.kind != CardKind.LAST_TURN

    // Đợt E: xem trước danh sách tool thẻ sẽ offer (đọc + parse lại mỗi khi
    // đổi nguồn/file/văn bản). Nguồn Text parse ngay tại chỗ (không cần I/O);
    // nguồn File đọc + parse lại như lúc gửi.
    LaunchedEffect(toolFiles, toolsSource, text, card?.kind) {
        if (card?.kind != CardKind.TOOLS) return@LaunchedEffect
        toolsPreview = null
        toolsPreviewError = null
        val origins = container.registry.originDescriptions()
        if (toolsSource == ToolsSource.TEXT) {
            if (text.isBlank()) {
                toolsPreview = "No tools — the card text is empty."
                return@LaunchedEffect
            }
            try {
                val parsed = ToolsMdParser.parse(text, origins)
                toolsPreview = if (parsed.isEmpty()) "No tools — the card text lists no tools."
                else "Offers ${parsed.size} tool(s): " + parsed.joinToString(", ") { it.name }
            } catch (e: ToolsMdParseException) {
                toolsPreviewError = "In the Tools card text: ${e.message}"
            }
            return@LaunchedEffect
        }
        // Nguồn File mà chưa chọn file nào thì báo ngay ở đây (lúc gửi cũng
        // chặn với cùng lỗi này — xem RequestLayout.resolveToolFiles).
        if (toolFiles.isEmpty()) {
            toolsPreviewError = "No file selected for the Tools card. " +
                "Choose one or more files (in tools.md format), or switch the card to Text input."
            return@LaunchedEffect
        }
        val layout = store.storedLayout.value
        // Ép toolsSource = FILE cho bản xem trước: user đang sửa ở nguồn File
        // nhưng chưa Save (bố cục đã lưu có thể còn là TEXT).
        val previewCard = layout.toolsCard()?.copy(files = toolFiles, toolsSource = ToolsSource.FILE) ?: return@LaunchedEffect
        val previewLayout = layout.copy(cards = layout.cards.map { if (it.id == previewCard.id) previewCard else it })
        val res = withContext(Dispatchers.IO) {
            previewLayout.resolveToolFiles({ path -> runCatching { container.fs.readCardFile(path) } }, origins)
        }
        if (res.errors.isNotEmpty()) {
            toolsPreviewError = res.errors.joinToString("\n")
        } else {
            val names = res.exposed.orEmpty()
            toolsPreview = if (names.isEmpty()) "No tools — the selected files list no tools."
            else "Offers ${names.size} tool(s): " + names.joinToString(", ") { it.name }
        }
    }

    BackHandler(enabled = dirty) { confirmDiscard = true }
    val goBack: () -> Unit = { if (dirty) confirmDiscard = true else onBack() }
    fun save() {
        val c = card ?: return
        // Đợt 6: thẻ Metadata không lưu vào thẻ (thẻ chỉ là vị trí cố định):
        // giá trị lưu toàn cục ở SettingsRepository, max tokens lưu theo
        // provider đang dùng (như màn sửa provider).
        if (c.kind == CardKind.METADATA) {
            container.settings.setMetadata(
                MetadataParams(
                    effort = effort,
                    stopSequences = stopLines.take(4),
                    keepThoughtSignature = keepSignature,
                    thinkingEnabled = thinkingEnabled,
                    thinkingBudget = thinkingBudgetText.toIntOrNull() ?: savedMetadata.thinkingBudget,
                )
            )
            val cfg = currentProvider
            val max = maxTokens.toIntOrNull()
            if (cfg != null && max != null && max >= 256 && max != cfg.maxOutputTokens) {
                container.settings.saveProvider(cfg.copy(maxOutputTokens = max))
            }
            onBack()
            return
        }
        // Đợt provider 12/11/2026 (quyết định của Duy — KHÓA CỨNG thay vì
        // branch): cuộc có thinking của Claude thì không sửa được thẻ
        // System/Tools/Free nữa (thẻ lưu chung cho mọi cuộc nên sửa sẽ phá
        // mạch suy luận của cuộc này); muốn đổi thì mở cuộc mới rồi sửa thẻ
        // ở đó. Chỉ chặn khi đủ 3 điều kiện (niêm phong + provider Claude +
        // thinking đang bật) — như cổng của thẻ Free ở Q4.
        if ((c.kind == CardKind.SYSTEM || c.kind == CardKind.TOOLS || c.kind == CardKind.FREE) && conversationId != null) {
            scope.launch {
                val sealedBlocked = container.ops.hasThinkingSeal(conversationId) &&
                    providerType == ProviderType.CLAUDE && savedMetadata.thinkingEnabled
                if (sealedBlocked) {
                    lockError = THINKING_SEAL_CARD_BLOCK_MESSAGE
                } else {
                    saveCard(c)
                }
            }
            return
        }
        saveCard(c)
    }

    /** Lưu nội dung thẻ đã sửa (trừ Metadata — xem save()). */
    fun saveCard(c: RequestCard, goBack: Boolean = true) {
        store.updateCard(c.id) {
            it.copy(
                text = text,
                label = label.trim(),
                role = role,
                policy = policy,
                lastTurns = lastTurns.toIntOrNull()?.coerceIn(1, 100) ?: c.lastTurns,
                compactToolResults = compact,
                turnTags = turnTags,
                includeErrorTurns = includeErrors,
                // Thẻ Tools dùng danh sách files (file đơn cũ gộp vào đó);
                // thẻ khác giữ nguyên file/files của chúng. Đợt 8: nguồn
                // Text input ghi file = null (bỏ liên kết file cũ), nguồn
                // External file ghi URI/đường dẫn đã chọn. Quyết định 16:
                // chọn Text nghĩa là thẻ là Text — bỏ luôn danh sách file
                // cũ, không giữ cả hai.
                file = if (c.kind == CardKind.TOOLS) null
                else if (source == CardSource.FILE) file.trim().ifEmpty { null }
                else null,
                files = if (c.kind == CardKind.TOOLS) {
                    if (toolsSource == ToolsSource.TEXT) emptyList() else toolFiles
                } else it.files,
                // Sửa luật nguồn 11/11/2026: chỉ thẻ Tools dùng toolsSource;
                // thẻ khác giữ nguyên giá trị đã lưu (mặc định FILE).
                toolsSource = if (c.kind == CardKind.TOOLS) toolsSource else it.toolsSource,
            )
        }
        // Đợt provider 12/11/2026 (Q2): khi sửa thẻ trong cuộc có thinking
        // của Claude thì không quay về panel (màn sẽ mở cuộc nhánh mới).
        if (goBack) onBack()
    }

    /** Tạo file mẫu (nếu chưa có) rồi trỏ thẻ tới file đó. */
    fun createTemplate() {
        val c = card ?: return
        // Đợt E: mẫu tools.md liệt kê TẤT CẢ tool kèm tên/mô tả gốc — chỉ để
        // user lấy lại khi cần, KHÔNG tự ghi đè file của user (chỉ ghi khi
        // file chưa có; xem ToolsMdParser.buildTemplate).
        if (c.kind == CardKind.TOOLS) {
            // Đợt fix 11/11/2026 (A2): không còn ô gõ đường dẫn tay — mẫu luôn
            // ở đường mặc định.
            val path = "Tov/prompts/tools.md"
            scope.launch(Dispatchers.IO) {
                runCatching {
                    if (container.fs.find(path) == null) {
                        container.fs.writeText(path, ToolsMdParser.buildTemplate(container.registry.all))
                    }
                }.onSuccess {
                    withContext(Dispatchers.Main) {
                        if (path !in toolFiles) toolFiles = toolFiles + path
                        fileError = null
                    }
                }.onFailure { e ->
                    withContext(Dispatchers.Main) { fileError = e.message ?: "Could not create the file." }
                }
            }
            return
        }
        val path = file.trim().ifBlank {
            when (c.kind) {
                CardKind.SYSTEM -> "Tov/prompts/system.md"
                else -> "Tov/prompts/" + label.trim().lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "free-card" } + ".md"
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (container.fs.find(path) == null) {
                    container.fs.writeText(path, templateText(c.kind))
                }
            }.onSuccess {
                withContext(Dispatchers.Main) { file = path; fileError = null; source = CardSource.FILE }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { fileError = e.message ?: "Could not create the file." }
            }
        }
    }

    /** Mở file đã trỏ bằng app ngoài (trình soạn .md) — dùng helper chung (OpenFile.kt). */
    fun openFile() {
        openFileAt(context, container.fs, file.trim(), onError = { fileError = it })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(card?.displayName() ?: "Card") },
                navigationIcon = {
                    IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (hasSave) {
                        TextButton(enabled = dirty && saveValid, onClick = ::save) { Text("Save") }
                    }
                },
            )
        },
    ) { padding ->
        if (card == null) {
            Text("This card was not found.", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Đợt provider 12/11/2026: thông báo khóa thẻ vì thinking của
            // Claude (hiện khi user bấm Save mà cuộc đang bị niêm phong).
            if (lockError != null) {
                Text(lockError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            when (card.kind) {
                CardKind.METADATA -> {
                    // Đợt 6: model chỉ đọc (chọn ở thanh nhập chat như cũ).
                    // Đợt fix 11/11/2026 (D1–D6): viết lại bằng tiếng thường,
                    // không thuật ngữ API; bỏ "Tools for this request",
                    // Structured output, Service tier và dòng Stream.
                    Text(
                        "Model: ${currentModel ?: "no model"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "The model is chosen in the chat input bar, not here — this card only shows it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Đợt provider 12/11/2026: mọi hãng đều có lựa chọn mức
                    // suy luận (OpenAI dùng Responses API — suy luận với tool
                    // dùng chung được; DeepSeek gửi lại reasoning_content;
                    // Claude/Gemini như cũ). D3 cũ (Chat Completions ép
                    // effort "none" cho OpenAI) đã bỏ.
                    Text("How hard the model thinks", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "How hard the model thinks before it answers. Harder thinking " +
                            "takes longer and costs more, but can give better answers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    savedMetadata.effortOptionsFor(providerType).forEach { option ->
                        PolicyOption(
                            selected = effort == option,
                            title = option.name.lowercase(),
                            desc = when (option) {
                                ReasoningEffort.NONE -> "As little thinking as the provider allows."
                                ReasoningEffort.MINIMAL -> "The least thinking."
                                ReasoningEffort.LOW -> "A little thinking — faster answers."
                                ReasoningEffort.MEDIUM -> "Balanced (default)."
                                ReasoningEffort.HIGH -> "More thinking."
                                ReasoningEffort.XHIGH -> "Very deep thinking."
                                ReasoningEffort.MAX -> "Maximum thinking."
                            },
                            onClick = { effort = option },
                        )
                    }
                    // Đợt provider 12/11/2026: thinking của Claude — chỉ
                    // hãng này mới có công tắc riêng (OpenAI/DeepSeek dùng
                    // chung lựa chọn "How hard the model thinks" ở trên).
                    if (providerType == ProviderType.CLAUDE) {
                        SwitchRow(
                            title = "Extended thinking",
                            desc = "Let Claude think before it answers. The thinking is shown in the chat under \"Suy luận\" and sent back to Claude with the next request.",
                            checked = thinkingEnabled,
                            onChange = { thinkingEnabled = it },
                        )
                        if (thinkingEnabled && thinkingMode == ClaudeThinkingMode.ADAPTIVE) {
                            Text(
                                "This model manages its own thinking depth — \"How hard the model thinks\" above controls it. No separate budget for this model.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (thinkingEnabled && thinkingMode == ClaudeThinkingMode.ADAPTIVE_ALWAYS_ON) {
                            Text(
                                "This model thinks by default — Anthropic doesn't let it be turned off. Turning the switch off only stops the app from asking for a readable summary.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (showThinkingBudget) {
                            OutlinedTextField(
                                value = thinkingBudgetText,
                                onValueChange = { thinkingBudgetText = it.filter { ch -> ch.isDigit() }.take(7) },
                                label = { Text("Thinking budget (tokens)") },
                                singleLine = true,
                                isError = !thinkingBudgetValid,
                                supportingText = {
                                    if (!thinkingBudgetValid) Text("Enter a number from 1024 up to the longest answer above, minus 1.")
                                    else Text("How many tokens Claude may spend thinking. At least 1024, and less than the longest answer.")
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (currentProvider != null) {
                        OutlinedTextField(
                            value = maxTokens,
                            onValueChange = { maxTokens = it.filter { ch -> ch.isDigit() }.take(7) },
                            label = { Text("Longest answer (tokens)") },
                            singleLine = true,
                            isError = !maxTokensValid,
                            supportingText = {
                                if (!maxTokensValid) Text("Enter a number of 256 or more.")
                                else Text("Saved for this provider (${currentProvider.name}). At least 256.")
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            "Longest answer (tokens): choose a provider and model first (it is saved per provider).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = stopText,
                        onValueChange = { stopText = it },
                        label = { Text("Stop phrases (one per line, up to 4)") },
                        minLines = 2,
                        maxLines = 5,
                        isError = !stopsValid,
                        supportingText = { if (!stopsValid) Text("At most 4 phrases.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "If the model is about to write one of these, it stops there instead. " +
                            "For example, write \"User:\" so the model never starts a new turn by itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Đợt fix 12/11/2026 (K): công tắc chữ ký suy luận — chỉ
                    // áp cho chữ ký của phần chữ trả lời; chữ ký của lệnh gọi
                    // tool thì luôn gửi lại (Gemini bắt buộc).
                    SwitchRow(
                        title = "Keep thought signature",
                        desc = "Sends Gemini's thought signature back with the reply, so the model keeps its train of thought. Tool signatures are always kept.",
                        checked = keepSignature,
                        onChange = { keepSignature = it },
                    )
                }
                CardKind.SYSTEM -> {
                    // Đợt 8: ô Name (lưu vào label đã có; tên hiện trên Panel).
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Card name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Source", style = MaterialTheme.typography.labelLarge)
                    PolicyOption(
                        selected = source == CardSource.TEXT,
                        title = "Text input",
                        desc = "Type the system prompt here.",
                        onClick = { source = CardSource.TEXT },
                    )
                    PolicyOption(
                        selected = source == CardSource.FILE,
                        title = "External file",
                        desc = "Read from a file anywhere on this device, again on every send.",
                        onClick = { source = CardSource.FILE },
                    )
                    if (source == CardSource.TEXT) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("System prompt") },
                            minLines = 6,
                            maxLines = 12,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(VARS_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FileSection(
                            file = file,
                            fileError = fileError,
                            onCreateTemplate = ::createTemplate,
                            onOpenFile = ::openFile,
                            onFilePicked = { file = it; fileError = null; source = CardSource.FILE },
                            onPickError = { fileError = it },
                        )
                    }
                }
                CardKind.FREE -> {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Card name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Role", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        FreeRole.entries.forEachIndexed { index, r ->
                            SegmentedButton(
                                selected = role == r,
                                onClick = { role = r },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = FreeRole.entries.size),
                                label = { Text(if (r == FreeRole.SYSTEM) "System" else "User") },
                            )
                        }
                    }
                    Text(
                        if (role == FreeRole.SYSTEM) "Sent as part of the system prompt."
                        else "Sent as a message from you, at this card's position in the list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Đợt 8: chọn nguồn rạch ròi như thẻ System (dùng chung FileSection).
                    Text("Source", style = MaterialTheme.typography.labelLarge)
                    PolicyOption(
                        selected = source == CardSource.TEXT,
                        title = "Text input",
                        desc = "Type the text here.",
                        onClick = { source = CardSource.TEXT },
                    )
                    PolicyOption(
                        selected = source == CardSource.FILE,
                        title = "External file",
                        desc = "Read from a file anywhere on this device, again on every send.",
                        onClick = { source = CardSource.FILE },
                    )
                    if (source == CardSource.TEXT) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Text") },
                            minLines = 4,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(VARS_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FileSection(
                            file = file,
                            fileError = fileError,
                            onCreateTemplate = ::createTemplate,
                            onOpenFile = ::openFile,
                            onFilePicked = { file = it; fileError = null; source = CardSource.FILE },
                            onPickError = { fileError = it },
                        )
                    }
                }
                CardKind.TIME -> TimeCardEditor()
                CardKind.HISTORY -> {
                    Text("How much history to send", style = MaterialTheme.typography.labelLarge)
                    PolicyOption(
                        selected = policy == HistoryPolicy.BUDGET,
                        title = "Fit the token budget",
                        desc = "Cut the oldest turns so the chat fits the model's limit. The latest turn is always kept.",
                        onClick = { policy = HistoryPolicy.BUDGET },
                    )
                    PolicyOption(
                        selected = policy == HistoryPolicy.LAST_N,
                        title = "Last N turns",
                        desc = "Keep only the most recent turns, whatever they cost.",
                        onClick = { policy = HistoryPolicy.LAST_N },
                    )
                    if (policy == HistoryPolicy.LAST_N) {
                        OutlinedTextField(
                            value = lastTurns,
                            onValueChange = { lastTurns = it.filter { ch -> ch.isDigit() }.take(3) },
                            label = { Text("Number of turns") },
                            singleLine = true,
                            isError = !lastTurnsValid,
                            supportingText = { if (!lastTurnsValid) Text("Enter a number from 1 to 100.") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    PolicyOption(
                        selected = policy == HistoryPolicy.NO_CUT,
                        title = "Send everything",
                        desc = "No cutting at all. The request may exceed the model's limit on long chats.",
                        onClick = { policy = HistoryPolicy.NO_CUT },
                    )
                    SwitchRow(
                        title = "Shorten old tool results",
                        desc = "Tool results longer than 500 characters in older turns become one line. The latest turn is never shortened.",
                        checked = compact,
                        onChange = { compact = it },
                    )
                    SwitchRow(
                        title = "Tag turns",
                        desc = "Add the turn number to each of your messages, so the AI can refer to a turn.",
                        checked = turnTags,
                        onChange = { turnTags = it },
                    )
                    SwitchRow(
                        title = "Include error turns",
                        desc = "Send turns where the AI returned an error, instead of leaving them out.",
                        checked = includeErrors,
                        onChange = { includeErrors = it },
                    )
                    // Đợt 4: đường vào màn Messages — quản lý từng lượt của cuộc này.
                    if (onOpenMessages != null) {
                        Text("Messages of this chat", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "See every turn, edit or delete single messages, and delete whole turns.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onOpenMessages) { Text("Manage messages of this chat") }
                    }
                }
                CardKind.TOOLS -> {
                    // Đợt E: thẻ Tools có editor riêng — danh sách file tools.md,
                    // KHÔNG mở màn quản lý tool nữa (hai tầng tách riêng: tầng
                    // quyền ở Settings/Manage Tools, tầng RCP ở đây).
                    Text(
                        "This card is not a message: it is the tool list sent with the request, " +
                            "so its position in the panel changes nothing. Every tool listed is " +
                            "offered to the model, in file order, then block order. A tool not " +
                            "listed is not offered. Delete a block to hide that tool. Turn the " +
                            "card off to offer no tools at all.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Source", style = MaterialTheme.typography.labelLarge)
                    PolicyOption(
                        selected = toolsSource == ToolsSource.TEXT,
                        title = "Text input",
                        desc = "Type the tools list here, in tools.md format. Leave it empty to offer no tools.",
                        onClick = { toolsSource = ToolsSource.TEXT },
                    )
                    PolicyOption(
                        selected = toolsSource == ToolsSource.FILE,
                        title = "External file",
                        desc = "Read from one or more files anywhere on this device, again on every send. Each file must list tools in tools.md format.",
                        onClick = { toolsSource = ToolsSource.FILE },
                    )
                    if (toolsSource == ToolsSource.TEXT) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Tools list (tools.md format)") },
                            minLines = 6,
                            maxLines = 12,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "One block per tool, starting with \"## <name>\"; each block needs " +
                                "uid, origin_description, name and description. Lines starting " +
                                "with \">\" are notes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("Files", style = MaterialTheme.typography.labelLarge)
                        if (toolFiles.isEmpty()) {
                            Text(
                                "(no files chosen)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        toolFiles.forEach { path ->
                            ToolFileRow(
                                path = path,
                                onOpen = { openFileAt(context, container.fs, path, onError = { fileError = it }) },
                                onRemove = { toolFiles = toolFiles - path },
                            )
                        }
                        // Đợt fix 11/11/2026 (A2): không gõ đường dẫn tay nữa —
                        // chọn bằng hộp chọn của hệ thống (như thẻ System); các
                        // nút xếp dọc dạng TextButton như mẫu FPilot.
                        Column {
                            TextButton(onClick = { pickToolFile.launch(arrayOf("*/*")) }) {
                                Text("Choose file")
                            }
                            TextButton(onClick = ::createTemplate) { Text("Create template") }
                        }
                        if (fileError != null) {
                            Text(fileError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text("What the model will see", style = MaterialTheme.typography.labelLarge)
                    if (toolsPreviewError != null) {
                        Text(toolsPreviewError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else if (toolsPreview != null) {
                        Text(toolsPreview!!, style = MaterialTheme.typography.bodySmall)
                    }
                }
                CardKind.LAST_TURN -> {
                    Text(
                        "The last turn is your latest message and the tool round in progress. " +
                            "It is always sent, and always after History.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "Your edits to this card have not been saved.",
            confirmLabel = "Discard",
            onConfirm = { confirmDiscard = false; onBack() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun PolicyOption(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(desc) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

/**
 * Thẻ Time: BẬT = chèn dòng giờ vào cuối system; TẮT = không chèn
 * (Đợt 2, quyết định 1). Không còn chọn mode cho time.now ở đây: mode
 * AUTO/ASK/OFF vẫn tồn tại cho các tool khác ở tầng quản lý tool, nhưng
 * thẻ Time không ghi vào ToolPrefs nữa.
 */
@Composable
private fun TimeCardEditor() {
    Text(
        "When this card is on, the current time is added at the end of the system prompt " +
            "as \"Current time: YYYY-MM-DD HH:MM\" (date and time only — no time zone, " +
            "no location, no seconds). When it is off, no time is added. Either way, the " +
            "model can still call the time tool (time.now) from the Tools list whenever " +
            "it wants the time.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "When this card is on, the time line changes every minute, so the cache can't be reused.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        "Turn the card on or off with the switch on the Request panel.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Nội dung mẫu cho file .md mới của thẻ (Đợt D). */
private fun templateText(kind: CardKind): String = when (kind) {
    CardKind.SYSTEM -> "# System prompt\n\nWrite the instructions for the AI here. This file is read again on every send, so edits take effect immediately.\n\nYou can use {date}, {time}, {model} and {provider}.\n"
    else -> "# Note for the AI\n\nWrite here what the AI should know. This file is read again on every send.\n"
}

/**
 * Hộp chọn file cho thẻ (SAF OpenDocument), dùng chung cho mục "File" của
 * thẻ System/Free và trình sửa thẻ Tools (đợt fix 11/11/2026, A1+A2 — theo
 * mẫu FPilot: chọn bằng hộp chọn, không gõ đường dẫn tay).
 * Đợt fix 11/11/2026 (I1): file có thể ở BẤT CỨ ĐÂU trên máy, đuôi bất kỳ
 * — nội dung mới là thứ được parse, workspace chỉ là thư mục của AI chứ
 * không phải nơi bắt buộc chứa file của thẻ. Chọn xong thì giữ quyền đọc
 * lâu dài (persistable) rồi trả về URI dạng chuỗi; giữ không được quyền
 * thì báo lỗi (không lưu URI sẽ mất quyền khi khởi động lại app). File
 * trong workspace vẫn lưu bằng đường dẫn tương đối như thẻ cũ.
 */
@Composable
private fun rememberCardFilePicker(
    onPicked: (String) -> Unit,
    /** File đã chọn không dùng được (không giữ được quyền, ...). */
    onPickError: (String) -> Unit,
): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val fs = LocalContext.current.container.fs
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // File trong workspace thì lưu đường dẫn tương đối như thẻ cũ (dễ
        // đọc, quyền đọc đã có sẵn từ quyền của cả cây); file ngoài thì
        // giữ quyền đọc lâu dài rồi lưu URI dạng chuỗi.
        val relative = fs.relativePathForDocument(uri)
        if (relative != null) {
            onPicked(relative)
            return@rememberLauncherForActivityResult
        }
        if (!fs.takeCardFilePermission(uri)) {
            onPickError("Could not keep access to this file. Pick it again, or copy it into the workspace.")
            return@rememberLauncherForActivityResult
        }
        onPicked(uri.toString())
    }
}

/**
 * Mục "File" trong màn sửa thẻ System/Free (Đợt D, nút "Change file" thêm ở
 * Đợt 8). Đợt fix 11/11/2026 (A1): theo mẫu FPilot — không còn ô gõ đường
 * dẫn tay (khi đã chọn file thì ô đó vẫn còn là điểm Duy chê); chỉ hiện tên
 * file đã chọn dạng chữ + các nút TextButton xếp dọc (Choose/Change file,
 * Open file chỉ khi đã có file, Create template).
 */
@Composable
private fun FileSection(
    file: String,
    fileError: String?,
    onCreateTemplate: () -> Unit,
    onOpenFile: () -> Unit,
    /** File vừa chọn bằng hộp chọn của hệ thống (URI file ngoài, hoặc đường dẫn tương đối trong workspace — thẻ cũ). */
    onFilePicked: (String) -> Unit,
    /** File đã chọn không dùng được (không giữ được quyền, ...). */
    onPickError: (String) -> Unit,
) {
    val fs = LocalContext.current.container.fs
    val pickFile = rememberCardFilePicker(onPicked = onFilePicked, onPickError = onPickError)
    // Đợt fix 11/11/2026 (I1): tên hiển thị của file ngoài (URI) phải tra
    // qua nhà cung cấp nên đọc nền; chưa tra xong thì hiện đoạn cuối của
    // giá trị đã lưu.
    var displayName by remember(file) { mutableStateOf(file.substringAfterLast('/').ifEmpty { file }) }
    LaunchedEffect(file) {
        if (file.isNotBlank()) displayName = withContext(Dispatchers.IO) { fs.cardFileDisplayName(file) }
    }
    Text("File", style = MaterialTheme.typography.labelLarge)
    if (file.isBlank()) {
        Text(
            "(no file chosen)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(displayName, style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        "It can be any file on this device — it does not have to be in the workspace. " +
            "Its content is read again on every send. Edit the file in any editor and the next send " +
            "uses the new text. If the file cannot be read, the card's saved text is used instead " +
            "and an error is shown.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column {
        TextButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
            Text(if (file.isBlank()) "Choose file" else "Change file")
        }
        if (file.isNotBlank()) {
            TextButton(onClick = onOpenFile) { Text("Open file") }
        }
        TextButton(onClick = onCreateTemplate) { Text("Create template") }
    }
    if (fileError != null) {
        Text(fileError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * Một dòng file trong trình sửa thẻ Tools (đợt fix 11/11/2026, I1): hiện
 * tên file thật thay vì URI/đường dẫn đã lưu. Tên của file ngoài (URI)
 * phải tra qua nhà cung cấp nên đọc nền; chưa tra xong thì hiện đoạn cuối
 * của giá trị đã lưu.
 */
@Composable
private fun ToolFileRow(
    path: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val fs = LocalContext.current.container.fs
    var displayName by remember(path) { mutableStateOf(path.substringAfterLast('/').ifEmpty { path }) }
    LaunchedEffect(path) {
        displayName = withContext(Dispatchers.IO) { fs.cardFileDisplayName(path) }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(onClick = onOpen) {
            Icon(Icons.Default.OpenInNew, contentDescription = "Open file")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove file")
        }
    }
}
