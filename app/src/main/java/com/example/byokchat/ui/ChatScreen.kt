package com.example.byokchat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.byokchat.container
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
) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val vm: ChatViewModel = viewModel(key = conversationId, factory = viewModelFactory {
        initializer { ChatViewModel(container, conversationId) }
    })
    val conv by vm.conversation.collectAsState()
    val messages by vm.messages.collectAsState()
    val generating by vm.generating.collectAsState()
    val providers by vm.providers.collectAsState()
    val stats by vm.tokenStats.collectAsState()
    val workspaceOk by container.fs.accessible.collectAsState()
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
    val visible = remember(chrono) { chrono.asReversed() }
    // Only the very last answer of the chat gets the Copy / Regenerate / Branch row; older answers use press-and-hold.
    val lastAnswerId = remember(chrono) {
        chrono.lastOrNull()?.takeIf { it.role == "assistant" && it.text.isNotEmpty() && it.status == "done" }?.id
    }
    // Turns that have an AI message on screen. A turn without one gets "Regenerate" in your message's menu.
    val turnsWithAnswer = remember(chrono) { chrono.filter { it.role == "assistant" }.map { it.turnId }.toSet() }
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

    val provider = providers.firstOrNull { it.id == conv?.providerId }
    val modelName = conv?.model.orEmpty()
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
                        stats?.let { ContextRing(it.used, it.limit, it.droppedTurns, onNewChat) }
                        IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Search, contentDescription = "Search") }
                        val hasPrompt = conv?.systemPrompt?.isNotBlank() == true
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                // A small dot on the menu button tells you this chat has a system prompt.
                                BadgedBox(badge = { if (hasPrompt) Badge() }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = if (hasPrompt) "More (this chat has a system prompt)" else "More")
                                }
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { showMenu = false; showRename = true })
                                DropdownMenuItem(
                                    text = { Text("System prompt") },
                                    trailingIcon = if (hasPrompt) {
                                        { Icon(Icons.Default.Check, contentDescription = "Has a system prompt", modifier = Modifier.size(18.dp)) }
                                    } else null,
                                    onClick = { showMenu = false; showPrompt = true },
                                )
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
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (visible.isEmpty()) {
                    ChatEmptyState(
                        noProvider = providers.isEmpty(),
                        noModel = providers.isNotEmpty() && conv != null && modelName.isBlank(),
                        onConnect = onOnboarding,
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
                    items(visible, key = { it.id }) { m ->
                        val cur = matches.getOrNull(currentIdx)
                        val hl = if (searchOpen && query.isNotBlank()) Highlight(query, if (cur?.messageId == m.id) cur.occurrence else -1) else null
                        val edit = editing?.takeIf { it.messageId == m.id }
                        if (m.role == "user") {
                            UserMessage(
                                m, generating, hl, edit, actions,
                                canRegen = m.turnId !in turnsWithAnswer,
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
            providerId = conv?.providerId ?: "",
            model = conv?.model ?: "",
            onSelect = { pid, model -> vm.setModel(pid, model); showModelPicker = false },
            onTogglePin = { pid, model -> vm.togglePin(pid, model) },
            onConnect = { showModelPicker = false; onOnboarding() },
            onDismiss = { showModelPicker = false },
        )
    }
    if (showPrompt) {
        TextEditDialog(
            title = "System prompt",
            initial = conv?.systemPrompt ?: "",
            multiline = true,
            hint = "Fixed instructions for the AI in this chat (for example: role, tone, language of the replies).",
            onConfirm = { vm.setSystemPrompt(it); showPrompt = false },
            onDismiss = { showPrompt = false },
        )
    }
    if (showRename) {
        TextEditDialog(
            title = "Rename chat",
            initial = displayTitle(conv?.title ?: ""),
            onConfirm = { vm.rename(it); showRename = false },
            onDismiss = { showRename = false },
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
