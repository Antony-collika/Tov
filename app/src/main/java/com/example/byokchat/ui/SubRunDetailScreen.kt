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
import com.example.byokchat.data.SubRunEntity
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
 * Shared store (17/11/2026): when the run has a stored record in the
 * sub_runs table, this screen reads it — title, brief, status, rounds /
 * tokens, full report, reasoning — the SAME record get_subagent_result
 * reads (one store, two readers). Runs from before the table existed
 * have no record and fall back to the turn's message rows only (the
 * sub's assistant rows, the tool rows and the sub's message rows), as
 * this screen did before. Anything not in either source is not shown
 * here, and is not filled in by guessing.
 */
/** What the run detail screen shows from a stored run record: the same
 * record get_subagent_result reads — one store, two readers. Pure, so it
 * can be unit-tested without Android. */
data class SubRunDetail(
    val title: String,
    val agentName: String,
    val turnNo: Int,
    val status: String,
    val brief: String,
    val rounds: Int,
    val tokens: Int,
    val fullReport: String,
    val reasoning: String,
    val error: String?,
)

fun SubRunEntity.toDetail(): SubRunDetail = SubRunDetail(
    title = title,
    agentName = agentName,
    turnNo = turnNo,
    status = status,
    brief = brief,
    rounds = rounds,
    tokens = tokens,
    fullReport = fullReport,
    reasoning = reasoning,
    error = error,
)

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
    var record by remember { mutableStateOf<SubRunEntity?>(null) }
    LaunchedEffect(conversationId, turnId, agentId) {
        val loaded = withContext(Dispatchers.IO) {
            container.db.messages().getAll(conversationId).filter { it.turnId == turnId }
        }
        rows = loaded
        // The stored record for this run, when one exists: the turn number
        // comes from the turn's rows (same turn, same number). Runs from
        // before the table existed have no record — the screen then shows
        // only the rows, as before.
        record = withContext(Dispatchers.IO) {
            loaded.firstOrNull()?.turnNo?.let { container.db.subRuns().get(conversationId, agentId, it) }
        }
    }
    val detail = remember(record) { record?.toDetail() }
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
            // The stored record, when one exists: title, brief, status,
            // rounds / tokens, reasoning and the full report — the same
            // record get_subagent_result reads. The pills and bubbles below
            // still come from the turn's message rows.
            detail?.let { d ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(d.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${d.status.replace('_', ' ')} · ${d.rounds} rounds · ~${d.tokens} tokens",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Task", style = MaterialTheme.typography.labelLarge)
                        Text(d.brief, style = MaterialTheme.typography.bodyMedium)
                        if (d.reasoning.isNotBlank()) {
                            Text("Reasoning", style = MaterialTheme.typography.labelLarge)
                            Text(d.reasoning, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Full report", style = MaterialTheme.typography.labelLarge)
                        MarkdownBody(
                            d.fullReport, color = MaterialTheme.colorScheme.onSurface, highlight = null,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }
            items(agentRows, key = { it.id }) { m ->
                // The pill renders on its segment's first row; tap it to
                // open the table of steps. The sub's own words render as
                // bubbles, in the order they happened.
                segmentsByAnchor[m.id]?.let { segment ->
                    WorkPill(segment, generating = false)
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
