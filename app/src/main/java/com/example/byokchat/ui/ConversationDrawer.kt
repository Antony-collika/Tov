package com.example.byokchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.unit.sp
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

    /** Only conversations that have at least one message; empty drafts, pending deletions and archived chats are hidden. */
    val conversations: StateFlow<List<ConversationEntity>> =
        combine(app.db.conversations().observeNonEmpty(), hidden) { list, h -> list.filter { it.id !in h && !it.archived } }
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
    fun setArchived(id: String, archived: Boolean) = viewModelScope.launch { app.db.conversations().setArchived(id, archived) }

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
    /** Which filter chip is active; only filters the list in memory, never the data. */
    var filter by remember { mutableStateOf(ConversationFilter.ALL) }

    /** The list as the active filter chip shows it. */
    val filtered = when (filter) {
        ConversationFilter.ALL -> conversations
        ConversationFilter.PINNED -> conversations.filter { it.pinned }
    }
    val groups = remember(filtered, filter) {
        groupConversations(filtered, System.currentTimeMillis(), pinnedSection = filter == ConversationFilter.PINNED)
    }
    val pinnedCount = remember(conversations) { conversations.count { it.pinned } }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    LaunchedEffect(isOpen) {
        vm.setDrawerOpen(isOpen)
        if (!isOpen) {
            exitSelection()
            // The drawer is a transient panel: reopening starts fresh (search cleared, filter reset).
            filter = ConversationFilter.ALL
            vm.setQuery("")
        }
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
                    IconButton(onClick = { selected = filtered.map { it.id }.toSet() }) {
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
                // Header, option B: a persistent search field + a small
                // gear button opening Settings. No "Conversations" title, no
                // New chat button up here — New chat lives in the split
                // bottom row. The field matches the 9:30 sample Duy picked:
                // 36dp high, light-gray fill (#F2F2F5, same gray as the row
                // tiles), no outline border. A plain Box + clickable
                // carries the × instead of an IconButton, because
                // IconButton forces a 48dp touch target and would stretch
                // the field.
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).height(36.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFFF2F2F5), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        BasicTextField(
                            value = query,
                            onValueChange = { vm.setQuery(it) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.sp,
                                color = Color(0xFF191C20),
                            ),
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (query.isEmpty()) {
                                        Text(
                                            "Search chats",
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                        if (query.isNotEmpty()) {
                            Box(
                                Modifier.size(36.dp).clickable { vm.setQuery("") },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
                // Filter chips, horizontally scrollable. Only two chips now: Archived
                // lives on the Backup screen instead. Hidden while searching: the
                // list below is replaced by the search results then.
                if (query.isBlank()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            label = "All chats ${conversations.size}",
                            selected = filter == ConversationFilter.ALL,
                            onClick = { filter = ConversationFilter.ALL },
                            icon = { tint -> ChatBubbleIcon(Modifier.size(18.dp), bubbleColor = tint) },
                        )
                        FilterChip(
                            label = "Pinned $pinnedCount",
                            selected = filter == ConversationFilter.PINNED,
                            onClick = { filter = ConversationFilter.PINNED },
                            icon = { tint -> Icon(Icons.Default.PushPin, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) },
                        )
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
                } else if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No pinned conversations.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                    groups.forEach { (bucket, list) ->
                        item(key = "h_" + bucket.name) {
                            Text(
                                bucket.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6B7280),
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                            )
                        }
                        list.forEachIndexed { index, c ->
                            item(key = c.id) {
                                ConversationRow(
                                    title = displayTitle(c.title),
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
                                    onArchive = { vm.setArchived(c.id, true) },
                                    onDelete = { deleteWithUndo(listOf(c.id)) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            // A thin divider after every row except the last row of its group.
                            if (index != list.lastIndex) {
                                item(key = "d_" + c.id) {
                                    HorizontalDivider(color = Color(0xFFF2F2F5))
                                }
                            }
                        }
                    }
                }
            }
            // Fixed footer: always visible at the bottom of the drawer, never
            // scrolls with the conversation list. Split in two halves —
            // Setting on the left, New Chat on the right — with a thin
            // vertical divider between them, on a white background.
            HorizontalDivider(color = Color(0xFFE8E8EC))
            Row(
                Modifier.fillMaxWidth().background(Color.White),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).clickable { onSettings() }.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        "Setting",
                        fontSize = 15.sp,
                        color = Color(0xFF191C20),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Box(Modifier.width(1.dp).height(24.dp).background(Color(0xFFE8E8EC)))
                Row(
                    Modifier.weight(1f).clickable { onNewChat() }.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        "New Chat",
                        fontSize = 15.sp,
                        color = Color(0xFF191C20),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
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

/** Which list filter chip is active. */
private enum class ConversationFilter { ALL, PINNED }

/**
 * Chat bubble: a thin-outlined oval with a small tail at the bottom-right,
 * drawn with Canvas (the app has no avatar for a chat session, so this
 * stands in for one). The outline colour follows its context: dark grey
 * in a row, the chip's text colour in a filter chip. No fill, no dots.
 * Shared with the Backup screen's archived list, so it is not private.
 */
@Composable
internal fun ChatBubbleIcon(
    modifier: Modifier = Modifier,
    bubbleColor: Color = Color(0xFF4B5563),
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.075f
        // Bubble body: a wide oval taking up the top of the box.
        val bodyHeight = h * 0.80f
        drawOval(
            color = bubbleColor,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(w - stroke, bodyHeight - stroke),
            style = Stroke(width = stroke),
        )
        // Small tail at the bottom-right: two strokes from the oval's lower
        // edge out to a point and back.
        val tail = Path().apply {
            moveTo(w * 0.60f, bodyHeight - stroke * 0.6f)
            lineTo(w * 0.88f, h - stroke / 2f)
            lineTo(w * 0.78f, bodyHeight - stroke * 1.4f)
        }
        drawPath(tail, bubbleColor, style = Stroke(width = stroke, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
}

/**
 * Pushpin, tilted, drawn with the same thin outline as the chat bubble:
 * a small round head at the top, a tapered shaft, and a point at the
 * bottom-left. No fill, no disc behind it.
 */
@Composable
private fun PinIcon(
    modifier: Modifier = Modifier,
    pinColor: Color = Color(0xFF4B5563),
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.075f
        // Round head at the top.
        drawCircle(
            color = pinColor,
            radius = w * 0.17f,
            center = Offset(w * 0.62f, h * 0.22f),
            style = Stroke(width = stroke),
        )
        // Tapered shaft: two strokes from the head down to one point.
        val shaft = Path().apply {
            moveTo(w * 0.50f, h * 0.34f)
            lineTo(w * 0.28f, h * 0.86f)
            moveTo(w * 0.72f, h * 0.32f)
            lineTo(w * 0.40f, h * 0.86f)
        }
        drawPath(shaft, pinColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

/** One filter chip above the conversation list ("All chats 12", "Pinned 3"): text only, no border, no background. The touch area (padding + clickable) is kept exactly as the old chip — only the visible frame is gone. */
@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit,
) {
    val contentColor = if (selected) Color(0xFFC2410C) else Color(0xFF33353A)
    Row(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(contentColor)
        Text(
            text = label,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    title: String,
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
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    // A flat row: no rounded corners, no swipe-to-delete (deleting is only
    // through the menu, with Undo). The open / selected row keeps the pale
    // orange background, as a plain rectangle.
    Box(
        modifier
            .background(if (isCurrent || checked) Color(0xFFFFF1E6) else Color.White)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (!selecting) {
                        onLongClick()
                        menu = true
                    }
                },
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = null)
            } else if (pinned) {
                PinIcon(Modifier.size(24.dp))
            } else {
                ChatBubbleIcon(Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF191C20),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!preview.isNullOrEmpty()) {
                    Text(
                        preview,
                        fontSize = 14.sp,
                        color = Color(0xFF6B7280),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (!selecting) {
                    // The menu must live inside a Box around its button: DropdownMenu
                    // anchors to its parent layout, and the row is full-width, so a menu
                    // placed at row level opens at the left edge instead of the button.
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Color.White) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin") }, onClick = { menu = false; onTogglePin() })
                            DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onStartSelecting() })
                            DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; onArchive() })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
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
