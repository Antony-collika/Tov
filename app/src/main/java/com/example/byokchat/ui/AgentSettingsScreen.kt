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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.byokchat.chat.AgentConfigMd
import com.example.byokchat.chat.HistoryVisibility
import com.example.byokchat.chat.ParsedAgentConfig
import com.example.byokchat.chat.SubAgentConfig
import com.example.byokchat.chat.agentIdSlug
import com.example.byokchat.container
import com.example.byokchat.tools.SubAgentTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var folderError by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showToolPicker by remember { mutableStateOf(false) }
    // Config file (Stage 2): an optional external file this agent's fields
    // sync with. Blank = the fields live in agents.json only.
    var configFile by remember(key) { mutableStateOf(agent?.configFile ?: "") }
    var configFileError by remember { mutableStateOf<String?>(null) }
    var syncNote by remember { mutableStateOf<String?>(null) }
    var configFileName by remember(configFile) { mutableStateOf(configFile.substringAfterLast('/').ifEmpty { configFile }) }
    val scope = rememberCoroutineScope()

    /** The agent as the form stands now (id slugged from the name for a new agent). */
    fun formConfig() = SubAgentConfig(
        id = if (isNew) agentIdSlug(name) else agent!!.id,
        name = name, role = role, description = description,
        systemPrompt = systemPrompt, toolUids = toolUids.toList(),
        workspaceFolder = workspaceFolder,
        roundCap = roundCapText.toIntOrNull() ?: SubAgentConfig.DEFAULT_ROUND_CAP,
        tokenBudget = tokenBudgetText.toIntOrNull() ?: SubAgentConfig.DEFAULT_TOKEN_BUDGET,
        enabled = enabled,
        modelProviderId = modelChoice?.first, model = modelChoice?.second,
        historyVisibility = history,
        configFile = configFile.trim().ifEmpty { null },
    )

    /** Fill the form from a parsed config file (only the fields the file has). */
    fun applyParsed(p: ParsedAgentConfig) {
        p.name?.let { name = it }
        p.role?.let { role = it }
        p.description?.let { description = it }
        p.systemPrompt?.let { systemPrompt = it }
        p.workspaceFolder?.let { workspaceFolder = it }
        p.toolUids?.let { toolUids = it.toSet() }
        if (p.modelProviderId != null && p.model != null) modelChoice = p.modelProviderId to p.model
        p.roundCap?.let { roundCapText = it.toString() }
        p.tokenBudget?.let { tokenBudgetText = it.toString() }
        p.historyVisibility?.let { history = it }
        p.enabled?.let { enabled = it }
    }

    // Pick a config file: parse it at once and fill the fields with it.
    // Write access is kept so saving can write the fields back to it.
    val pickConfigFile = rememberCardFilePicker(
        onPicked = { path ->
            configFile = path
            configFileError = null
            syncNote = null
            scope.launch(Dispatchers.IO) {
                val result = runCatching { AgentConfigMd.parse(container.fs.readCardFile(path)) }
                withContext(Dispatchers.Main) {
                    result.onSuccess { parsed ->
                        applyParsed(parsed)
                        syncNote = "Filled from the linked file."
                    }.onFailure { e ->
                        configFileError = "Could not read the file: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        },
        onPickError = { configFileError = it },
        writeAccess = true,
    )

    // Pick the workspace folder with the system folder picker (GĐ4
    // batch): no more typing a folder name by hand. Picking adds the
    // folder to the AI playground; the agent just stores its name.
    val pickAgentFolder = rememberAgentFolderPicker(
        onPicked = { name -> workspaceFolder = name; folderError = null },
        onPickError = { folderError = it },
    )

    // Display name of the linked file (a URI needs a provider lookup).
    LaunchedEffect(configFile) {
        if (configFile.isNotBlank()) {
            configFileName = withContext(Dispatchers.IO) { container.fs.cardFileDisplayName(configFile) }
        }
    }

    // Two-way sync, change-triggered ONLY — no file watcher, no polling,
    // no background sync (performance: the app must not slow down watching
    // a file). Only two sync points exist: (a) here, when this screen
    // opens for a linked agent — the file is re-read once, and when its
    // content differs from the stored fields the file wins and the fields
    // are filled from it; (b) on save, the fields are written back to the
    // file. Nothing else reads or writes the file.
    LaunchedEffect(key) {
        val stored = agent ?: return@LaunchedEffect
        val path = stored.configFile?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val parsed = withContext(Dispatchers.IO) {
            runCatching { AgentConfigMd.parse(container.fs.readCardFile(path)) }
        }
        parsed.onSuccess { p ->
            if (p.differsFrom(stored)) {
                applyParsed(p)
                syncNote = "Updated from the linked file."
            }
        }.onFailure { e ->
            configFileError =
                "Could not read the linked file (${e.message ?: e.javaClass.simpleName}); showing the saved fields."
        }
    }

    /**
     * Create a new template file in Storage/template/ and link this agent
     * to it. The file starts from the form when the agent already has a
     * name; otherwise from the blank template (and the form is filled
     * with it, so a new agent has a starting point).
     */
    fun createTemplate() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (container.fs.sessionRootOrNull() == null) {
                    error("Choose a Session storage folder first (Settings → Select Storage and Playground folder).")
                }
                val fromForm = name.isNotBlank()
                val content = if (fromForm) AgentConfigMd.serialize(formConfig()) else AgentConfigMd.agentTemplate()
                val relPath = "template/" + uniqueTemplateName("agent_template.md") { n ->
                    container.fs.findSession("template/$n") != null
                }
                container.fs.writeSessionText(relPath, content)
                val doc = container.fs.findSession(relPath)
                    ?: error("Could not find the created file.")
                val uriString = doc.uri.toString()
                runCatching { container.fs.takeCardFilePermission(doc.uri) }
                container.fs.readCardFile(uriString)
                uriString to fromForm
            }.onSuccess { (uriString, fromForm) ->
                withContext(Dispatchers.Main) {
                    configFile = uriString
                    configFileError = null
                    if (!fromForm) {
                        applyParsed(AgentConfigMd.parse(AgentConfigMd.agentTemplate()))
                        syncNote = "Filled from the new template file."
                    }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { configFileError = e.message ?: "Could not create the file." }
            }
        }
    }

    // Tools a sub may use: everything except the delegate, stop and
    // recall tools (no sub-subs, no stopping or recalling from a sub).
    // The recruit tool IS pickable: a creator agent (BotFather-style)
    // holds it, so Main can delegate hiring to it.
    // The send_message tool is added to every run by the runner, so it is
    // not a checkbox below — it is shown as a built-in row instead (always
    // on, Main cannot use it).
    val pickableTools = container.registry.all.filter { !it.uid.startsWith("sub.") || it.uid == SubAgentTools.RECRUIT_TOOL_UID }
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
            Text("Config file", style = MaterialTheme.typography.labelLarge)
            if (configFile.isBlank()) {
                Text("(no file linked)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(configFileName, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Link a file and the fields stay in sync with it: opening this screen reads the file, saving writes the fields back. The file is a plain markdown file — edit it in any editor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                TextButton(onClick = { pickConfigFile.launch(arrayOf("*/*")) }) {
                    Text(if (configFile.isBlank()) "Choose file" else "Change file")
                }
                if (configFile.isNotBlank()) {
                    TextButton(onClick = {
                        configFileError = null
                        openFileAt(context, container.fs, configFile.trim(), onError = { configFileError = it })
                    }) { Text("Open file") }
                }
                TextButton(onClick = ::createTemplate) { Text("Create template") }
                if (configFile.isNotBlank()) {
                    TextButton(onClick = { configFile = ""; syncNote = null }) { Text("Unlink file") }
                }
            }
            if (configFileError != null) {
                Text(configFileError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (syncNote != null) {
                Text(syncNote!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("Workspace folder", style = MaterialTheme.typography.labelLarge)
            if (workspaceFolder.isBlank()) {
                Text("(no folder chosen)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(workspaceFolder, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "The sub works inside this folder in the AI playground; picking a folder adds it to the playground.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = pickAgentFolder) { Text(if (workspaceFolder.isBlank()) "Choose folder" else "Change folder") }
                if (workspaceFolder.isNotBlank()) {
                    TextButton(onClick = { workspaceFolder = ""; folderError = null }) { Text("Clear folder") }
                }
            }
            if (folderError != null) {
                Text(folderError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
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
            Text(
                toolSelectionSummary(toolUids, pickableTools.associate { it.uid to it.defaultName }),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { showToolPicker = true }) { Text("Choose tools") }
            if (showToolPicker) {
                ToolPickerSheet(
                    tools = pickableTools,
                    selected = toolUids,
                    onSelectionChange = { toolUids = it },
                    onDismiss = { showToolPicker = false },
                )
            }
            // Built-in, not a checkbox: the runner adds send_message to
            // every sub run, and Main cannot use it (it is not in the tool
            // registry). Shown so the user can see what the sub can do.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = true, onCheckedChange = null, enabled = false)
                Column {
                    Text("send_message", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "sub.send — built-in, always on. Only the sub can use it, to send a message to the user or to Main. Main cannot call it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("Model", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showModelPicker = true }) { Text(modelLabel) }
            }
            if (showModelPicker) {
                ModelPickerSheet(
                    providers = providers,
                    providerId = modelChoice?.first ?: "",
                    model = modelChoice?.second ?: "",
                    refreshing = false,
                    onSelect = { pid, m -> modelChoice = pid to m; showModelPicker = false },
                    onTogglePin = { pid, m ->
                        val cfg = container.settings.provider(pid)
                        if (cfg != null) {
                            val pinned = if (m in cfg.pinnedModels) cfg.pinnedModels - m else cfg.pinnedModels + m
                            container.settings.saveProvider(cfg.copy(pinnedModels = pinned))
                        }
                    },
                    // No provider setup flow from here — connect an AI in
                    // Settings, then come back to pick its models.
                    onConnect = { showModelPicker = false },
                    onDismiss = { showModelPicker = false },
                    onFollowMain = { modelChoice = null; showModelPicker = false },
                    followMainSelected = modelChoice == null,
                )
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
                    val updated = formConfig()
                    val result = if (isNew) store.addAgent(updated) else store.updateAgent(updated)
                    if (result.isFailure) {
                        error = result.exceptionOrNull()?.message
                    } else {
                        // Saved in agents.json. When a file is linked, write
                        // the fields back to it (sync point (b)); a write
                        // failure keeps the agent saved but stays on this
                        // screen with the error shown.
                        val path = updated.configFile
                        if (path == null) {
                            onBack()
                        } else {
                            scope.launch(Dispatchers.IO) {
                                val write = runCatching {
                                    container.fs.writeCardFile(path, AgentConfigMd.serialize(updated))
                                }
                                withContext(Dispatchers.Main) {
                                    write.onSuccess { onBack() }
                                        .onFailure {
                                            error = "Saved, but could not write back to the linked file: " +
                                                (it.message ?: it.javaClass.simpleName)
                                        }
                                }
                            }
                        }
                    }
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
