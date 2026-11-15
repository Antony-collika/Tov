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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.example.byokchat.container

/**
 * Members screen (Phase 1: UI shell): who is in this group chat and what
 * each member is doing, grouped by urgency (most urgent first). Entry: the
 * chat menu (⋮ → Members) or the members strip under the top bar.
 *
 * Phase 1 has only 2 members (you + Main). Sub-agents join this same list
 * in a later phase; their status, current task and per-agent stop arrive
 * with them. Tapping an agent's avatar opens its settings — a stub for now
 * (the fields are shown, editing arrives in a later phase).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(conversationId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val vm: ChatViewModel = viewModel(key = conversationId, factory = viewModelFactory {
        initializer { ChatViewModel(container, conversationId) }
    })
    val members by vm.members.collectAsState()
    val groups = remember(members) { groupMembers(members) }
    var settingsMember by remember { mutableStateOf<GroupMember?>(null) }

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
            groups.forEach { group ->
                item {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(group.members, key = { it.id }) { member ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Tapping an agent's avatar opens its settings (stub);
                        // your own avatar is not an agent, so it is not tappable.
                        MemberAvatar(
                            member,
                            size = 40.dp,
                            onClick = if (member.isUser) null else ({ settingsMember = member }),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(member.name, style = MaterialTheme.typography.titleMedium)
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
                }
            }
            item {
                Text(
                    "Sub-agents join this list when Main calls them, and leave when their task is done.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    settingsMember?.let { member ->
        AgentSettingsStubDialog(member = member, onDismiss = { settingsMember = null })
    }
}

/**
 * Stub entry point for an agent's settings (Phase 1): the fields are shown
 * (folder / tools / role, per the group-chat design) but not editable yet —
 * editing arrives with the agent settings screen in a later phase.
 * Phase 2: a known sub-agent shows its real folder and tools; Main still
 * shows "—" (its settings live elsewhere).
 */
@Composable
private fun AgentSettingsStubDialog(member: GroupMember, onDismiss: () -> Unit) {
    val agent = com.example.byokchat.chat.SubAgentStore.snapshot.byId(member.id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(member.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Role: ${member.role}", style = MaterialTheme.typography.bodyMedium)
                Text("Workspace folder: ${agent?.workspaceFolder ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Tools: ${agent?.toolUids?.joinToString(", ") ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Agent settings editing arrives in a later phase.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
