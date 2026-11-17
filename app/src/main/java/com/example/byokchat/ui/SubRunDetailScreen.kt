package com.example.byokchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A sub-agent's run in one turn, opened by tapping the sub's avatar on the
 * chat screen (17/11/2026, decided 15/11/2026): the tools it called, its
 * readable reasoning where the provider returned any, and its answers —
 * the same work the chat shows, gathered in one place. This is NOT the
 * agent's settings: the avatar on the Members screen opens settings,
 * the avatar on the chat screen opens this run view.
 *
 * Shared store (17/11/2026): this screen reads ONLY the turn's existing
 * DB rows — the sub's assistant rows (sender = agent id), the tool rows
 * (args + results) and the sub's message rows. No separate store is
 * created for it: the future recall tool (context-manager phase) will
 * read this same record. Anything not in those rows is not shown here,
 * and is listed as a gap in the handoff, not filled in by guessing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubRunDetailScreen(
    conversationId: String,
    agentId: String,
    turnId: String,
    onBack: () -> Unit,
) {
    val container = LocalContext.current.container
    val member = remember(agentId) { subAgentMember(agentId) }
    var rows by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    LaunchedEffect(conversationId, turnId, agentId) {
        rows = withContext(Dispatchers.IO) {
            container.db.messages().getAll(conversationId).filter { it.turnId == turnId }
        }
    }
    // The sub's own rows (sender = the agent id) plus the tool-result rows
    // of the same turn, which carry no sender. Everything else in the
    // turn (Main's calls, the user's message) stays out.
    val agentRows = remember(rows) { rows.filter { it.role == "assistant" && it.sender == agentId } }
    val toolResults = remember(rows) {
        ToolResults(rows.filter { it.role == "tool" && it.toolCallId != null }.associateBy { it.toolCallId!! })
    }
    val segmentsByAnchor = remember(agentRows, toolResults) {
        groupWorkSegments(agentRows, toolResults).associateBy { it.anchorId }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { Text(member.name) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemberAvatar(member, size = 36.dp)
                    Column {
                        Text(member.name, style = MaterialTheme.typography.titleMedium)
                        Text(member.role, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(agentRows, key = { it.id }) { m ->
                // The pill renders on its segment's first row, expanded so
                // the run's work is visible right away; the sub's own
                // words render as bubbles, in the order they happened.
                segmentsByAnchor[m.id]?.let { segment ->
                    WorkPill(segment, generating = false, initiallyExpanded = true)
                }
                if (m.text.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                        modifier = Modifier.widthIn(max = 340.dp),
                    ) {
                        MarkdownBody(
                            m.text, color = MaterialTheme.colorScheme.onSurface, highlight = null,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            if (agentRows.isEmpty()) {
                item {
                    Text(
                        "No messages from this agent in this turn.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
