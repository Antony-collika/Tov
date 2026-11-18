package com.example.byokchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.byokchat.chat.SubAgentConfig
import com.example.byokchat.container
import kotlinx.coroutines.delay

/**
 * Members screen (Phase 3: full session info, per the group-chat mockup):
 * the session header (id, start, peak team, elapsed time, message counts,
 * session token bar, journal shortcut), members grouped by urgency, and
 * per member: live running timer, current task, rounds used/cap, tokens
 * used/budget, a per-sub Stop button and a journal shortcut. Tapping an
 * agent's avatar opens its settings screen.
 *
 * Done-when: a sub stuck too long is visible here (its timer keeps
 * running while its rounds/tokens stop moving) and can be stopped from
 * here — stopping one sub never stops Main.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(
    conversationId: String,
    onBack: () -> Unit,
    onAgentSettings: (String) -> Unit,
    onAddAgent: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val vm: ChatViewModel = viewModel(key = conversationId, factory = viewModelFactory {
        initializer { ChatViewModel(container, conversationId) }
    })
    val members by vm.members.collectAsState()
    val sessionInfo by vm.sessionInfo.collectAsState()
    val team by container.agentStore.team.collectAsState()
    val groups = remember(members) { groupMembers(members) }
    // Ticking clock for the session elapsed time and the per-sub timers.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    var journalError by remember { mutableStateOf<String?>(null) }
    val journalPath = sessionInfo?.journalPath

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Members") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            sessionInfo?.let { info ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Session",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        InfoRow("Session id", info.sessionShortId)
                        InfoRow("Started", "${formatClock(info.startedAt)} · running ${formatElapsed(now - info.startedAt)}")
                        InfoRow("At the start", "${info.membersAtStart} members · You + Main")
                        InfoRow("At peak", "${info.peakMembers} members")
                        InfoRow("Messages", "${info.messageCount} messages · ${info.subMessageCount} from sub-agents")
                        val percent = sessionTokenPercent(info.sessionTokensUsed, info.sessionTokenBudget)
                        InfoRow(
                            "Session token budget",
                            if (info.sessionTokenBudget > 0) "used ${info.sessionTokensUsed} / ${info.sessionTokenBudget} ($percent%)" else "no agent budget set",
                        )
                        if (info.sessionTokenBudget > 0) {
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                        if (journalPath != null) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Journal (sub_runs.meta.json)", style = MaterialTheme.typography.bodyMedium)
                                TextButton(
                                    onClick = {
                                        journalError = null
                                        openFileAt(context, container.fs, journalPath, onError = { journalError = it }, inSessionStorage = true)
                                    },
                                ) { Text("Open file ›") }
                            }
                            if (journalError != null) {
                                Text(journalError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            groups.forEach { group ->
                item {
                    Text(
                        "${group.title} · ${group.members.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(group.members, key = { it.id }) { member ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Tapping an agent's avatar opens its settings;
                            // your own avatar is not an agent, so it is not tappable.
                            MemberAvatar(
                                member,
                                size = 40.dp,
                                onClick = if (member.isAgent) ({ onAgentSettings(member.id) }) else null,
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(member.name, style = MaterialTheme.typography.titleMedium)
                                    if (!member.enabled) {
                                        Text("Off", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (member.startedAt != null) {
                                        Text(
                                            formatElapsed(now - member.startedAt),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(member.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (member.currentTask != null) {
                                    Text(member.currentTask, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(
                                memberStatusLabel(member.status),
                                style = MaterialTheme.typography.labelMedium,
                                color = memberStatusColor(member.status),
                            )
                        }
                        if (member.roundsUsed != null && member.roundCap != null) {
                            Text(
                                "Round ${member.roundsUsed}/${member.roundCap}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (member.tokensUsed != null && member.tokenBudget != null) {
                            Text(
                                "Tokens ~${member.tokensUsed} / ${member.tokenBudget}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearProgressIndicator(
                                progress = { (member.tokensUsed.toFloat() / member.tokenBudget.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (member.isAgent) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (journalPath != null) {
                                    TextButton(
                                        onClick = {
                                            journalError = null
                                            openFileAt(context, container.fs, journalPath, onError = { journalError = it }, inSessionStorage = true)
                                        },
                                    ) { Text("View journal") }
                                }
                                if (member.status == MemberStatus.WORKING) {
                                    TextButton(onClick = { vm.stopSub(member.id) }) { Text("Stop this sub") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Safety ceilings — defaults, adjustable per agent (apply to later runs)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InfoRow("Max rounds per sub run", "Default ${SubAgentConfig.DEFAULT_ROUND_CAP} · adjustable ${SubAgentConfig.MIN_ROUND_CAP}–${SubAgentConfig.MAX_ROUND_CAP}")
                    InfoRow("Token budget per sub", "Default ${SubAgentConfig.DEFAULT_TOKEN_BUDGET / 1000}K · adjustable ${SubAgentConfig.MIN_TOKEN_BUDGET / 1000}K–${SubAgentConfig.MAX_TOKEN_BUDGET / 1000}K")
                    InfoRow("Team structure", "Main controls directly · no sub-subs")
                    InfoRow("Main Stop button", "Stops Main only")
                    Text(
                        "An agent that finished its run stays in the team — Main calls it again when needed, with the same role and tools. Tap an agent's avatar to edit its folder, tools and role.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (team.agents.isEmpty()) {
                item {
                    Text(
                        "No sub-agents yet — Main has no one to call. Add one below, then teach Main when to call it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OutlinedButton(onClick = onAddAgent, modifier = Modifier.fillMaxWidth()) {
                    Text("Add agent")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
