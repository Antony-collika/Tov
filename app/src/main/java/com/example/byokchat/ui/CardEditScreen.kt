package com.example.byokchat.ui

import androidx.activity.compose.BackHandler
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
import com.example.byokchat.ai.MetadataParams
import com.example.byokchat.ai.ReasoningEffort
import com.example.byokchat.data.CardKind
import com.example.byokchat.data.RequestCard
import com.example.byokchat.data.FreeRole
import com.example.byokchat.data.HistoryPolicy
import com.example.byokchat.data.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VARS_HELP = "You can use {date}, {time}, {model} and {provider} — they are replaced when sending. " +
    "Note: {time} changes every minute, so the AI's cache is not reused while it is in the prompt."

/** Sửa một thẻ trong Request panel (Đợt C). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardEditScreen(cardId: String, onBack: () -> Unit, onOpenMessages: (() -> Unit)? = null) {
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
    // RCP (17/11/2026): thẻ System/Free đồng bộ hai chiều với file ngoài,
    // như cấu hình sub-agent — ghi chú hiện khi file vừa điền vào ô text.
    var syncNote by remember(cardId) { mutableStateOf<String?>(null) }
    // RCP (17/11/2026): thẻ Tools chọn trong app — UID các tool đã chọn
    // (thứ tự offer tính lúc gửi theo nhóm + registry, không phải thứ tự
    // tích). Thẻ chưa có lựa chọn đã lưu thì mặc định chọn hết (xem
    // RequestCard.effectiveSelectedToolUids); bỏ hết tick rồi Save thì
    // lưu rỗng = 0 tools, khác với chưa lưu (sửa 17/11/2026).
    val allToolUids = remember { container.registry.all.map { it.uid } }
    val initialSelectedUids = remember(cardId) {
        if (card?.kind == CardKind.TOOLS) card.effectiveSelectedToolUids(allToolUids).toSet()
        else card?.selectedToolUids?.toSet().orEmpty()
    }
    var selectedUids by remember(cardId) { mutableStateOf(initialSelectedUids) }
    var showToolPicker by remember(cardId) { mutableStateOf(false) }
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
    var replayReasoning by remember(cardId) { mutableStateOf(savedMetadata.replayReasoning) }
    var maxTokens by remember(cardId) { mutableStateOf((currentProvider?.maxOutputTokens ?: 8192).toString()) }
    val stopLines = stopText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val stopsValid = stopLines.size <= 4
    val maxTokensValid = currentProvider == null || maxTokens.toIntOrNull()?.let { it >= 256 } == true
    var confirmDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /**
     * RCP (17/11/2026): đồng bộ hai chiều thẻ System/Free với file ngoài,
     * như cấu hình sub-agent — CHỈ ở 2 điểm, không watcher: (a) đây, lúc
     * mở màn — đọc file một lần; nội dung khác văn bản đã lưu thì file
     * thắng và điền vào ô text; lỗi đọc thì báo lỗi, giữ văn bản đã lưu.
     * (b) lúc Save — ghi văn bản vào thẻ rồi ghi đè lại file (xem save()).
     * Đọc lại file mỗi lần gửi (resolveFiles) vẫn là lưới an toàn.
     */
    LaunchedEffect(cardId) {
        val c = card ?: return@LaunchedEffect
        if (c.kind != CardKind.SYSTEM && c.kind != CardKind.FREE) return@LaunchedEffect
        val path = c.file?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val content = withContext(Dispatchers.IO) {
            runCatching { container.fs.readCardFile(path) }
        }
        content.onSuccess {
            if (it != c.text) {
                text = it
                syncNote = "Updated from the linked file."
            }
        }.onFailure { e ->
            fileError = "Could not read the linked file (${e.message ?: e.javaClass.simpleName}); showing the saved text."
        }
    }

    /** Đọc file vừa chọn và điền nội dung vào ô text (như agent: 'Filled from the linked file.'). */
    fun fillTextFromFile(path: String) {
        scope.launch(Dispatchers.IO) {
            val content = runCatching { container.fs.readCardFile(path) }
            withContext(Dispatchers.Main) {
                content.onSuccess {
                    text = it
                    syncNote = "Filled from the linked file."
                }.onFailure { e ->
                    fileError = "Could not read the file: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    val lastTurnsValid = lastTurns.toIntOrNull()?.let { it in 1..100 } == true
    // Đợt fix 11/11/2026 (I1): file thẻ trỏ tới có thể ở bất cứ đâu, đuôi
    // bất kỳ — không còn kiểm tra đuôi .md nữa.
    val dirty = card != null && (
        text != card.text || label != card.label || role != card.role || policy != card.policy ||
            lastTurns != card.lastTurns.toString() || compact != card.compactToolResults ||
            turnTags != card.turnTags || includeErrors != card.includeErrorTurns ||
            file.trim() != (card.file ?: "") ||
            selectedUids != initialSelectedUids ||
            effort != savedMetadata.effort || stopText != savedMetadata.stopSequences.joinToString("\n") ||
            keepSignature != savedMetadata.keepThoughtSignature ||
            replayReasoning != savedMetadata.replayReasoning ||
            (currentProvider != null && maxTokens != currentProvider.maxOutputTokens.toString())
        )
    val saveValid = (card?.kind != CardKind.HISTORY || policy != HistoryPolicy.LAST_N || lastTurnsValid) && stopsValid && maxTokensValid
    // Thẻ Time/Last turn không có gì để Save (sửa trực tiếp hoặc chỉ đọc).
    // Thẻ Tools CÓ Save (danh sách file) từ Đợt E.
    val hasSave = card != null && card.kind != CardKind.TIME && card.kind != CardKind.LAST_TURN

    BackHandler(enabled = dirty) { confirmDiscard = true }
    val goBack: () -> Unit = { if (dirty) confirmDiscard = true else onBack() }

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
                // RCP (17/11/2026): thẻ System/Free luôn giữ liên kết file
                // đã chọn (trống = không trỏ file). Thẻ Tools lưu danh sách
                // uid đã chọn (nhóm + registry); file cũ (nếu thẻ cũ còn
                // trỏ) thì bỏ — đường gửi không đọc file nữa.
                file = if (c.kind == CardKind.TOOLS) null
                else file.trim().ifEmpty { null },
                selectedToolUids = if (c.kind == CardKind.TOOLS) {
                    container.registry.all.map { tool -> tool.uid }.filter { it in selectedUids }
                } else it.selectedToolUids,
            )
        }
        if (goBack) onBack()
    }

    fun save() {
        val c = card ?: return
        // RCP (17/11/2026): thẻ System/Free có file thì ghi văn bản vào thẻ
        // trước, rồi ghi đè lại file (đồng bộ hai chiều, như lưu agent);
        // lỗi ghi file thì thẻ vẫn lưu, màn hình ở lại và hiện lỗi.
        if ((c.kind == CardKind.SYSTEM || c.kind == CardKind.FREE) && file.trim().isNotEmpty()) {
            saveCard(c, goBack = false)
            val path = file.trim()
            val content = text
            scope.launch(Dispatchers.IO) {
                val write = runCatching { container.fs.writeCardFile(path, content) }
                withContext(Dispatchers.Main) {
                    write.onSuccess { onBack() }
                        .onFailure {
                            fileError = "Saved, but could not write back to the linked file: " +
                                (it.message ?: it.javaClass.simpleName)
                        }
                }
            }
            return
        }
        // Đợt 6: thẻ Metadata không lưu vào thẻ (thẻ chỉ là vị trí cố định):
        // giá trị lưu toàn cục ở SettingsRepository, max tokens lưu theo
        // provider đang dùng (như màn sửa provider).
        if (c.kind == CardKind.METADATA) {
            container.settings.setMetadata(
                MetadataParams(
                    effort = effort,
                    stopSequences = stopLines.take(4),
                    keepThoughtSignature = keepSignature,
                    replayReasoning = replayReasoning,
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
        saveCard(c)
    }

    /**
     * Tạo file mẫu MỚI trong Storage/template/ rồi trỏ thẻ tới URI của file
     * đó (chỉ thẻ System/Free — thẻ Tools không còn file nữa, RCP
     * 17/11/2026). Mỗi lần bấm là một file mới (quyết định 13/11/2026):
     * tên gốc là `<loại>_template.md`; trùng tên thì tự thêm số, vd
     * system_template_(01).md, _(02)... — KHÔNG dùng lại hay ghi đè file
     * cũ, nên dù cậu có dùng chính file mẫu đó rồi sửa hỏng thì lần bấm
     * sau vẫn tạo ra bản chuẩn mới. Thẻ lưu URI content:// của file (đường
     * file ngoài đã có sẵn), không lưu đường dẫn tương đối trong playground.
     */
    fun createTemplate() {
        val c = card ?: return
        val (baseName, content) = when (c.kind) {
            CardKind.SYSTEM -> "system_template.md" to templateText(c.kind)
            else -> {
                val slug = label.trim().lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "free-card" }
                "${slug}_template.md" to templateText(c.kind)
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (container.fs.sessionRootOrNull() == null) {
                    error("Choose a Session storage folder first (Settings → Select Storage and Playground folder).")
                }
                val relPath = "template/" + uniqueTemplateName(baseName) { name ->
                    container.fs.findSession("template/$name") != null
                }
                container.fs.writeSessionText(relPath, content)
                val doc = container.fs.findSession(relPath)
                    ?: error("Could not find the created file.")
                val uriString = doc.uri.toString()
                // Quyền đọc file con trong cây Storage: thường đã có sẵn từ
                // quyền của cả cây; giữ thêm cho chắc — lỗi ở đây không phải
                // lỗi (handoff §4.9), chỉ cần đọc lại được là đủ.
                runCatching { container.fs.takeCardFilePermission(doc.uri) }
                container.fs.readCardFile(uriString)
                uriString
            }.onSuccess { uriString ->
                withContext(Dispatchers.Main) {
                    // RCP (17/11/2026): file mẫu mới trở thành file liên
                    // kết và nội dung mẫu điền vào ô text (như agent).
                    file = uriString
                    text = content
                    syncNote = "Filled from the new template file."
                    fileError = null
                }
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
                    // dùng chung được; máy chủ tương thích gửi lại
                    // reasoning_content; Gemini như cũ). D3 cũ (Chat
                    // Completions ép effort "none" cho OpenAI) đã bỏ.
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
                    // 12/11/2026: công tắc gửi lại suy luận — chỉ OpenAI
                    // (Responses API). Tắt thì lượt thường không gửi reasoning
                    // đã lưu; lượt có tool call vẫn gửi (OpenAI bắt buộc
                    // reasoning đi kèm kết quả tool call).
                    if (providerType == ProviderType.OPENAI) {
                        SwitchRow(
                            title = "Resend saved reasoning",
                            desc = "Sends the saved reasoning back with the next request. Off: plain turns leave it out, but turns with tool calls still send it — OpenAI requires reasoning with tool results.",
                            checked = replayReasoning,
                            onChange = { replayReasoning = it },
                        )
                    }
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
                    // RCP (17/11/2026): không còn chọn 1 trong 2 nguồn nữa —
                    // luôn có ô text VÀ mục File, đồng bộ hai chiều: mở màn
                    // thì file điền vào ô, lúc Save thì ô ghi đè lại file.
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("System prompt") },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(VARS_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (syncNote != null) {
                        Text(syncNote!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    FileSection(
                        file = file,
                        fileError = fileError,
                        onCreateTemplate = ::createTemplate,
                        onOpenFile = ::openFile,
                        onFilePicked = { file = it; fileError = null; fillTextFromFile(it) },
                        onPickError = { fileError = it },
                        onUnlink = { file = ""; fileError = null; syncNote = "File unlinked — the text above is used." },
                    )
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
                    // RCP (17/11/2026): như thẻ System — luôn có ô text VÀ
                    // mục File, đồng bộ hai chiều.
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Text") },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(VARS_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (syncNote != null) {
                        Text(syncNote!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    FileSection(
                        file = file,
                        fileError = fileError,
                        onCreateTemplate = ::createTemplate,
                        onOpenFile = ::openFile,
                        onFilePicked = { file = it; fileError = null; fillTextFromFile(it) },
                        onPickError = { fileError = it },
                        onUnlink = { file = ""; fileError = null; syncNote = "File unlinked — the text above is used." },
                    )
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
                    // RCP (17/11/2026): thẻ Tools chỉ chọn trong app — danh
                    // sách uid, KHÔNG còn file tools.md. Hai tầng tách riêng:
                    // tầng quyền ở Settings/Manage Tools, tầng chọn ở đây.
                    // Chưa có lựa chọn đã lưu thì mặc định chọn hết; bỏ hết
                    // tick rồi Save thì không offer tool nào (như tắt thẻ).
                    Text(
                        "This card is not a message: it is the tool list sent with the request, " +
                            "so its position in the panel changes nothing. Every tool selected is " +
                            "offered to the model, in the app's group order. A card with no saved " +
                            "choice offers all tools — uncheck the ones you don't want. " +
                            "Uncheck all of them to offer no tools at all.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val namesByUid = container.registry.all.associate { it.uid to it.defaultName }
                    Text(
                        toolSelectionSummary(selectedUids, namesByUid),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { showToolPicker = true }) { Text("Choose tools") }
                    if (showToolPicker) {
                        ToolPickerSheet(
                            tools = container.registry.all,
                            selected = selectedUids,
                            onSelectionChange = { selectedUids = it },
                            onDismiss = { showToolPicker = false },
                        )
                    }
                    Text("What the model will see", style = MaterialTheme.typography.labelLarge)
                    val offered = container.registry.all.filter { it.uid in selectedUids }
                    Text(
                        if (offered.isEmpty()) "No tools — nothing is offered to the model."
                        else "Offers ${offered.size} tool(s): " + offered.joinToString(", ") { it.defaultName },
                        style = MaterialTheme.typography.bodySmall,
                    )
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
 * Chọn tên file mẫu chưa trùng trong thư mục template (logic thuần, có test
 * riêng — quyết định 13/11/2026: mỗi lần bấm "Create template" là một file
 * mới). Tên gốc còn trống thì dùng luôn; trùng thì thêm số, vd
 * tools_template.md → tools_template_(01).md → _(02)... Số đệm 2 chữ số.
 */
internal fun uniqueTemplateName(baseName: String, exists: (String) -> Boolean): String {
    if (!exists(baseName)) return baseName
    val dot = baseName.lastIndexOf('.')
    val (stem, ext) = if (dot > 0) baseName.substring(0, dot) to baseName.substring(dot) else baseName to ""
    for (i in 1..9999) {
        val candidate = stem + "_(" + i.toString().padStart(2, '0') + ")" + ext
        if (!exists(candidate)) return candidate
    }
    error("Too many template files named like $baseName.")
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
    /** File vừa chọn bằng hộp chọn của hệ thống (URI file ngoài, hoặc đường dẫn tương đối trong playground — thẻ cũ). */
    onFilePicked: (String) -> Unit,
    /** File đã chọn không dùng được (không giữ được quyền, ...). */
    onPickError: (String) -> Unit,
    /** Gỡ liên kết file (null = không cho gỡ); văn bản trong thẻ giữ nguyên. */
    onUnlink: (() -> Unit)? = null,
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
        "It can be any file on this device — it does not have to be in the AI playground. " +
            "Its content is read again on every send. Edit the file in any editor and the next send " +
            "uses the new text. When you save here, the text above is also written back to the file. " +
            "If the file cannot be read, the card's saved text is used instead and an error is shown.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column {
        TextButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
            Text(if (file.isBlank()) "Choose file" else "Change file")
        }
        if (file.isNotBlank()) {
            TextButton(onClick = onOpenFile) { Text("Open file") }
            if (onUnlink != null) {
                TextButton(onClick = onUnlink) { Text("Unlink file") }
            }
        }
        TextButton(onClick = onCreateTemplate) { Text("Create template") }
    }
    if (fileError != null) {
        Text(fileError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

