package com.example.byokchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.byokchat.AppContainer
import com.example.byokchat.container
import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.displayTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ConversationListViewModel(private val app: AppContainer) : ViewModel() {
    /** Conversations waiting for their "Undo" time to run out: hidden from the list, deleted for good afterwards. */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    /** Deleting must finish even if this screen goes away, so it does not use viewModelScope. */
    private val commitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drawerOpen = MutableStateFlow(false)

    /** Only conversations that have at least one message; empty drafts and pending deletions are hidden. */
    val conversations: StateFlow<List<ConversationEntity>> =
        combine(app.db.conversations().observeNonEmpty(), hidden) { list, h -> list.filter { it.id !in h } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Preview line (start of the last message) per conversation. Only observed while the drawer is open. */
    val previews: StateFlow<Map<String, String>> = drawerOpen
        .flatMapLatest { isOpen ->
            if (isOpen) app.db.messages().observeLastMessages().map { rows -> rows.associate { it.conversationId to previewOf(it.text) } }
            else emptyFlow()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Search results (grouped by conversation), computed shortly after the user stops typing. */
    val search: StateFlow<SearchState> = _query
        .debounce(250)
        .mapLatest { q ->
            if (q.isBlank()) SearchState()
            else SearchState(q, withContext(Dispatchers.Default) {
                searchAllChats(app.db.conversations().observeAllOnce(), { app.db.messages().chatMessagesOf(it) }, q)
            })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchState())

    fun setDrawerOpen(isOpen: Boolean) { drawerOpen.value = isOpen }
    fun setQuery(q: String) { _query.value = q }

    fun rename(id: String, title: String) = viewModelScope.launch { app.conversations.rename(id, title) }
    fun setPinned(id: String, pinned: Boolean) = viewModelScope.launch { app.db.conversations().setPinned(id, pinned) }

    fun hide(ids: Collection<String>) { hidden.value = hidden.value + ids }
    fun unhide(ids: Collection<String>) { hidden.value = hidden.value - ids.toSet() }

    /** Deletes for good (messages and their pictures included) and stops hiding them. */
    fun commitDelete(ids: Collection<String>) {
        val list = ids.toList()
        commitScope.launch {
            list.forEach { app.conversations.delete(it) }
            hidden.value = hidden.value - list.toSet()
        }
    }
}

/** Content of the navigation drawer: search, the list of conversations (the chat itself is the home screen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDrawerContent(
    isOpen: Boolean,
    currentId: String?,
    onSelect: (String) -> Unit,
    onOpenResult: (conversationId: String, messageId: String?, query: String?) -> Unit,
    onNewChat: () -> Unit,
    onDeleted: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val container = LocalContext.current.container
    val vm: ConversationListViewModel = viewModel(factory = viewModelFactory {
        initializer { ConversationListViewModel(container) }
    })
    val conversations by vm.conversations.collectAsState()
    val previews by vm.previews.collectAsState()
    val query by vm.query.collectAsState()
    val search by vm.search.collectAsState()
    val active by container.engine.activeConversations.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val groups = remember(conversations) { groupConversations(conversations, System.currentTimeMillis()) }
    val titles = remember(conversations) { conversations.associate { it.id to displayTitle(it.title) } }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    LaunchedEffect(isOpen) {
        vm.setDrawerOpen(isOpen)
        if (!isOpen) exitSelection()
    }
    BackHandler(enabled = selecting) { exitSelection() }

    /** Hides the conversations at once and deletes them for good when the "Undo" bar goes away. */
    fun deleteWithUndo(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.forEach { container.engine.stop(it) }
        vm.hide(ids)
        ids.forEach(onDeleted)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            var undone = false
            try {
                val result = snackbar.showSnackbar(
                    message = if (ids.size == 1) "Conversation deleted" else "${ids.size} conversations deleted",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short,
                )
                undone = result == SnackbarResult.ActionPerformed
            } finally {
                // Also runs if this screen disappears first, so nothing stays hidden forever.
                if (undone) vm.unhide(ids) else vm.commitDelete(ids)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { exitSelection() }) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") }
                    Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { selected = conversations.map { it.id }.toSet() }) {
                        Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                    }
                    IconButton(
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            val ids = selected.toList()
                            exitSelection()
                            deleteWithUndo(ids)
                        },
                    ) { Icon(Icons.Default.Delete, contentDescription = "Delete selected") }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Conversations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.setQuery(it) },
                    singleLine = true,
                    shape = CircleShape,
                    placeholder = { Text("Search chats") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (query.isBlank()) {
                    FilledTonalButton(
                        onClick = onNewChat,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("New chat", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            if (query.isNotBlank() && !selecting) {
                val liveIds = remember(conversations) { conversations.map { it.id }.toSet() }
                val upToDate = search.query == query
                val results = remember(search, liveIds) { search.groups.filter { it.conversationId in liveIds } }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !upToDate -> CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(24.dp).size(24.dp), strokeWidth = 2.dp)
                        results.isEmpty() -> Text(
                            "No results found.",
                            modifier = Modifier.align(Alignment.TopCenter).padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(results, key = { it.conversationId }) { g ->
                                SearchGroupItem(g) { messageId ->
                                    keyboard?.hide()
                                    // A title-only match opens the chat plainly; a message match also opens the in-chat search.
                                    onOpenResult(g.conversationId, messageId, if (messageId == null) null else query)
                                    vm.setQuery("")
                                }
                            }
                        }
                    }
                }
            } else {
                if (conversations.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No conversations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    groups.forEach { (bucket, list) ->
                        item(key = "h_" + bucket.name) {
                            Text(
                                bucket.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(list, key = { it.id }) { c ->
                            ConversationRow(
                                title = displayTitle(c.title),
                                parentTitle = c.parentId?.let { titles[it] ?: "" },
                                preview = previews[c.id],
                                pinned = c.pinned,
                                isCurrent = c.id == currentId,
                                working = c.id in active,
                                selecting = selecting,
                                checked = c.id in selected,
                                onClick = {
                                    if (selecting) {
                                        selected = if (c.id in selected) selected - c.id else selected + c.id
                                    } else {
                                        onSelect(c.id)
                                    }
                                },
                                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onRename = { renameTarget = c },
                                onTogglePin = { vm.setPinned(c.id, !c.pinned) },
                                onStartSelecting = { selecting = true; selected = setOf(c.id) },
                                onDelete = { deleteWithUndo(listOf(c.id)) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
            // Fixed footer: always visible at the bottom of the drawer, never
            // scrolls with the conversation list. The header Settings icon stays.
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable { onSettings() },
                headlineContent = { Text("Settings") },
                leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    renameTarget?.let { c ->
        TextEditDialog(
            title = "Rename conversation",
            initial = displayTitle(c.title),
            onConfirm = { vm.rename(c.id, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ConversationRow(
    title: String,
    parentTitle: String?,
    preview: String?,
    pinned: Boolean,
    isCurrent: Boolean,
    working: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onStartSelecting: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    // Swipe to the right deletes (with Undo). Swiping left is left alone because that closes the drawer.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onDelete()
                true
            } else false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = !selecting,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Box {
            ListItem(
                modifier = Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        if (!selecting) {
                            onLongClick()
                            menu = true
                        }
                    },
                ),
                colors = ListItemDefaults.colors(
                    containerColor = if (isCurrent || checked) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                leadingContent = if (selecting) {
                    { Checkbox(checked = checked, onCheckedChange = null) }
                } else null,
                headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        if (parentTitle != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CallSplit, contentDescription = "Branch", modifier = Modifier.size(14.dp))
                                Text(
                                    " " + if (parentTitle.isNotEmpty()) "Branch of $parentTitle" else "Branch",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (!preview.isNullOrEmpty()) {
                            Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        if (pinned) Icon(Icons.Default.PushPin, contentDescription = "Pinned", modifier = Modifier.size(16.dp).padding(start = 2.dp))
                        if (!selecting) {
                            // The menu must live inside a Box around its button: DropdownMenu
                            // anchors to its parent layout, and the row is full-width, so a menu
                            // placed at row level opens at the left edge instead of the button.
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                                    DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin") }, onClick = { menu = false; onTogglePin() })
                                    DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onStartSelecting() })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

/** One conversation in the search results: its title, then up to a few matching messages. */
@Composable
private fun SearchGroupItem(group: SearchGroup, onOpen: (messageId: String?) -> Unit) {
    val first = group.hits.firstOrNull()?.messageId
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable { onOpen(first) },
            headlineContent = { Text(group.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (group.totalHits > 0) {
                { Text(if (group.totalHits == 1) "1 match" else "${group.totalHits} matches") }
            } else null,
        )
        group.hits.forEach { hit ->
            Text(
                hit.snippet.toAnnotated(prefix = if (hit.role == "user") "You: " else "AI: "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(hit.messageId) }.padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            )
        }
        if (group.totalHits > group.hits.size) {
            Text(
                "+${group.totalHits - group.hits.size} more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpen(first) }.padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            )
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

private fun Snippet.toAnnotated(prefix: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(prefix) }
    val offset = length
    append(text)
    highlights.forEach { r ->
        addStyle(SpanStyle(background = Color(0x99FFEB3B), color = Color.Black), offset + r.first, offset + r.last + 1)
    }
}
