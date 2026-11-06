package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.data.NoteEntity
import com.example.byokchat.data.TodoEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val db = LocalContext.current.container.db
    val scope = rememberCoroutineScope()
    val notes by remember { db.notes().observeAll() }.collectAsState(initial = emptyList())
    val todos by remember { db.todos().observeAll() }.collectAsState(initial = emptyList())
    var openNote by remember { mutableStateOf<NoteEntity?>(null) }
    var deleteNote by remember { mutableStateOf<NoteEntity?>(null) }
    var deleteTodo by remember { mutableStateOf<TodoEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ghi chú & việc cần làm") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionTitle("Việc cần làm (${todos.size})") }
            if (todos.isEmpty()) item { Text("Chưa có việc nào.", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            items(todos, key = { "t_" + it.id }) { t ->
                ListItem(
                    leadingContent = {
                        Checkbox(checked = t.done, onCheckedChange = { scope.launch { db.todos().upsert(t.copy(done = it)) } })
                    },
                    headlineContent = { Text(t.text) },
                    trailingContent = {
                        IconButton(onClick = { deleteTodo = t }) { Icon(Icons.Default.Delete, contentDescription = "Xóa việc") }
                    },
                )
            }
            item { SectionTitle("Ghi chú nhớ lâu (${notes.size})") }
            if (notes.isEmpty()) item { Text("Chưa có ghi chú nào.", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            items(notes, key = { "n_" + it.id }) { n ->
                ListItem(
                    modifier = Modifier.clickable { openNote = n },
                    headlineContent = { Text(n.title) },
                    supportingContent = { Text(n.content.replace("\n", " "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { deleteNote = n }) { Icon(Icons.Default.Delete, contentDescription = "Xóa ghi chú") }
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    openNote?.let { n ->
        AlertDialog(
            onDismissRequest = { openNote = null },
            title = { Text(n.title) },
            text = { Text(n.content) },
            confirmButton = { TextButton(onClick = { openNote = null }) { Text("Đóng") } },
        )
    }
    deleteNote?.let { n ->
        ConfirmDialog("Xóa ghi chú?", "\"${n.title}\" sẽ bị xóa.", "Xóa",
            onConfirm = { scope.launch { db.notes().delete(n.id) }; deleteNote = null },
            onDismiss = { deleteNote = null })
    }
    deleteTodo?.let { t ->
        ConfirmDialog("Xóa việc?", "\"${t.text}\" sẽ bị xóa.", "Xóa",
            onConfirm = { scope.launch { db.todos().delete(t.id) }; deleteTodo = null },
            onDismiss = { deleteTodo = null })
    }
}
