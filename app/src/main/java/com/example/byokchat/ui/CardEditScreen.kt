package com.example.byokchat.ui

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Close
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
import com.example.byokchat.data.CardKind
import com.example.byokchat.data.FreeRole
import com.example.byokchat.data.HistoryPolicy
import com.example.byokchat.tools.ToolsMdParser
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
    // Đợt D: file .md mà thẻ trỏ tới (trống = không trỏ file).
    var file by remember(cardId) { mutableStateOf(card?.file.orEmpty()) }
    var fileError by remember(cardId) { mutableStateOf<String?>(null) }
    // Đợt E: thẻ Tools giữ NHIỀU file tools.md (file cũ chuyển thành danh sách 1 file).
    var toolFiles by remember(cardId) { mutableStateOf(card?.toolFiles().orEmpty()) }
    var newToolFile by remember(cardId) { mutableStateOf("") }
    var toolsPreview by remember(cardId) { mutableStateOf<String?>(null) }
    var toolsPreviewError by remember(cardId) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val lastTurnsValid = lastTurns.toIntOrNull()?.let { it in 1..100 } == true
    val fileValid = file.isBlank() || file.trim().endsWith(".md")
    val newToolFileValid = newToolFile.isBlank() || newToolFile.trim().endsWith(".md")
    val dirty = card != null && (
        text != card.text || label != card.label || role != card.role || policy != card.policy ||
            lastTurns != card.lastTurns.toString() || compact != card.compactToolResults ||
            turnTags != card.turnTags || includeErrors != card.includeErrorTurns ||
            file.trim() != (card.file ?: "") || toolFiles != card.toolFiles()
        )
    val saveValid = (card?.kind != CardKind.HISTORY || policy != HistoryPolicy.LAST_N || lastTurnsValid) && fileValid && newToolFileValid
    // Thẻ Time/Last turn không có gì để Save (sửa trực tiếp hoặc chỉ đọc).
    // Thẻ Tools CÓ Save (danh sách file) từ Đợt E.
    val hasSave = card != null && card.kind != CardKind.TIME && card.kind != CardKind.LAST_TURN

    // Đợt E: xem trước danh sách tool các file sẽ offer (đọc + parse lại mỗi khi đổi file).
    LaunchedEffect(toolFiles, card?.kind) {
        if (card?.kind != CardKind.TOOLS) return@LaunchedEffect
        toolsPreview = null
        toolsPreviewError = null
        if (toolFiles.isEmpty()) return@LaunchedEffect
        val layout = store.storedLayout.value
        val previewCard = layout.toolsCard()?.copy(files = toolFiles) ?: return@LaunchedEffect
        val previewLayout = layout.copy(cards = layout.cards.map { if (it.id == previewCard.id) previewCard else it })
        val origins = container.registry.originDescriptions()
        val res = withContext(Dispatchers.IO) {
            previewLayout.resolveToolFiles({ path -> runCatching { container.fs.readText(path) } }, origins)
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
                // thẻ khác giữ nguyên file/files của chúng.
                file = if (c.kind == CardKind.TOOLS) null else file.trim().ifEmpty { null },
                files = if (c.kind == CardKind.TOOLS) toolFiles else it.files,
            )
        }
        onBack()
    }

    /** Tạo file mẫu (nếu chưa có) rồi trỏ thẻ tới file đó. */
    fun createTemplate() {
        val c = card ?: return
        // Đợt E: mẫu tools.md liệt kê TẤT CẢ tool kèm tên/mô tả gốc — chỉ để
        // user lấy lại khi cần, KHÔNG tự ghi đè file của user (chỉ ghi khi
        // file chưa có; xem ToolsMdParser.buildTemplate).
        if (c.kind == CardKind.TOOLS) {
            val path = newToolFile.trim().ifBlank { "Tov/prompts/tools.md" }
            scope.launch(Dispatchers.IO) {
                runCatching {
                    if (container.fs.find(path) == null) {
                        container.fs.writeText(path, ToolsMdParser.buildTemplate(container.registry.all))
                    }
                }.onSuccess {
                    withContext(Dispatchers.Main) {
                        newToolFile = path
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
                withContext(Dispatchers.Main) { file = path; fileError = null }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { fileError = e.message ?: "Could not create the file." }
            }
        }
    }

    /** Mở file đã trỏ bằng app ngoài (trình soạn .md). */
    fun openFile() = openFileAt(file.trim())

    private fun openFileAt(path: String) {
        if (path.isEmpty()) return
        try {
            val doc = container.fs.find(path)
            if (doc == null) {
                fileError = "File not found: $path"
                return
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(doc.uri, "text/markdown")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val doc = container.fs.find(path) ?: return
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(doc.uri, "text/plain")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show()
            }
        }
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
                CardKind.SYSTEM -> {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("System prompt") },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(VARS_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FileSection(
                        file = file,
                        onFileChange = { file = it; fileError = null },
                        fileValid = fileValid,
                        fileError = fileError,
                        onCreateTemplate = ::createTemplate,
                        onOpenFile = ::openFile,
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
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Text") },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(VARS_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FileSection(
                        file = file,
                        onFileChange = { file = it; fileError = null },
                        fileValid = fileValid,
                        fileError = fileError,
                        onCreateTemplate = ::createTemplate,
                        onOpenFile = ::openFile,
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
                        title = "Tag turns [T7]",
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
                            "so its position in the panel changes nothing. Choose one or more " +
                            "tools.md files — every tool listed in them is offered to the model, " +
                            "in file order, then block order. A tool not listed in any file is " +
                            "not offered. Delete a block in the file to hide that tool.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "No file chosen yet means every tool is offered with its original " +
                            "name and description (as before). Turn the card off to offer no tools at all.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Files", style = MaterialTheme.typography.labelLarge)
                    if (toolFiles.isEmpty()) {
                        Text(
                            "(no files chosen)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    toolFiles.forEach { path ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                path,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(onClick = { toolFiles = toolFiles - path }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove file")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newToolFile,
                        onValueChange = { newToolFile = it; fileError = null },
                        label = { Text("File path, e.g. Tov/prompts/tools.md") },
                        singleLine = true,
                        isError = !newToolFileValid,
                        supportingText = { if (!newToolFileValid) Text("The file must end with .md.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = newToolFile.isNotBlank() && newToolFileValid &&
                                newToolFile.trim() !in toolFiles,
                            onClick = {
                                toolFiles = toolFiles + newToolFile.trim()
                                fileError = null
                            },
                        ) { Text("Add file") }
                        OutlinedButton(onClick = ::createTemplate) { Text("Create template") }
                        OutlinedButton(
                            enabled = newToolFile.isNotBlank(),
                            onClick = { openFileAt(newToolFile.trim()) },
                        ) { Text("Open file") }
                    }
                    if (fileError != null) {
                        Text(fileError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text("What the model will see", style = MaterialTheme.typography.labelLarge)
                    if (toolsPreviewError != null) {
                        Text(toolsPreviewError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else if (toolsPreview != null) {
                        Text(toolsPreview!!, style = MaterialTheme.typography.bodySmall)
                    } else if (toolFiles.isEmpty()) {
                        Text(
                            "All tools, with their original names and descriptions.",
                            style = MaterialTheme.typography.bodySmall,
                        )
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
        "Bật thẻ này thì dòng giờ thay đổi mỗi phút, nên cache không dùng lại được.",
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

/** Mục "File" trong màn sửa thẻ System/Free (Đợt D). */
@Composable
private fun FileSection(
    file: String,
    onFileChange: (String) -> Unit,
    fileValid: Boolean,
    fileError: String?,
    onCreateTemplate: () -> Unit,
    onOpenFile: () -> Unit,
) {
    Text("File (.md in the workspace)", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(
        value = file,
        onValueChange = onFileChange,
        label = { Text("File path, e.g. Tov/prompts/system.md") },
        singleLine = true,
        isError = !fileValid,
        supportingText = { if (!fileValid) Text("The file must end with .md.") },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "When a file is linked, its content is read again on every send, replacing the text above. " +
            "Edit the file in any editor and the next send uses the new text. " +
            "If the file cannot be read, the text above is used instead and an error is shown.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCreateTemplate) { Text("Create template") }
        OutlinedButton(enabled = file.isNotBlank(), onClick = onOpenFile) { Text("Open file") }
    }
    if (fileError != null) {
        Text(fileError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
