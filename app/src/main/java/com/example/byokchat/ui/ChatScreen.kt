package com.example.byokchat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.byokchat.container
import com.example.byokchat.chat.TurnNumbers
import com.example.byokchat.data.displayTitle
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
    /** Set when this conversation was just created as a branch of another one. */
    branchFrom: String? = null,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBranch: (String) -> Unit,
    onOpenOriginal: (String) -> Unit,
    onBranchNoticeShown: () -> Unit,
    onOnboarding: () -> Unit,
    /** Opens the settings of a provider (null = the list of providers). */
    onOpenProvider: (String?) -> Unit,
    /** Starts a fresh chat (used by the "context almost full" hint). */
    onNewChat: () -> Unit,
    /** Opens the "Preview request" Inspector for this chat. */
    onOpenInspector: () -> Unit,
    /** Opens the Messages screen (chat history) for this chat. */
    onOpenMessages: () -> Unit,
    /** Opens the request log. */
    onOpenRequestLog: () -> Unit,
    /** Opens the Request panel for this chat. */
    onOpenRequestPanel: () -> Unit,
    /** Opens the Storage settings (used by the "no data storage" banner). */
    onOpenStorage: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val vm: ChatViewModel = viewModel(key = conversationId, factory = viewModelFactory {
        initializer { ChatViewModel(container, conversationId) }
    })
    // Đợt fix 11/11/2026 (I2): đồng bộ lại history.md mỗi lần màn chat quay
    // lại foreground — sửa file ngoài khi chat đang mở rồi quay lại cũng
    // được parse (hoặc báo "sửa ngoài không có hiệu lực" khi AI đang trả
    // lời), thay vì chỉ đồng bộ một lần lúc mở chat (init của ViewModel).
    // Chạy lại khi không có gì đổi thì hash làm no-op; init + resume trùng
    // nhau thì mutex trong syncHistoryOnOpen bỏ qua lần sau. Lấy lifecycle
    // từ Activity (không thêm dependency lifecycle-runtime-compose).
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { vm.syncHistoryOnOpen() }
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    val conv by vm.conversation.collectAsState()
    val messages by vm.messages.collectAsState()
    val generating by vm.generating.collectAsState()
    // Đợt bỏ provider 12/11/2026: các lượt đã Retry một lần mà vẫn lỗi —
    // bong bóng lỗi của lượt đó thêm nút "Start a new chat".
    val retriedTurns by vm.retriedTurns.collectAsState()
    val providers by vm.providers.collectAsState()
    val currentProviderId by vm.currentModelProviderId.collectAsState()
    val currentModelName by vm.currentModel.collectAsState()
    val modelsRefreshing by vm.modelsRefreshing.collectAsState()
    val workspaceOk by container.fs.accessible.collectAsState()
    val sessionStorageUri by container.settings.sessionStorageUri.collectAsState()
    val toolPrefs by container.toolPrefs.prefs.collectAsState()
    // Text of the answer being streamed. Deliberately NOT read here (no `by`): only the streaming message reads it,
    // so only that message recomposes while the AI is typing.
    val liveText = container.engine.liveText.collectAsState()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    // Chỉ số 0 là tin mới nhất (đáy màn hình); cuộn lên quá 1 mục thì hiện nút "xuống cuối".
    val showScrollDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }
    val pickFolder = rememberFolderPicker()

    var showModelPicker by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    // Số liệu «Context window» chỉ tính khi hộp thoại được mở (xem ChatViewModel.computeTokenStats).
    var memoryStats by remember { mutableStateOf<TokenStats?>(null) }
    LaunchedEffect(showMemory, messages) {
        if (showMemory) memoryStats = vm.computeTokenStats()
    }
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
    val inputFocus = remember { FocusRequester() }

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
            Toast.makeText(context, "Couldn't open the camera.", Toast.LENGTH_SHORT).show()
        }
    }

    // Xin quyền thông báo đúng một lần, vào lần đầu người dùng gửi tin. Bị từ chối thì im lặng, không hỏi lại.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // Dù được cấp hay bị từ chối đều không làm gì thêm (cờ "đã hỏi" đã được ghi nên sẽ không hỏi lại).
    }
    fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (container.settings.notifPermissionAsked.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        container.settings.setNotifPermissionAsked(true)
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                is ChatEvent.Deleted -> scope.launch {
                    val r = snackbar.showSnackbar("Turn deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.undoDelete(ev.backup)
                }
                is ChatEvent.OpenBranch -> onOpenBranch(ev.id)
                is ChatEvent.Error -> Toast.makeText(context, ev.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Mở ModelPickerSheet thì làm mới danh sách mô hình từ máy chủ của từng provider.
    LaunchedEffect(showModelPicker) {
        if (showModelPicker) vm.refreshModels()
    }

    // Just arrived from "Branch": tell the user what happened and offer a way back to the original chat.
    val noticeFrom = remember { branchFrom }
    LaunchedEffect(Unit) {
        val from = noticeFrom ?: return@LaunchedEffect
        onBranchNoticeShown()
        val r = snackbar.showSnackbar("Branch created", actionLabel = "Original chat", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) onOpenOriginal(from)
    }

    // Back closes the in-chat search first (otherwise the host opens the drawer)
    BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }

    fun requestRegen(turnId: String, newText: String?) {
        scope.launch {
            val later = vm.laterTurnCount(turnId)
            if (later > 0) regenPrompt = RegenPrompt(turnId, newText, later) else vm.regenerateHere(turnId, newText)
        }
    }

    val actions = remember {
        Actions(
            copy = { text ->
                clipboard.setText(AnnotatedString(text))
                // Android 13+ already shows its own "copied" confirmation, so only older versions need a toast.
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
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
    // Đợt 4: nhãn lượt ổn định (T1, T2…) cho bong bóng user, như FPilot.
    val turnLabels = remember(messages) { TurnNumbers.effectiveTurnNumbers(messages) }
    val visible = remember(chrono) { chrono.asReversed() }
    // Only the very last answer of the chat gets the Copy / Regenerate / Branch row; older answers use press-and-hold.
    val lastAnswerId = remember(chrono) {
        chrono.lastOrNull()?.takeIf { it.role == "assistant" && it.text.isNotEmpty() && it.status == "done" }?.id
    }
    // Turns that have an AI message on screen. A turn without one gets "Regenerate" in your message's menu.
    val turnsWithAnswer = remember(chrono) { chrono.filter { it.role == "assistant" }.map { it.turnId }.toSet() }
    val toolResults = remember(messages) {
        ToolResults(messages.filter { it.role == "tool" && it.toolCallId != null }.associateBy { it.toolCallId!! })
    }
    // Bỏ dấu một lần: câu tìm và từng tin (chỉ khi đang mở thanh tìm), thay vì bỏ dấu lại mỗi lần gõ.
    val foldedQuery = remember(query) { TextUtil.fold(query) }
    val foldedTexts = remember(chrono, searchOpen) {
        if (searchOpen) chrono.associate { it.id to TextUtil.fold(it.text) } else emptyMap()
    }
    val matches = remember(chrono, query, searchOpen, foldedQuery, foldedTexts) {
        if (!searchOpen || query.isBlank()) emptyList()
        else chrono.flatMap { m ->
            TextUtil.findAllFolded(foldedTexts[m.id].orEmpty(), foldedQuery).indices.map { Match(m.id, it) }
        }
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

    fun nextMatch() { if (matches.isNotEmpty()) currentIdx = if (currentIdx >= matches.size - 1) 0 else currentIdx + 1 }
    fun prevMatch() { if (matches.isNotEmpty()) currentIdx = if (currentIdx <= 0) matches.size - 1 else currentIdx - 1 }

    // After you send, jump to the newest message even if you had scrolled up, and keep following the reply until
    // you drag the list yourself or the AI is done.
    var followNewest by remember { mutableStateOf(false) }
    val newestId = visible.firstOrNull()?.id
    LaunchedEffect(newestId) { if (followNewest && newestId != null) listState.animateScrollToItem(0) }
    LaunchedEffect(generating) { if (!generating) followNewest = false }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) followNewest = false }
    }

    // Chip chọn mô hình và bảng chọn mô hình đều theo lựa chọn toàn cục, không theo từng cuộc nữa.
    val provider = providers.firstOrNull { it.id == currentProviderId }
    val modelName = currentModelName.orEmpty()
    val modelMissing = provider == null || modelName.isBlank()
    val chipLabel = when {
        provider == null || modelName.isBlank() -> "Select model"
        providers.size >= 2 -> provider.name + " · " + modelName
        else -> modelName
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // The input bar lives in bottomBar so snackbars (e.g. "Undo") appear above it instead of covering it.
        bottomBar = {
            InputBar(
                draft = vm.draft,
                onDraft = { vm.draft = it },
                images = vm.pendingImages,
                onRemoveImage = vm::removePendingImage,
                generating = generating,
                onSend = {
                    if (!generating && (vm.draft.isNotBlank() || vm.pendingImages.isNotEmpty())) followNewest = true
                    askNotificationPermissionOnce()
                    vm.send()
                },
                onStop = vm::stop,
                onPickGallery = { pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickCamera = { launchCamera() },
                inputFocus = inputFocus,
                modelLabel = chipLabel,
                modelMissing = modelMissing,
                onPickModel = { showModelPicker = true },
            )
        },
        topBar = {
            if (searchOpen) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { searchOpen = false; query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    },
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search in this chat") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            // Enter jumps to the next match (and keeps the keyboard open)
                            keyboardActions = KeyboardActions(onSearch = { nextMatch() }),
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
                            onClick = { prevMatch() },
                        ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous result") }
                        IconButton(
                            enabled = matches.isNotEmpty(),
                            onClick = { nextMatch() },
                        ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next result") }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(displayTitle(conv?.title ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) { Icon(Icons.Default.Menu, contentDescription = "Conversations") }
                    },
                    actions = {
                        // Đợt fix 11/11/2026 (H2): Request panel và New chat
                        // thành nút trên thanh trên cùng (thứ tự: panel – new
                        // chat – menu); Search chuyển vào trong menu.
                        IconButton(onClick = onOpenRequestPanel) { Icon(Icons.Default.Tune, contentDescription = "Request panel") }
                        IconButton(onClick = onNewChat) { Icon(Icons.Default.Edit, contentDescription = "New chat") }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(text = { Text("Search") }, onClick = { showMenu = false; searchOpen = true })
                                DropdownMenuItem(text = { Text("Context window") }, onClick = { showMenu = false; showMemory = true })
                                DropdownMenuItem(text = { Text("Preview request") }, onClick = { showMenu = false; onOpenInspector() })
                                DropdownMenuItem(text = { Text("Log") }, onClick = { showMenu = false; onOpenRequestLog() })
                                DropdownMenuItem(text = { Text("Settings") }, onClick = { showMenu = false; onOpenSettings() })
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val showBanner = remember(toolPrefs) { container.registry.hasEnabledWorkspaceTools() } && !workspaceOk
            if (showBanner) {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "No workspace folder is chosen (or access was lost), so file tools are blocked. Other tools still work.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = pickFolder) { Text("Choose folder") }
                    }
                }
            }
            // Đợt fix 11/11/2026 (C2): chưa chọn data storage thì không có chỗ
            // lưu session — chat mới không có history.md/history.meta.json,
            // không sửa ngoài app hay đồng bộ hai chiều được. Chat vẫn chạy,
            // chỉ báo cho user biết hệ quả, không tự lấy workspace làm thay.
            if (sessionStorageUri == null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "No data storage folder is chosen, so this chat has no history file and edits outside the app won't sync. Chat still works.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenStorage) { Text("Choose folder") }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (visible.isEmpty()) {
                    ChatEmptyState(
                        noProvider = providers.isEmpty(),
                        noModel = providers.isNotEmpty() && conv != null && modelName.isBlank(),
                        onConnect = { onOpenProvider(null) },
                        onPickModel = { showModelPicker = true },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    reverseLayout = true, // tin mới nhất ở dưới cùng, tự bám đáy khi AI đang viết
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(visible, key = { it.id }, contentType = { it.role }) { m ->
                        val cur = matches.getOrNull(currentIdx)
                        val hl = if (searchOpen && query.isNotBlank()) Highlight(query, if (cur?.messageId == m.id) cur.occurrence else -1) else null
                        val edit = editing?.takeIf { it.messageId == m.id }
                        if (m.role == "user") {
                            UserMessage(
                                m, generating, hl, edit, actions,
                                canRegen = m.turnId !in turnsWithAnswer,
                                turnLabel = turnLabels[m.turnId]?.let { "T$it" },
                                onEditChange = { t -> editing = editing?.copy(text = t) },
                                onEditSave = { st ->
                                    editing = null
                                    if (st.regen) requestRegen(st.turnId, st.text) else vm.saveEdit(st.messageId, st.text)
                                },
                                onEditCancel = { editing = null },
                            )
                        } else {
                            val fix = if (m.status == "error") errorFix(m.error, provider, modelName) else null
                            AssistantMessage(
                                m, generating, hl, edit, actions, toolResults,
                                showActions = m.id == lastAnswerId,
                                errorFix = fix,
                                onErrorFix = { f ->
                                    when (f) {
                                        ErrorFix.PROVIDER_SETTINGS -> onOpenProvider(provider?.id)
                                        ErrorFix.PICK_MODEL -> showModelPicker = true
                                    }
                                },
                                // Đợt bỏ provider 12/11/2026: giải thích
                                // nguyên nhân lỗi + Retry sửa nguyên nhân rồi
                                // gửi lại; Retry vẫn lỗi thì thêm nút mở cuộc mới.
                                errorExplanation = if (m.status == "error") explainApiError(m.error, provider) else null,
                                onRetryError = { vm.retryTurn(m.turnId) },
                                showNewChat = m.status == "error" && m.turnId in retriedTurns,
                                onNewChat = onNewChat,
                                liveText = if (m.status == "streaming") liveText.value[m.id] else null,
                                onEditChange = { t -> editing = editing?.copy(text = t) },
                                onEditSave = { st -> editing = null; vm.saveEdit(st.messageId, st.text) },
                                onEditCancel = { editing = null },
                            )
                        }
                    }
                }
                if (showScrollDown) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to latest")
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = providers,
            providerId = currentProviderId ?: "",
            model = currentModelName ?: "",
            refreshing = modelsRefreshing,
            onSelect = { pid, model -> vm.setModel(pid, model); showModelPicker = false },
            onTogglePin = { pid, model -> vm.togglePin(pid, model) },
            onConnect = { showModelPicker = false; onOnboarding() },
            onDismiss = { showModelPicker = false },
        )
    }
    if (showMemory) memoryStats?.let { st ->
        ChatMemoryDialog(
            used = st.used,
            limit = st.limit,
            dropped = st.droppedTurns,
            open = true,
            onDismiss = { showMemory = false; memoryStats = null },
            onNewChat = onNewChat,
            onOpenMessages = onOpenMessages,
        )
    }
    regenPrompt?.let { rp ->
        AlertDialog(
            onDismissRequest = { regenPrompt = null },
            title = { Text("Create another branch?") },
            text = {
                Text(
                    "There are ${rp.later} more turn(s) after this one.\n\n" +
                        "\u2022 Yes: start a new chat and regenerate the answer there. This chat stays as it is.\n" +
                        "\u2022 No: regenerate right here; the turns after this one will be deleted."
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { regenPrompt = null }) { Text("Cancel") }
                    TextButton(onClick = { regenPrompt = null; vm.regenerateHere(rp.turnId, rp.newText) }) { Text("No") }
                    TextButton(onClick = { regenPrompt = null; vm.regenerateInBranch(rp.turnId, rp.newText) }) { Text("Yes") }
                }
            },
        )
    }
}

internal fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    n >= 1000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000.0).replace(".0k", "k")
    else -> n.toString()
}
