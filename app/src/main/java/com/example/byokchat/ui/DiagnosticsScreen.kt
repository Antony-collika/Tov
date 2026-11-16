package com.example.byokchat.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.byokchat.chat.HistoryExporter
import com.example.byokchat.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The per-chat files of the latest chat, for the "Other logs" section. */
internal data class LatestChatFiles(
    val sessionId: String,
    val dir: String,
    val historyPath: String,
    val metaPath: String,
    val subRunsPath: String,
)

/**
 * Session ids mentioned in a diagnostics log text: the conv= ids, in order
 * of first appearance, no duplicates. Pure, so it can be unit-tested.
 */
internal fun sessionsInDiagnosticsLog(text: String): List<String> {
    val seen = LinkedHashSet<String>()
    Regex("conv=([0-9a-fA-F-]{8})").findAll(text).forEach { seen += it.groupValues[1] }
    return seen.toList()
}

/**
 * The export bundle: diagnostics log + sessions + where the other logs
 * live. Pure, so it can be unit-tested.
 */
internal fun diagnosticsBundle(logText: String, sessions: List<String>, latestChat: LatestChatFiles?): String =
    buildString {
        appendLine("# Diagnostics")
        appendLine()
        appendLine("Exported ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}.")
        appendLine()
        appendLine("## Sessions in this log")
        if (sessions.isEmpty()) appendLine("(none yet)")
        else sessions.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Diagnostics log")
        appendLine(if (logText.isBlank()) "(empty — no handoff has been logged yet)" else logText.trim())
        appendLine()
        appendLine("## Other logs")
        appendLine("- Request log: open the Log screen in the app (chat menu → Log, or Request panel → Log).")
        if (latestChat == null) {
            appendLine("- No chat with messages yet: history.md, history.meta.json and sub_runs.meta.json appear in a chat's session folder once it has messages.")
        } else {
            appendLine("- Latest chat (${latestChat.sessionId}), in the session folder ${latestChat.dir}:")
            appendLine("  - ${latestChat.historyPath}")
            appendLine("  - ${latestChat.metaPath}")
            appendLine("  - ${latestChat.subRunsPath}")
        }
    }

/**
 * Diagnostics (16/11/2026): the sub-agent handoff log (each step from a
 * sub finishing to the next request), the sessions in it, and where the
 * other logs live. Copy or export the bundle and send it to Muse when a
 * turn gets stuck. Entered from Settings (the log is app-wide, not
 * per-chat, so it does not live on the per-chat Request panel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, onOpenRequestLog: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var logText by remember { mutableStateOf("") }
    var latestChat by remember { mutableStateOf<LatestChatFiles?>(null) }
    val sessions = sessionsInDiagnosticsLog(logText)

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            logText = container.diagnosticsLog.readText()
            val conv = container.db.conversations().observeNonEmpty().firstOrNull()?.firstOrNull()
            latestChat = conv?.let {
                LatestChatFiles(
                    sessionId = it.id.take(8),
                    dir = HistoryExporter.sessionDir(it),
                    historyPath = HistoryExporter.historyPath(it),
                    metaPath = HistoryExporter.metaPath(it),
                    subRunsPath = HistoryExporter.subRunsPath(it),
                )
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun bundle(): String = diagnosticsBundle(logText, sessions, latestChat)

    // Export the bundle as one .md file — the user picks where to save it
    // with the system file picker (same path as the Log screen's export);
    // the log is re-read at write time so the file is always the latest.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val current = container.diagnosticsLog.readText()
                val conv = container.db.conversations().observeNonEmpty().firstOrNull()?.firstOrNull()
                val chat = conv?.let {
                    LatestChatFiles(
                        sessionId = it.id.take(8),
                        dir = HistoryExporter.sessionDir(it),
                        historyPath = HistoryExporter.historyPath(it),
                        metaPath = HistoryExporter.metaPath(it),
                        subRunsPath = HistoryExporter.subRunsPath(it),
                    )
                }
                val text = diagnosticsBundle(current, sessionsInDiagnosticsLog(current), chat)
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    ?: error("Could not open the file")
            }.isSuccess
            launch(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (ok) "Diagnostics exported" else "Export failed",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                container.diagnosticsLog.threadDump()
                                launch(Dispatchers.Main) {
                                    Toast.makeText(context, "Thread dump saved", Toast.LENGTH_SHORT).show()
                                    refresh()
                                }
                            }
                        },
                    ) { Text("Thread dump") }
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                container.diagnosticsLog.coroutineDump()
                                launch(Dispatchers.Main) {
                                    Toast.makeText(context, "Coroutine dump saved", Toast.LENGTH_SHORT).show()
                                    refresh()
                                }
                            }
                        },
                    ) { Text("Coroutine dump") }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(bundle()))
                            Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                            refresh()
                        },
                    ) { Text("Copy") }
                    TextButton(
                        onClick = {
                            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            exportLauncher.launch("diagnostics-$date.md")
                        },
                    ) { Text("Export") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Sub-agent handoff log: each step from a sub finishing to the next request. " +
                    "Copy or export it and send it to Muse when a turn gets stuck. " +
                    "If a turn is stuck right now, tap Coroutine dump first to capture where the turn is parked, then copy or export. " +
                    "Tap Clear before a fresh test run so the log starts empty and sessions don't mix.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text("Sessions in this log", style = MaterialTheme.typography.labelLarge)
            Text(
                if (sessions.isEmpty()) "(none yet)" else sessions.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Diagnostics log", style = MaterialTheme.typography.labelLarge)
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            container.diagnosticsLog.clear()
                            launch(Dispatchers.Main) {
                                Toast.makeText(context, "Log cleared", Toast.LENGTH_SHORT).show()
                                refresh()
                            }
                        }
                    },
                ) { Text("Clear") }
            }
            if (logText.isBlank()) {
                Text("No handoff has been logged yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                ExpandableText(logText, previewChars = 4000)
            }
            Spacer(Modifier.height(12.dp))
            Text("Other logs", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onOpenRequestLog) { Text("Open request log") }
            val chat = latestChat
            if (chat == null) {
                Text(
                    "No chat with messages yet. history.md, history.meta.json and sub_runs.meta.json appear in a chat's session folder once it has messages.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "Latest chat (${chat.sessionId}), in the session folder ${chat.dir}:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(chat.historyPath, style = MaterialTheme.typography.bodySmall)
                Text(chat.metaPath, style = MaterialTheme.typography.bodySmall)
                Text(chat.subRunsPath, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
