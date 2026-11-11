package com.example.byokchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.byokchat.chat.ModelFiles
import com.example.byokchat.chat.TurnNumbers
import com.example.byokchat.chat.UsedWorkspaceFile
import com.example.byokchat.container
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Màn quản lý Messages (Đợt 4): cùng một cuộc trò chuyện với màn chat, nhìn
 * theo từng lượt. Đường vào: Request panel → sửa thẻ History → Manage
 * messages of this chat. Nút Back quay về Request panel (như FPilot).
 *
 * - Thẻ Files ở trên cùng: file user đính kèm + file model đã đọc/ghi qua tool.
 * - Mỗi lượt: sửa từng tin (user, assistant, cả kết quả tool) và Delete turn.
 * - Sửa/xóa dùng lại ChatOps.editMessage/deleteTurn qua ChatViewModel, nên
 *   sửa tin assistant có tool call vẫn giữ nguyên toolCallsJson như trên màn
 *   chat (không đụng tới trường này).
 * - Sửa/xóa bị khóa khi model đang trả lời, như màn chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(conversationId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val vm: ChatViewModel = viewModel(key = conversationId, factory = viewModelFactory {
        initializer { ChatViewModel(container, conversationId) }
    })
    val messages by vm.messages.collectAsState()
    val generating by vm.generating.collectAsState()
    val workspaceOk by container.fs.accessible.collectAsState()

    val numbers = remember(messages) { TurnNumbers.effectiveTurnNumbers(messages) }
    val turns = remember(messages) {
        val map = LinkedHashMap<String, MutableList<MessageEntity>>()
        messages.forEach { map.getOrPut(it.turnId) { mutableListOf() }.add(it) }
        map.entries.toList()
    }
    val usedFiles = remember(messages) { ModelFiles.extractUsedFiles(messages) }
    // File còn tồn tại hay không — kiểm chứng bằng WorkspaceFs; chưa chọn
    // workspace thì không kiểm chứng được (null = chưa kiểm chứng).
    var fileExists by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    LaunchedEffect(usedFiles, workspaceOk) {
        fileExists = if (!workspaceOk) {
            usedFiles.associate { it.path to null }
        } else withContext(Dispatchers.IO) {
            usedFiles.associate { f ->
                f.path to runCatching { container.fs.find(f.path) != null }.getOrNull()
            }
        }
    }

    var editing by remember { mutableStateOf<EditState?>(null) }
    var deleteTurn by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var deleteMessage by remember { mutableStateOf<MessageEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages of this chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Editing or deleting here changes what the model sees from that point on, " +
                        "so the provider reads the chat again from there (its cache restarts at " +
                        "that message). Turn numbers are never reused, even after a turn is deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                FilesCard(
                    messages = messages,
                    numbers = numbers,
                    usedFiles = usedFiles,
                    fileExists = fileExists,
                    workspaceOk = workspaceOk,
                )
            }
            if (generating) {
                item {
                    Text(
                        "An answer is being written. Editing is paused until it is finished.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (turns.isEmpty()) {
                item { Text("No messages yet.", style = MaterialTheme.typography.bodySmall) }
            }
            items(turns, key = { it.key }) { (turnId, rows) ->
                val n = numbers[turnId] ?: 0
                TurnCard(
                    turnNo = n,
                    rows = rows,
                    generating = generating,
                    editing = editing,
                    onEditStart = { m -> editing = EditState(m.id, m.turnId, m.text, regen = false, allowBlank = true) },
                    onEditChange = { t -> editing = editing?.copy(text = t) },
                    onEditSave = { st -> editing = null; vm.saveEdit(st.messageId, st.text) },
                    onEditCancel = { editing = null },
                    onDeleteMessage = { m -> deleteMessage = m },
                    onDeleteTurn = { deleteTurn = turnId to n },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    deleteTurn?.let { (turnId, n) ->
        ConfirmDialog(
            title = "Delete turn T$n?",
            message = "The question, the tool rounds and the answer of this turn are removed. " +
                "The provider's cache restarts from here.",
            confirmLabel = "Delete",
            onConfirm = { deleteTurn = null; vm.deleteTurn(turnId) },
            onDismiss = { deleteTurn = null },
        )
    }
    deleteMessage?.let { m ->
        val n = numbers[m.turnId] ?: 0
        ConfirmDialog(
            title = "Delete turn T$n?",
            message = "Deleting a message removes its whole turn: the question, the tool " +
                "rounds and the answer of turn T$n. The provider's cache restarts from here.",
            confirmLabel = "Delete",
            onConfirm = { deleteMessage = null; vm.deleteTurn(m.turnId) },
            onDismiss = { deleteMessage = null },
        )
    }
}

/** Thẻ Files ở trên cùng màn Messages: file đính kèm + file model đã dùng. */
@Composable
private fun FilesCard(
    messages: List<MessageEntity>,
    numbers: Map<String, Int>,
    usedFiles: List<UsedWorkspaceFile>,
    fileExists: Map<String, Boolean?>,
    workspaceOk: Boolean,
) {
    val attachments = remember(messages) {
        messages.filter { it.role == "user" }.flatMap { m ->
            m.images().map { path -> Triple(path, m.id, m.turnId) }
        }
    }
    // Chỉ hiện file còn tồn tại khi đã kiểm chứng được; file không còn thì bỏ.
    val shownUsed = usedFiles.filter { fileExists[it.path] != false }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Files", style = MaterialTheme.typography.titleMedium)
            Text("User attachments", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (attachments.isEmpty()) {
                Text("No files attached.", style = MaterialTheme.typography.bodySmall)
            } else {
                attachments.forEach { (path, id, turnId) ->
                    Text(
                        "• ${path.substringAfterLast('/')} (attached in T${numbers[turnId] ?: 0} · message ${id.take(8)})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text("Workspace files used by the model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (!workspaceOk) {
                Text(
                    "No workspace folder is chosen, so file existence could not be checked. " +
                        "The paths below come from successful tool calls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (shownUsed.isEmpty()) {
                Text(
                    if (usedFiles.isEmpty()) "No files used yet." else "No files used — the files from earlier tool calls no longer exist.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                shownUsed.forEach { f ->
                    Text(
                        "• ${f.path} (via ${f.toolName} · T${numbers[f.turnId] ?: 0})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TurnCard(
    turnNo: Int,
    rows: List<MessageEntity>,
    generating: Boolean,
    editing: EditState?,
    onEditStart: (MessageEntity) -> Unit,
    onEditChange: (String) -> Unit,
    onEditSave: (EditState) -> Unit,
    onEditCancel: () -> Unit,
    onDeleteMessage: (MessageEntity) -> Unit,
    onDeleteTurn: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Turn T$turnNo", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(enabled = !generating, onClick = onDeleteTurn) { Text("Delete turn") }
            }
            rows.forEach { m ->
                val label = when (m.role) {
                    "user" -> "You"
                    "assistant" -> "Assistant"
                    "tool" -> "Tool result" + (m.toolName?.let { " · $it" } ?: "")
                    else -> m.role
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        if (m.edited) Text("edited", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(enabled = !generating, onClick = { onEditStart(m) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit message")
                        }
                        IconButton(enabled = !generating, onClick = { onDeleteMessage(m) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete turn")
                        }
                    }
                    val edit = editing?.takeIf { it.messageId == m.id }
                    if (edit != null) {
                        OutlinedTextField(
                            value = edit.text,
                            onValueChange = onEditChange,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 12,
                            label = { Text("Edit message (text only; images stay the same)") },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                            TextButton(onClick = onEditCancel) { Text("Cancel") }
                            Button(onClick = { onEditSave(edit) }, enabled = edit.text.isNotBlank() || edit.allowBlank) { Text("Save") }
                        }
                    } else if (m.text.isNotBlank()) {
                        ExpandableText(m.text)
                    } else if (m.status == "streaming") {
                        Text("Answering…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("(empty message)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
