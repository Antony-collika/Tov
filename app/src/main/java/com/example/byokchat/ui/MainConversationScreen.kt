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
 * Main's inbox for a session, opened by tapping Main's avatar on the
 * chat screen (20/11/2026). The full exchange between Main and every
 * sub, in time order across the whole session: Main's briefs (full
 * text, with the recipient — "Main tới Bob" — so in one inbox it is
 * clear who Main is talking to) and each sub's messages to Main plus
 * final reports, as plain bubbles. A peer's brief (Bob→Danny,
 * Danny→Bob) is NOT here: it is neither Main's words nor addressed
 * to Main — it lives in the per-sub screens. The messages the subs
 * sent to the user are NOT here either — they already live on the
 * main chat screen. There is no per-turn split, no turn marker. The
 * title is just "Main". Main's own bubbles sit on the right, every
 * sub's on the left, under that sub's name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainConversationScreen(
    conversationId: String,
    onBack: () -> Unit,
) {
    val container = LocalContext.current.container
    var items by remember { mutableStateOf<List<SubConversationItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(conversationId) {
        val built = withContext(Dispatchers.IO) {
            val runs = container.db.subRuns().getAll(conversationId)
            buildMainConversation(runs)
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
                title = { Text("Main") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.key }) { item ->
                val isMine = item.senderId == MAIN_SENDER_ID
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                ) {
                    Column(
                        Modifier.widthIn(max = 340.dp),
                        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
                    ) {
                        Text(
                            if (isMine && item.toName != null) "Main tới ${item.toName}" else item.senderName,
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
                        "Nothing between Main and the subs in this chat yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
