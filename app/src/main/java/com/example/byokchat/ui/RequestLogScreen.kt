package com.example.byokchat.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.byokchat.ai.RequestLogEntry
import com.example.byokchat.ai.toMarkdown
import com.example.byokchat.container
import com.example.byokchat.util.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nhật ký request/response: mỗi lần gọi model một mục, ghi ra file trong bộ
 * nhớ riêng của app. Đợt fix 11/11/2026 (F1–F4): log theo từng chat — mở
 * một chat thì chỉ thấy log của chat đó (kèm số lượt), sao chép cả một lượt
 * (request + response) và xuất cả chat ra file .md khi user yêu cầu.
 * `conversationId = null` = mục cũ ghi trước khi log theo chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestLogScreen(conversationId: String? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val logEnabled by settings.requestLogEnabled.collectAsState()
    var entries by remember { mutableStateOf<List<RequestLogEntry>>(emptyList()) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch(Dispatchers.IO) { entries = container.requestLog.entries(conversationId) }
    }
    LaunchedEffect(conversationId) { refresh() }

    // F4: xuất cả chat ra file .md — user chọn chỗ lưu bằng hộp chọn file
    // của hệ thống; đọc lại log lúc ghi để luôn lấy bản mới nhất.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val current = container.requestLog.entries(conversationId)
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val text = buildString {
                    append("# Request log")
                    if (conversationId == null) append(" (older logs)")
                    append("\n\nExported $date.\n\n")
                    append(current.joinToString("\n\n---\n\n") { it.toMarkdown() })
                    append("\n")
                }
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    ?: error("Could not open the file")
            }.isSuccess
            launch(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (ok) "Log exported" else "Export failed",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Request log") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                exportLauncher.launch(
                                    if (conversationId == null) "request-log-older-$date.md"
                                    else "request-log-$date.md",
                                )
                            },
                        ) { Text("Export") }
                        TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text("Request log") },
                supportingContent = {
                    Text(
                        if (conversationId == null) "Requests logged before chats had their own log, with the real token counts. Stored only on this device."
                        else "Requests sent in this chat, with the real token counts. Stored only on this device.",
                    )
                },
                trailingContent = {
                    Switch(checked = logEnabled, onCheckedChange = { settings.setRequestLogEnabled(it) })
                },
            )
            if (!logEnabled) {
                Text(
                    "Logging is off. Turn it on to record future requests.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (entries.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("No requests logged yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(entries, key = { it.id }) { e ->
                        EntryCard(
                            entry = e,
                            expanded = expandedId == e.id,
                            onToggle = { expandedId = if (expandedId == e.id) null else e.id },
                            onCopyRequest = {
                                clipboard.setText(AnnotatedString(e.requestBody))
                                refresh()
                            },
                            onCopyTurn = {
                                clipboard.setText(AnnotatedString(e.toMarkdown()))
                                refresh()
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear the request log?",
            message = "All ${entries.size} logged request(s) will be deleted from this device.",
            confirmLabel = "Clear",
            onConfirm = {
                confirmClear = false
                scope.launch(Dispatchers.IO) { container.requestLog.clear(conversationId); entries = emptyList() }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun EntryCard(
    entry: RequestLogEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopyRequest: () -> Unit,
    onCopyTurn: () -> Unit,
) {
    Card(onClick = onToggle, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                buildString {
                    if (entry.turnNo != null) append("Turn ${entry.turnNo} · ")
                    append("${fmtTime(entry.timeMillis)} · ${entry.provider} · ${entry.model}")
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "HTTP ${entry.httpStatus ?: "?"} · ${entry.usage?.summaryLine() ?: "[usage] not reported"}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (entry.error != null) {
                Text("Error: ${entry.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text("Request", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                ExpandableText(entry.requestBody, previewChars = 2000)
                TextButton(onClick = onCopyRequest) { Text("Copy request") }
                Text("Response", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (entry.responseText.isNullOrBlank()) {
                    Text("(no response recorded)", style = MaterialTheme.typography.bodySmall)
                } else {
                    ExpandableText(entry.responseText, previewChars = 2000)
                }
                // F3: sao chép cả một lượt (request + response) để gửi đi/debug.
                TextButton(onClick = onCopyTurn) { Text("Copy turn") }
            } else {
                TextButton(onClick = onToggle) { Text("Show details") }
            }
        }
    }
}
