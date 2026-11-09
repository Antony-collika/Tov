package com.example.byokchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.tools.Risk
import com.example.byokchat.tools.Tool
import com.example.byokchat.tools.ToolGroup
import com.example.byokchat.tools.ToolMode
import com.example.byokchat.tools.ToolRegistry

/** First sentence of a tool description, cut to [max] characters: the short line shown in the tool list. */
fun shortDescription(description: String, max: Int = 110): String {
    val flat = description.trim().replace(Regex("\\s+"), " ")
    val end = Regex("[.!?](\\s|$)").find(flat)?.range?.first
    val sentence = if (end != null) flat.substring(0, end + 1) else flat
    return if (sentence.length > max) sentence.take(max - 1).trimEnd() + "…" else sentence
}

/** What "Set all" does to every tool of a group. */
private enum class GroupChoice(val label: String) {
    DEFAULTS("Reset to defaults"),
    AUTO("All Auto (delete tools still ask)"),
    ASK("All Ask first"),
    OFF("All Off"),
}

private fun applyToGroup(registry: ToolRegistry, tools: List<Tool>, choice: GroupChoice) {
    tools.forEach { t ->
        val mode = when (choice) {
            GroupChoice.DEFAULTS -> t.defaultMode
            GroupChoice.AUTO -> if (t.risk == Risk.DELETE) ToolMode.ASK else ToolMode.AUTO
            GroupChoice.ASK -> ToolMode.ASK
            GroupChoice.OFF -> ToolMode.OFF
        }
        registry.setMode(t, mode)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val container = LocalContext.current.container
    val registry = container.registry
    val prefs by container.toolPrefs.prefs.collectAsState()
    val workspaceOk by container.fs.accessible.collectAsState()
    val searchKey by container.settings.searchApiKey.collectAsState()
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage tools") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        // The registry reads its data from plain flows, so touch the values that can change to refresh the list.
        @Suppress("UNUSED_VARIABLE") val touch = prefs.size + (if (workspaceOk) 1 else 0) + searchKey.length
        val q = query.trim()
        val groups = ToolGroup.entries.map { group ->
            group to registry.all.filter { t ->
                t.group == group && (q.isEmpty() ||
                    registry.effectiveName(t).contains(q, ignoreCase = true) ||
                    registry.effectiveDescription(t).contains(q, ignoreCase = true) ||
                    t.uid.contains(q, ignoreCase = true))
            }
        }.filter { it.second.isNotEmpty() }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Tap a tool to see its UID, rename it or change the description the AI sees, turn it on or off, and choose how much control you keep. Changes apply from the next message you send.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search tools") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        "No tools match.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            groups.forEach { (group, tools) ->
                item(key = "h_" + group.name) {
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionTitle("${group.label} (${tools.size})") }
                        Box {
                            TextButton(onClick = { menu = true }) { Text("Set all") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                GroupChoice.entries.forEach { choice ->
                                    DropdownMenuItem(
                                        text = { Text(choice.label) },
                                        onClick = { menu = false; applyToGroup(registry, tools, choice) },
                                    )
                                }
                            }
                        }
                    }
                }
                items(tools, key = { it.uid }) { t ->
                    val mode = registry.mode(t)
                    val name = registry.effectiveName(t)
                    val blocked = mode != ToolMode.OFF && !registry.isAvailable(t)
                    ListItem(
                        modifier = Modifier.clickable { onOpen(t.uid) },
                        headlineContent = { Text(name) },
                        supportingContent = {
                            Column {
                                Text(shortDescription(registry.effectiveDescription(t)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val notes = listOfNotNull(
                                    if (name != t.defaultName) "renamed" else null,
                                    if (blocked) "blocked: " + (if (t.needsWorkspace && !workspaceOk) "no workspace folder" else t.unavailableHint ?: "not ready") else null,
                                )
                                if (notes.isNotEmpty()) {
                                    Text(
                                        notes.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        },
                        trailingContent = { ModeBadge(mode, t.risk) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ModeBadge(mode: ToolMode, risk: Risk) {
    val color = when (mode) {
        ToolMode.AUTO -> MaterialTheme.colorScheme.primary
        ToolMode.ASK -> MaterialTheme.colorScheme.tertiary
        ToolMode.OFF -> MaterialTheme.colorScheme.outline
    }
    Text(
        mode.label + if (risk == Risk.DELETE && mode != ToolMode.OFF) " (always)" else "",
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailScreen(uid: String, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val registry = container.registry
    val tool: Tool? = registry.tool(uid)
    val prefs by container.toolPrefs.prefs.collectAsState()

    // Edits of the name/description that are not saved yet must not be lost silently.
    var name by remember(uid) { mutableStateOf(tool?.let { registry.effectiveName(it) } ?: "") }
    var desc by remember(uid) { mutableStateOf(tool?.let { registry.effectiveDescription(it) } ?: "") }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = tool != null && (name.trim() != registry.effectiveName(tool) || desc.trim() != registry.effectiveDescription(tool))
    BackHandler(enabled = dirty) { confirmDiscard = true }
    val goBack: () -> Unit = {
        if (dirty) {
            confirmDiscard = true
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tool == null) "Tool" else registry.effectiveName(tool)) },
                navigationIcon = {
                    IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (tool == null) {
            Text("This tool was not found.", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        // Read prefs so the screen refreshes when the mode changes.
        val mode = remember(prefs) { registry.mode(tool) }
        val nameError = registry.validateName(tool, name)
        val descError = registry.validateDescription(desc)

        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("UID (fixed, set by the app)", style = MaterialTheme.typography.labelLarge)
            Text(tool.uid, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Text("Original description (read-only)", style = MaterialTheme.typography.labelLarge)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Text(tool.defaultDescription, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Original name: ${tool.defaultName} · Type: " + when (tool.risk) {
                    Risk.READ -> "read-only"
                    Risk.WRITE -> "writes, edits or moves"
                    Risk.DELETE -> "deletes (always asks first)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("Control", style = MaterialTheme.typography.labelLarge)
            // One control for everything: Auto / Ask first / Off. Delete tools can never run on their own.
            val options = if (tool.risk == Risk.DELETE) listOf(ToolMode.ASK, ToolMode.OFF) else ToolMode.entries.toList()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { registry.setMode(tool, m) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        label = { Text(m.label) },
                    )
                }
            }
            if (tool.risk == Risk.DELETE) {
                Text("Delete tools always ask you first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Text("What the AI sees", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Tool name") },
                singleLine = true,
                isError = nameError != null,
                supportingText = { if (nameError != null) Text(nameError) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = desc,
                onValueChange = { desc = it },
                label = { Text("Description for the AI") },
                isError = descError != null,
                supportingText = { if (descError != null) Text(descError) },
                minLines = 4,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = dirty && nameError == null && descError == null,
                    onClick = { registry.setNameAndDescription(tool, name, desc); name = name.trim(); desc = desc.trim() },
                ) { Text("Save") }
                OutlinedButton(onClick = {
                    registry.restoreDefaults(tool)
                    name = tool.defaultName
                    desc = tool.defaultDescription
                }) { Text("Restore defaults") }
            }
            Text(
                "The technical parameters and the way the tool works are handled by the app; you only edit the name and description. When the AI calls a tool by its new name, the app maps it back to the UID, so older conversations keep working.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "Your edits to the name or description have not been saved.",
            confirmLabel = "Discard",
            onConfirm = { confirmDiscard = false; onBack() },
            onDismiss = { confirmDiscard = false },
        )
    }
}
