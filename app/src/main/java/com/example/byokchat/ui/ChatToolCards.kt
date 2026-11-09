package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.container
import com.example.byokchat.data.MessageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val callsSer = ListSerializer(AiToolCall.serializer())

/**
 * The tool results of a chat, keyed by tool-call id. Wrapped in a stable class so that a message whose
 * content did not change is not redrawn just because some other message in the chat changed.
 */
@Immutable
data class ToolResults(val byCallId: Map<String, MessageEntity>)

/**
 * One card per tool call: a readable sentence ("Read file notes/todo.md") with a clear state
 * (running / waiting for your approval / done / failed). Tap to see the technical details (JSON and result).
 */
@Composable
internal fun ToolCards(m: MessageEntity, results: ToolResults, generating: Boolean) {
    val container = LocalContext.current.container
    val pending by container.confirmations.pending.collectAsState()
    val calls = remember(m.toolCallsJson) {
        runCatching { Json.decodeFromString(callsSer, m.toolCallsJson ?: "[]") }.getOrDefault(emptyList())
    }
    calls.forEach { c ->
        val res = results.byCallId[c.id]
        var expanded by remember(c.id) { mutableStateOf(false) }
        val toolName = c.uid?.let { container.registry.nameForUid(it) } ?: c.name
        val summary = remember(c.uid, c.argsJson, toolName) { toolSummary(c.uid, toolName, c.argsJson) }
        val waiting = res == null && generating && pending.any { it.uid == c.uid }
        val running = res == null && generating && !waiting
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        running || waiting -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        res == null -> Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                        res.toolIsError -> Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        else -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(summary, style = MaterialTheme.typography.labelLarge, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                        val (label, color) = when {
                            waiting -> "Waiting for your approval\u2026" to MaterialTheme.colorScheme.tertiary
                            running -> "Running\u2026" to MaterialTheme.colorScheme.onSurfaceVariant
                            res == null -> "No result" to MaterialTheme.colorScheme.onSurfaceVariant
                            res.toolIsError -> "Failed or denied" to MaterialTheme.colorScheme.error
                            else -> "Done" to MaterialTheme.colorScheme.primary
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Hide details" else "Show details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (expanded) {
                    Text("Tool: $toolName", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Arguments", style = MaterialTheme.typography.labelSmall)
                    Text(prettyArgs(c.argsJson).take(3000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    if (res != null) {
                        Text("Result", style = MaterialTheme.typography.labelSmall)
                        Text(res.text.take(4000), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
