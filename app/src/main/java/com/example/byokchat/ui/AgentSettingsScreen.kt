package com.example.byokchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.byokchat.chat.HistoryVisibility
import com.example.byokchat.chat.SubAgentConfig
import com.example.byokchat.container

/** Id for a new agent, from its name ("My Reviewer" -> "my-reviewer"). Pure. */
fun agentIdSlug(name: String): String {
    val slug = name.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
    return slug.ifBlank { "agent" }
}

/**
 * Agent settings screen (Phase 3): the editor for one agent in agents.json.
 * Reached by tapping an agent's avatar on the Members screen (or "Add
 * agent"). Every field of [SubAgentConfig] is editable here — name, role,
 * description, system prompt, workspace folder, tools, round cap, token
 * budget, model, on/off, history visibility — and saving writes the file
 * (the file is the source of truth; the delegate tool reads the team from
 * it on every send). The id never changes after creation; a new agent's id
 * is slugged from its name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsScreen(agentId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val store = container.agentStore
    val team by store.team.collectAsState()
    val providers by container.settings.providers.collectAsState()
    val loadError by store.lastLoadError.collectAsState()
    val isNew = agentId == "new"
    val agent = if (isNew) null else team.byId(agentId)
    val key = agent?.id ?: "new"

    // Field state, keyed by the agent so switching agents resets the form.
    var name by remember(key) { mutableStateOf(agent?.name ?: "") }
    var role by remember(key) { mutableStateOf(agent?.role ?: "") }
    var description by remember(key) { mutableStateOf(agent?.description ?: "") }
    var systemPrompt by remember(key) { mutableStateOf(agent?.systemPrompt ?: "") }
    var workspaceFolder by remember(key) { mutableStateOf(agent?.workspaceFolder ?: "") }
    var roundCapText by remember(key) { mutableStateOf(agent?.roundCap?.toString() ?: SubAgentConfig.DEFAULT_ROUND_CAP.toString()) }
    var tokenBudgetText by remember(key) { mutableStateOf(agent?.tokenBudget?.toString() ?: SubAgentConfig.DEFAULT_TOKEN_BUDGET.toString()) }
    // A new agent starts with the web tools (the common case); uncheck to take them away.
    var toolUids by remember(key) { mutableStateOf(agent?.toolUids?.toSet() ?: setOf("web.search", "web.fetch")) }
    var modelChoice by remember(key) {
        mutableStateOf<Pair<String, String>?>(
            if (agent?.hasOwnModel == true) agent.modelProviderId!! to agent.model!! else null,
        )
    }
    var history by remember(key) { mutableStateOf(agent?.historyVisibility ?: HistoryVisibility.BRIEF_ONLY) }
    var enabled by remember(key) { mutableStateOf(agent?.enabled ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }

    // Tools a sub may use: everything except the delegate tool (no sub-subs).
    // The send_message tool is added to every run by the runner, so it is never listed here.
    val pickableTools = container.registry.all.filter { !it.uid.startsWith("sub.") }
    val modelOptions: List<Pair<String, Pair<String, String>?>> =
        listOf("Follow Main's model" to null) +
            providers.flatMap { p -> p.models.map { m -> "${p.name} — $m" to (p.id to m) } }
    val modelLabel = modelChoice?.let { (pid, m) ->
        providers.firstOrNull { it.id == pid }?.let { "${it.name} — $m" } ?: "$pid — $m"
    } ?: "Follow Main's model"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New agent" else agent?.name ?: "Agent") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (!isNew && agent == null) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Unknown agent '$agentId'.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onBack) { Text("Back") }
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loadError != null) {
                Text(loadError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!isNew) {
                Text("Id: ${agent!!.id} (never changes)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = role, onValueChange = { role = it },
                label = { Text("Role (shown on the Members screen)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("Description (Main reads this when picking an agent)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
            OutlinedTextField(
                value = systemPrompt, onValueChange = { systemPrompt = it },
                label = { Text("System prompt") },
                modifier = Modifier.fillMaxWidth(), minLines = 4,
            )
            OutlinedTextField(
                value = workspaceFolder, onValueChange = { workspaceFolder = it },
                label = { Text("Workspace folder") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = roundCapText, onValueChange = { roundCapText = it.filter { c -> c.isDigit() } },
                label = { Text("Max rounds per run (${SubAgentConfig.MIN_ROUND_CAP}–${SubAgentConfig.MAX_ROUND_CAP})") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = tokenBudgetText, onValueChange = { tokenBudgetText = it.filter { c -> c.isDigit() } },
                label = { Text("Token budget (${SubAgentConfig.MIN_TOKEN_BUDGET}–${SubAgentConfig.MAX_TOKEN_BUDGET})") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("Tools", style = MaterialTheme.typography.labelLarge)
            pickableTools.forEach { tool ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = tool.uid in toolUids,
                        onCheckedChange = { checked ->
                            toolUids = if (checked) toolUids + tool.uid else toolUids - tool.uid
                        },
                    )
                    Column {
                        Text(tool.defaultName, style = MaterialTheme.typography.bodyMedium)
                        Text(tool.uid, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Model", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { modelMenu = true }) { Text(modelLabel) }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    modelOptions.forEach { (label, choice) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { modelChoice = choice; modelMenu = false },
                        )
                    }
                }
            }
            Text("History", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = history == HistoryVisibility.BRIEF_ONLY, onClick = { history = HistoryVisibility.BRIEF_ONLY })
                Text("Brief only", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = history == HistoryVisibility.SEES_HISTORY, onClick = { history = HistoryVisibility.SEES_HISTORY })
                Text("Sees Main's history", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    val updated = SubAgentConfig(
                        id = if (isNew) agentIdSlug(name) else agent!!.id,
                        name = name, role = role, description = description,
                        systemPrompt = systemPrompt, toolUids = toolUids.toList(),
                        workspaceFolder = workspaceFolder,
                        roundCap = roundCapText.toIntOrNull() ?: SubAgentConfig.DEFAULT_ROUND_CAP,
                        tokenBudget = tokenBudgetText.toIntOrNull() ?: SubAgentConfig.DEFAULT_TOKEN_BUDGET,
                        enabled = enabled,
                        modelProviderId = modelChoice?.first, model = modelChoice?.second,
                        historyVisibility = history,
                    )
                    val result = if (isNew) store.addAgent(updated) else store.updateAgent(updated)
                    result.onSuccess { onBack() }.onFailure { error = it.message }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (isNew) "Add agent" else "Save") }
            if (!isNew) {
                TextButton(
                    onClick = { showDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete agent", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (showDelete && agent != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete ${agent.name}?") },
            text = { Text("Main will no longer be able to call this agent. Its past messages and journal entries stay.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDelete = false
                        store.removeAgent(agent.id)
                            .onSuccess { onBack() }
                            .onFailure { error = it.message }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
}
