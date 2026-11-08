package com.example.byokchat.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationListViewModel(private val app: AppContainer) : ViewModel() {
    /** Only conversations that have at least one message; empty drafts are hidden. */
    val conversations: StateFlow<List<ConversationEntity>> =
        app.db.conversations().observeNonEmpty().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rename(id: String, title: String) = viewModelScope.launch { app.conversations.rename(id, title) }
    fun delete(id: String) = viewModelScope.launch { app.conversations.delete(id) }
}

/** Content of the navigation drawer: the list of conversations (the chat itself is the home screen). */
@Composable
fun ConversationDrawerContent(
    currentId: String?,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleted: (String) -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
) {
    val container = LocalContext.current.container
    val vm: ConversationListViewModel = viewModel(factory = viewModelFactory {
        initializer { ConversationListViewModel(container) }
    })
    val conversations by vm.conversations.collectAsState()
    val active by container.engine.activeConversations.collectAsState()
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    val titles = remember(conversations) { conversations.associate { it.id to it.title } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Conversations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Search") }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        }
        FilledTonalButton(
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("New chat", modifier = Modifier.padding(start = 8.dp))
        }
        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No conversations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            items(conversations, key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                val selected = c.id == currentId
                ListItem(
                    modifier = Modifier.clickable { onSelect(c.id) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ),
                    headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val parent = c.parentId
                        if (parent != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CallSplit, contentDescription = "Branch", modifier = Modifier.size(14.dp))
                                Text(
                                    " " + (titles[parent]?.let { "Branch of $it" } ?: "Branch"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Text(
                                listOf(c.model.ifBlank { "no model selected" }, dateFormat.format(Date(c.updatedAt))).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (c.id in active) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renameTarget = c })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleteTarget = c })
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    renameTarget?.let { c ->
        TextEditDialog(
            title = "Rename conversation",
            initial = c.title,
            onConfirm = { vm.rename(c.id, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { c ->
        ConfirmDialog(
            title = "Delete conversation?",
            message = "\"${c.title}\" will be permanently deleted along with all its messages.",
            confirmLabel = "Delete",
            onConfirm = {
                container.engine.stop(c.id)
                vm.delete(c.id)
                onDeleted(c.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}
