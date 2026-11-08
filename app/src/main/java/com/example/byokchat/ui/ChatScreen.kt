package com.example.byokchat.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.byokchat.container
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.images
import com.example.byokchat.util.TextUtil
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private data class RegenPrompt(val turnId: String, val newText: String?, val later: Int)
private data class Match(val messageId: String, val occurrence: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    focusMessageId: String? = null,
    initialQuery: String? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val vm: ChatViewModel = viewModel(factory = viewModelFactory {
        initializer { ChatViewModel(container, conversationId) }
    })
    val conv by vm.conversation.collectAsState()
    val messages by vm.messages.collectAsState()
    val generating by vm.generating.collectAsState()
    val providers by vm.providers.collectAsState()
    val stats by vm.tokenStats.collectAsState()
    val workspaceOk by container.fs.accessible.collectAsState()
    val toolPrefs by container.toolPrefs.prefs.collectAsState()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val pickFolder = rememberFolderPicker()

    var showModelPicker by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditState?>(null) }
    var regenPrompt by remember { mutableStateOf<RegenPrompt?>(null) }
    var deleteConfirm by remember { mutableStateOf<MessageEntity?>(null) }

    // ---------- Tìm trong cuộc trò chuyện ----------
    var searchOpen by remember { mutableStateOf(initialQuery != null) }
    var query by remember { mutableStateOf(initialQuery.orEmpty()) }
    var currentIdx by remember { mutableIntStateOf(0) }
    var focusApplied by remember { mutableStateOf(focusMessageId == null) }
    val searchFocus = remember { FocusRequester() }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) vm.addImages(uris)
    }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) vm.addImages(listOf(u))
        cameraUri = null
    }
    fun launchCamera() {
        try {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(context, "Không mở được máy ảnh.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                is ChatEvent.Deleted -> scope.launch {
                    val r = snackbar.showSnackbar("Đã xóa lượt hội thoại", actionLabel = "Hoàn tác", duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.undoDelete(ev.backup)
                }
                is ChatEvent.OpenConversation -> onOpenConversation(ev.id)
                is ChatEvent.Error -> Toast.makeText(context, ev.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun requestRegen(turnId: String, newText: String?) {
        scope.launch {
            val later = vm.laterTurnCount(turnId)
            if (later > 0) regenPrompt = RegenPrompt(turnId, newText, later) else vm.regenerateHere(turnId, newText)
        }
    }

    val actions = remember {
        Actions(
            copy = { m ->
                clipboard.setText(AnnotatedString(m.text))
                Toast.makeText(context, "Đã sao chép", Toast.LENGTH_SHORT).show()
            },
            edit = { m -> editing = EditState(m.id, m.turnId, m.text, regen = false, allowBlank = m.role == "user" && m.images().isNotEmpty()) },
            editRegen = { m -> editing = EditState(m.id, m.turnId, m.text, regen = true, allowBlank = m.images().isNotEmpty()) },
            regen = { m -> requestRegen(m.turnId, null) },
            delete = { m -> vm.deleteTurn(m.turnId) },
            branch = { m -> vm.branchAt(m.id) },
        )
    }

    val chrono = remember(messages) {
        messages.filter { m ->
            m.role != "tool" && !(m.role == "assistant" && m.text.isBlank() && m.status == "done" && m.toolCallsJson == null)
        }
    }
    val visible = remember(chrono) { chrono.asReversed() }
    val toolResults = remember(messages) {
        messages.filter { it.role == "tool" && it.toolCallId != null }.associateBy { it.toolCallId!! }
    }
    val matches = remember(chrono, query, searchOpen) {
        if (!searchOpen || query.isBlank()) emptyList()
        else chrono.flatMap { m -> TextUtil.findAll(m.text, query).indices.map { Match(m.id, it) } }
    }
    LaunchedEffect(query, searchOpen) { if (focusApplied) currentIdx = 0 }
    LaunchedEffect(matches) {
        if (!focusApplied && matches.isNotEmpty()) {
            val i = matches.indexOfFirst { it.messageId == focusMessageId }
            if (i >= 0) currentIdx = i
            focusApplied = true
        }
        if (currentIdx >= matches.size) currentIdx = 0
    }
    LaunchedEffect(currentIdx, matches) {
        val mm = matches.getOrNull(currentIdx) ?: return@LaunchedEffect
        val idx = visible.indexOfFirst { it.id == mm.messageId }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }
    LaunchedEffect(searchOpen) { if (searchOpen) runCatching { searchFocus.requestFocus() } }

    val provider = providers.firstOrNull { it.id == conv?.providerId }
    val modelLabel = buildString {
        append(if (provider == null) "Chưa chọn nhà cung cấp" else provider.name + " · " + (conv?.model?.ifBlank { "chưa chọn mô hình" } ?: ""))
        stats?.let {
            append(" · ≈").append(formatTokens(it.used)).append("/").append(formatTokens(it.limit))
            if (it.droppedTurns > 0) append(" (cắt ${it.droppedTurns} lượt cũ)")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (searchOpen) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { searchOpen = false; query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Đóng tìm kiếm")
                        }
                    },
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Tìm trong cuộc trò chuyện") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        )
                    },
                    actions = {
                        if (query.isNotBlank()) {
                            Text(if (matches.isEmpty()) "0/0" else "${currentIdx + 1}/${matches.size}", style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(
                            enabled = matches.isNotEmpty(),
                            onClick = { currentIdx = if (currentIdx <= 0) matches.size - 1 else currentIdx - 1 },
                        ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Kết quả trước") }
                        IconButton(
                            enabled = matches.isNotEmpty(),
                            onClick = { currentIdx = if (currentIdx >= matches.size - 1) 0 else currentIdx + 1 },
                        ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Kết quả sau") }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(conv?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(modelLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                    },
                    actions = {
                        IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Search, contentDescription = "Tìm kiếm") }
                        IconButton(onClick = { showModelPicker = true }) { Icon(Icons.Default.Build, contentDescription = "Chọn mô hình") }
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Thêm") }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(text = { Text("Đổi tên") }, onClick = { showMenu = false; showRename = true })
                                DropdownMenuItem(text = { Text("Prompt hệ thống") }, onClick = { showMenu = false; showPrompt = true })
                                DropdownMenuItem(text = { Text("Cài đặt") }, onClick = { showMenu = false; onOpenSettings() })
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val showBanner = remember(toolPrefs) { container.registry.hasEnabledWorkspaceTools() } && !workspaceOk
            if (showBanner) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Chưa chọn thư mục làm việc (hoặc đã mất quyền truy cập), nên các công cụ tệp đang bị chặn. Các công cụ khác vẫn dùng bình thường.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = pickFolder) { Text("Chọn thư mục") }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (visible.isEmpty()) {
                    Text(
                        "Hãy nhập câu hỏi đầu tiên của bạn bên dưới.",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    reverseLayout = true, // tin mới nhất ở dưới cùng, tự bám đáy khi AI đang viết
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(visible, key = { it.id }) { m ->
                        val cur = matches.getOrNull(currentIdx)
                        val hl = if (searchOpen && query.isNotBlank()) Highlight(query, if (cur?.messageId == m.id) cur.occurrence else -1) else null
                        val edit = editing?.takeIf { it.messageId == m.id }
                        if (m.role == "user") {
                            UserMessage(
                                m, generating, hl, edit, actions,
                                onEditChange = { t -> editing = editing?.copy(text = t) },
                                onEditSave = { st ->
                                    editing = null
                                    if (st.regen) requestRegen(st.turnId, st.text) else vm.saveEdit(st.messageId, st.text)
                                },
                                onEditCancel = { editing = null },
                            )
                        } else {
                            AssistantMessage(
                                m, generating, hl, edit, actions, toolResults,
                                onEditChange = { t -> editing = editing?.copy(text = t) },
                                onEditSave = { st -> editing = null; vm.saveEdit(st.messageId, st.text) },
                                onEditCancel = { editing = null },
                            )
                        }
                    }
                }
            }
            InputBar(
                draft = vm.draft,
                onDraft = { vm.draft = it },
                images = vm.pendingImages,
                onRemoveImage = vm::removePendingImage,
                generating = generating,
                onSend = vm::send,
                onStop = vm::stop,
                onPickGallery = { pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickCamera = { launchCamera() },
            )
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            providers = providers,
            providerId = conv?.providerId ?: "",
            model = conv?.model ?: "",
            onConfirm = { pid, model -> vm.setModel(pid, model); showModelPicker = false },
            onDismiss = { showModelPicker = false },
        )
    }
    if (showPrompt) {
        TextEditDialog(
            title = "Prompt hệ thống",
            initial = conv?.systemPrompt ?: "",
            multiline = true,
            hint = "Hướng dẫn cố định cho AI trong cuộc trò chuyện này (ví dụ: vai trò, giọng điệu, ngôn ngữ trả lời).",
            onConfirm = { vm.setSystemPrompt(it); showPrompt = false },
            onDismiss = { showPrompt = false },
        )
    }
    if (showRename) {
        TextEditDialog(
            title = "Đổi tên cuộc trò chuyện",
            initial = conv?.title ?: "",
            onConfirm = { vm.rename(it); showRename = false },
            onDismiss = { showRename = false },
        )
    }
    regenPrompt?.let { rp ->
        AlertDialog(
            onDismissRequest = { regenPrompt = null },
            title = { Text("Tạo nhánh khác?") },
            text = {
                Text(
                    "Phía sau lượt này còn ${rp.later} lượt khác.\n\n" +
                        "• Có: tạo cuộc trò chuyện mới và tạo lại câu trả lời ở đó. Cuộc hiện tại giữ nguyên.\n" +
                        "• Không: tạo lại ngay tại đây, các lượt phía sau sẽ bị xóa."
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { regenPrompt = null }) { Text("Hủy") }
                    TextButton(onClick = { regenPrompt = null; vm.regenerateHere(rp.turnId, rp.newText) }) { Text("Không") }
                    TextButton(onClick = { regenPrompt = null; vm.regenerateInBranch(rp.turnId, rp.newText) }) { Text("Có") }
                }
            },
        )
    }
}

private fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    n >= 1000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000.0).replace(".0k", "k")
    else -> n.toString()
}
