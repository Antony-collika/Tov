package com.example.byokchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The whole Main↔sub conversation of a session, opened by tapping the
 * sub's avatar on the chat screen — in ANY turn (17/11/2026, mockup
 * v2). This screen is that sub's inbox: everything the sub said and
 * received, in time order across the whole session — every brief it
 * was given (under whoever gave it: Main, or the peer that delegated
 * or woke it, 20/11/2026) and every report it sent back, as plain
 * bubbles. The messages the sub sent to the user are NOT here — they
 * already live on the main chat screen (18/11/2026). There is no
 * per-turn split, no turn marker. The title is just the sub's name.
 * Runs from before the sub_runs table existed have no record and
 * simply show nothing. The sub's own bubbles sit on the right, everyone
 * else's on the left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubConversationScreen(
    conversationId: String,
    agentId: String,
    onBack: () -> Unit,
) {
    val container = LocalContext.current.container
    val member = remember(agentId) { subAgentMember(agentId) }
    var items by remember { mutableStateOf<List<SubConversationItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(conversationId, agentId) {
        val built = withContext(Dispatchers.IO) {
            val runs = container.db.subRuns().getByAgent(conversationId, agentId)
            buildSubConversation(runs)
        }
        items = built
        loaded = true
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.key }) { item ->
                // The screen owner's own bubbles sit on the right;
                // everyone else's (Main, or a peer that started a run)
                // sit on the left, under their own name.
                val isMine = item.senderId == agentId
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                ) {
                    Column(
                        Modifier.widthIn(max = 340.dp),
                        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
                    ) {
                        Text(
                            item.senderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            color = if (isMine) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = if (isMine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                            else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                        ) {
                            MarkdownBody(
                                item.text,
                                color = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                highlight = null,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
            if (loaded && items.isEmpty()) {
                item {
                    Text(
                        "No messages with this agent in this chat yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
