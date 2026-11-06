package com.example.byokchat.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val conversations: StateFlow<List<ConversationEntity>> =
        app.db.conversations().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun create(): String = app.conversations.create()
    fun rename(id: String, title: String) = viewModelScope.launch { app.conversations.rename(id, title) }
    fun delete(id: String) = viewModelScope.launch { app.conversations.delete(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, onSearch: () -> Unit) {
    val container = LocalContext.current.container
    val vm: ConversationListViewModel = viewModel(factory = viewModelFactory {
        initializer { ConversationListViewModel(container) }
    })
    val scope = rememberCoroutineScope()
    val conversations by vm.conversations.collectAsState()
    val active by container.engine.activeConversations.collectAsState()
    val providers by container.settings.providers.collectAsState()
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trò chuyện") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = "Tìm kiếm") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Cài đặt") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { scope.launch { onOpen(vm.create()) } }) {
                Icon(Icons.Default.Add, contentDescription = "Cuộc trò chuyện mới")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            if (providers.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Chào mừng bạn! 👋", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Để bắt đầu, hãy thêm một nhà cung cấp AI (Claude, OpenAI, Gemini...) và nhập API key của bạn.",
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                            Button(onClick = onSettings) { Text("Mở Cài đặt") }
                        }
                    }
                }
            }
            if (conversations.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Chưa có cuộc trò chuyện nào. Bấm dấu + để bắt đầu.")
                    }
                }
            }
            items(conversations, key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { onOpen(c.id) },
                    headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            listOf(c.model.ifBlank { "chưa chọn mô hình" }, dateFormat.format(Date(c.updatedAt))).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (c.id in active) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Tùy chọn")
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Đổi tên") }, onClick = { menu = false; renameTarget = c })
                                    DropdownMenuItem(text = { Text("Xóa") }, onClick = { menu = false; deleteTarget = c })
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
            title = "Đổi tên cuộc trò chuyện",
            initial = c.title,
            onConfirm = { vm.rename(c.id, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { c ->
        ConfirmDialog(
            title = "Xóa cuộc trò chuyện?",
            message = "\"${c.title}\" sẽ bị xóa vĩnh viễn cùng toàn bộ tin nhắn.",
            confirmLabel = "Xóa",
            onConfirm = {
                container.engine.stop(c.id)
                vm.delete(c.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}
