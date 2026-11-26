package com.example.byokchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.tools.Risk
import com.example.byokchat.tools.Tool
import com.example.byokchat.tools.ToolGroup
import com.example.byokchat.tools.ToolMode
import com.example.byokchat.tools.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    val maxToolRounds by container.settings.maxToolRounds.collectAsState()
    val maxConsecutiveToolErrors by container.settings.maxConsecutiveToolErrors.collectAsState()
    val maxToolRoundTimeoutMinutes by container.settings.maxToolRoundTimeoutMinutes.collectAsState()
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
                    "Tap a tool to open, change permission and edit description",
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
                // Max tool rounds (16/11/2026): trước đây fix cứng 12 ở
                // ChatEngine, giờ chỉnh được ở đây (1–50). Chỉ nhận số;
                // số ngoài khoảng được kẹp lại lúc lưu, ô trống thì trả
                // lại giá trị đang lưu khi rời ô — không bao giờ lưu số
                // không hợp lệ, không bao giờ crash.
                var roundsText by remember(maxToolRounds) { mutableStateOf(maxToolRounds.toString()) }
                var roundsFocused by remember { mutableStateOf(false) }
                Text(
                    "Max tool rounds (1–50)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                OutlinedTextField(
                    value = roundsText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }
                        roundsText = digits
                        digits.toIntOrNull()?.let { container.settings.setMaxToolRounds(it) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .onFocusChanged { focus ->
                            if (roundsFocused && !focus.isFocused && roundsText.toIntOrNull() == null) {
                                roundsText = maxToolRounds.toString()
                            }
                            roundsFocused = focus.isFocused
                        },
                )
                Text(
                    "Maximum tool rounds Main could run in one turn.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                )
                // Max consecutive tool errors (24/11/2026): same pattern
                // as Max tool rounds above — one common number for every
                // tool, default 3. When the same tool fails this many
                // times in a row in one run, it is switched off for the
                // rest of that run; a new run starts counting again.
                var errorsText by remember(maxConsecutiveToolErrors) { mutableStateOf(maxConsecutiveToolErrors.toString()) }
                var errorsFocused by remember { mutableStateOf(false) }
                Text(
                    "Max consecutive tool errors (1–10)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                OutlinedTextField(
                    value = errorsText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }
                        errorsText = digits
                        digits.toIntOrNull()?.let { container.settings.setMaxConsecutiveToolErrors(it) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .onFocusChanged { focus ->
                            if (errorsFocused && !focus.isFocused && errorsText.toIntOrNull() == null) {
                                errorsText = maxConsecutiveToolErrors.toString()
                            }
                            errorsFocused = focus.isFocused
                        },
                )
                Text(
                    "Maximum consecutive error of the same tool-call.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                )
                // Max tool-round timeout (25/11/2026): same pattern as
                // above — minutes, default 3, no upper limit. One run of
                // one agent (Main's turn loop, or one sub-agent run), not
                // the whole turn: each run has its own time budget.
                var timeoutText by remember(maxToolRoundTimeoutMinutes) { mutableStateOf(maxToolRoundTimeoutMinutes.toString()) }
                var timeoutFocused by remember { mutableStateOf(false) }
                Text(
                    "Max tool-round timeout (minutes)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }
                        timeoutText = digits
                        digits.toIntOrNull()?.let { container.settings.setMaxToolRoundTimeoutMinutes(it) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .onFocusChanged { focus ->
                            if (timeoutFocused && !focus.isFocused && timeoutText.toIntOrNull() == null) {
                                timeoutText = maxToolRoundTimeoutMinutes.toString()
                            }
                            timeoutFocused = focus.isFocused
                        },
                )
                Text(
                    "Maximum duration one agent's tool round may run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
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
                                    if (blocked) "blocked: " + (if (t.needsWorkspace && !workspaceOk) "no AI playground folder" else t.unavailableHint ?: "not ready") else null,
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
    // RCP (17/11/2026): mô tả model thấy sửa được tại đây (không còn qua
    // file tools.md). Lưu khi ô mất focus hoặc khi rời màn hình — màn này
    // không có nút Save, như chế độ Auto/Ask/Off lưu ngay khi bấm.
    var descText by remember(uid) { mutableStateOf(tool?.let { registry.effectiveDescription(it) }.orEmpty()) }
    var descFocused by remember(uid) { mutableStateOf(false) }
    fun saveDescription() {
        val t = tool ?: return
        if (descText != registry.effectiveDescription(t)) registry.setDescription(t, descText)
    }

    BackHandler { saveDescription(); onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tool == null) "Tool" else tool.defaultName) },
                navigationIcon = {
                    IconButton(onClick = { saveDescription(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
        val hasOverride = prefs[tool.uid]?.description != null

        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("UID (fixed, set by the app)", style = MaterialTheme.typography.labelLarge)
            Text(tool.uid, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Text("Original name", style = MaterialTheme.typography.labelLarge)
            Text(tool.defaultName, style = MaterialTheme.typography.bodyLarge)
            Text("Original description (read-only)", style = MaterialTheme.typography.labelLarge)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Text(tool.defaultDescription, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Type: " + when (tool.risk) {
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
            // A tool that is turned off is still offered to the model (Đợt E, quyết định 8) —
            // the model is told it is turned off only when it tries to call it.
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
            Text("Name", style = MaterialTheme.typography.labelLarge)
            Text(tool.defaultName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "The name is fixed — it cannot be changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Description (what the AI sees)", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = descText,
                onValueChange = { descText = it },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { focus ->
                        if (descFocused && !focus.isFocused) saveDescription()
                        descFocused = focus.isFocused
                    },
            )
            if (hasOverride) {
                TextButton(onClick = {
                    descText = tool.defaultDescription
                    registry.setDescription(tool, tool.defaultDescription)
                }) { Text("Use original description") }
            }
            Text(
                "The description the AI sees.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("Parameters", style = MaterialTheme.typography.labelLarge)
            Text(
                "What each parameter means to the AI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ParametersSection(tool)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Danh sách tham số của công cụ, chỉ xem (Đợt E: bỏ sửa mô tả tham số). */
@Composable
private fun ParametersSection(tool: Tool) {
    val container = LocalContext.current.container
    val registry = container.registry
    val props = remember(tool) {
        (tool.schema["properties"] as? JsonObject)?.entries?.toList().orEmpty()
    }
    if (props.isEmpty()) {
        Text(
            "This tool takes no parameters.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val required = remember(tool) {
        (tool.schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        props.forEach { (paramName, schemaEl) ->
            val paramObj = schemaEl as? JsonObject
            val type = (paramObj?.get("type") as? JsonPrimitive)?.contentOrNull
            val desc = registry.paramDescription(tool, paramName)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    paramName + (if (paramName in required) " (required)" else "") + (type?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (desc.isBlank()) "(no description)" else desc,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
