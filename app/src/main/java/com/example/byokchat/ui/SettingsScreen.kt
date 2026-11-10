package com.example.byokchat.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings, split into four clearly titled groups: AI, Tools, Data & workspace, Appearance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProviders: () -> Unit,
    onTools: () -> Unit,
    onNotes: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val theme by settings.themeMode.collectAsState()
    val providers by settings.providers.collectAsState()
    val logEnabled by settings.requestLogEnabled.collectAsState()
    val openAiIncludeUsage by settings.openAiIncludeUsage.collectAsState()
    val workspaceUri by settings.workspaceUri.collectAsState()
    val accessible by container.fs.accessible.collectAsState()
    val searchProvider by settings.searchProvider.collectAsState()
    var prompt by remember { mutableStateOf(settings.defaultSystemPrompt.value) }
    var searchKey by remember { mutableStateOf(settings.searchApiKey.value) }
    var showKey by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val pickFolder = rememberFolderPicker()
    // Reading the folder name touches the document provider, so it is done off the main thread.
    val workspacePath by produceState<String?>(null, workspaceUri, accessible) {
        value = withContext(Dispatchers.IO) { container.fs.displayPath() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            // ---------- AI ----------
            SectionTitle("AI")
            ListItem(
                modifier = Modifier.clickable(onClick = onProviders),
                headlineContent = { Text("AI providers") },
                supportingContent = { Text(if (providers.isEmpty()) "No providers yet" else providers.joinToString(", ") { it.name }) },
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it; settings.setDefaultSystemPrompt(it) },
                    label = { Text("Default system prompt") },
                    supportingText = { Text("Used for new conversations. Each conversation can still change its own.") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ListItem(
                headlineContent = { Text("Request log") },
                supportingContent = { Text("Records every request sent to the AI, with the real token counts. Stored only on this device.") },
                trailingContent = {
                    Switch(checked = logEnabled, onCheckedChange = { settings.setRequestLogEnabled(it) })
                },
            )
            ListItem(
                headlineContent = { Text("Ask OpenAI for real token counts") },
                supportingContent = { Text("Sends stream_options.include_usage so the log shows real counts. Some self-hosted servers reject this — turn it off if requests fail.") },
                trailingContent = {
                    Switch(checked = openAiIncludeUsage, onCheckedChange = { settings.setOpenAiIncludeUsage(it) })
                },
            )

            // ---------- Tools ----------
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Tools")
            ListItem(
                modifier = Modifier.clickable(onClick = onTools),
                headlineContent = { Text("Manage tools") },
                supportingContent = { Text("Turn tools on or off, choose how much control you keep, rename them and edit their descriptions") },
            )
            Text(
                "Web search (for the web_search tool)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            )
            val engines = listOf("BRAVE" to "Brave Search", "TAVILY" to "Tavily")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                engines.forEachIndexed { index, (id, label) ->
                    SegmentedButton(
                        selected = searchProvider == id,
                        onClick = { settings.setSearchProvider(id) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = engines.size),
                        label = { Text(label) },
                    )
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = searchKey,
                    onValueChange = { searchKey = it; settings.setSearchApiKey(it.trim()) },
                    label = { Text("Your search service API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                    supportingText = { Text("If empty, the web search tool is not offered to the AI. The key is encrypted on this device.") },
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---------- Data & workspace ----------
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Data & workspace")
            ListItem(
                headlineContent = { Text(if (workspaceUri == null) "No folder chosen" else workspacePath ?: "Folder selected") },
                overlineContent = { Text("Workspace folder") },
                supportingContent = {
                    Text(
                        when {
                            workspaceUri == null -> "File tools are blocked until you choose a folder."
                            accessible -> "Active. The AI can only see and change things inside this folder."
                            else -> "Access was lost or the folder no longer exists. Please choose it again."
                        }
                    )
                },
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = pickFolder) { Text(if (workspaceUri == null) "Choose folder" else "Change folder") }
                if (workspaceUri != null) {
                    TextButton(onClick = { confirmClear = true }) { Text("Remove") }
                }
            }
            if (workspaceUri != null) {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val msg = container.fs.selfTest()
                            launch(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Text("Check read/write access") }
            }
            ListItem(
                modifier = Modifier.clickable(onClick = onNotes),
                headlineContent = { Text("Notes & to-dos") },
                supportingContent = { Text("See what the AI has saved for you") },
            )

            // ---------- Appearance ----------
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Appearance")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = theme == mode,
                        onClick = { settings.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                        label = { Text(mode.label) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Remove the workspace folder?",
            message = "The app will forget its access to this folder. The files in the folder are not affected.",
            confirmLabel = "Remove",
            onConfirm = { confirmClear = false; scope.launch(Dispatchers.IO) { container.fs.clear() } },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
