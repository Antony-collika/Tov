package com.example.byokchat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings (Đợt 7): chỉ còn DANH SÁCH THẺ — tên + một dòng tóm tắt, ấn vào
 * mở màn chi tiết — cùng chuẩn thẻ với Request panel. Không còn điều khiển
 * inline nào trên màn này: web search, workspace, theme đã dời sang màn
 * chi tiết; switch include_usage đã dời sang từng provider (Đợt 7); hàng
 * Request log đã xóa — công tắc bật/tắt log vẫn nằm trên chính màn
 * Request log, nút mở log nằm trên Request panel cạnh Preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProviders: () -> Unit,
    onTools: () -> Unit,
    onNotes: () -> Unit,
    onRequestPanel: () -> Unit,
    onWebSearch: () -> Unit,
    onStorage: () -> Unit,
    onAppearance: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val theme by settings.themeMode.collectAsState()
    val providers by settings.providers.collectAsState()
    val searchProvider by settings.searchProvider.collectAsState()
    val searchKey by settings.searchApiKey.collectAsState()
    val playgroundUris by settings.playgroundUris.collectAsState()
    val sessionStorageUri by settings.sessionStorageUri.collectAsState()
    // Reading the folder name touches the document provider, so it is done off the main thread.
    val playgroundPaths by produceState<List<String>>(emptyList(), playgroundUris) {
        value = withContext(Dispatchers.IO) { container.fs.playgroundDisplayPaths() }
    }
    val sessionPath by produceState<String?>(null, sessionStorageUri) {
        value = withContext(Dispatchers.IO) { container.fs.displaySessionPath() }
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
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsCard(
                title = "AI providers",
                summary = if (providers.isEmpty()) "No providers yet" else providers.joinToString(", ") { it.name },
                onClick = onProviders,
            )
            SettingsCard(
                title = "System prompt & request panel",
                summary = "What the AI sees, and in which order — system prompt, time tool, history and tools",
                onClick = onRequestPanel,
            )
            SettingsCard(
                title = "Manage tools",
                summary = "Turn tools on or off and choose how much control you keep. The name and description the AI sees are managed in the Tools card file.",
                onClick = onTools,
            )
            SettingsCard(
                title = "Web search",
                summary = (if (searchProvider == "TAVILY") "Tavily" else "Brave Search") +
                    if (searchKey.isBlank()) " · No API key — web search is not offered" else " · API key saved",
                onClick = onWebSearch,
            )
            SettingsCard(
                title = "Select Storage and Playground folder",
                summary = when {
                    playgroundUris.isEmpty() -> "No folder chosen"
                    playgroundUris.size > 3 -> "Playground: ${playgroundUris.size} folders · Storage: ${sessionPath ?: "not chosen"}"
                    sessionStorageUri == null -> "Playground: ${playgroundPaths.joinToString(", ")} · Storage: not chosen"
                    else -> "Playground: ${playgroundPaths.joinToString(", ")} · Storage: ${sessionPath ?: "Folder selected"}"
                },
                onClick = onStorage,
            )
            SettingsCard(
                title = "Notes & to-dos",
                summary = "See what the AI has saved for you",
                onClick = onNotes,
            )
            SettingsCard(
                title = "Appearance",
                summary = "Theme: ${theme.label}",
                onClick = onAppearance,
            )
        }
    }
}

/** Một thẻ Settings: tên + một dòng tóm tắt, cùng chuẩn thẻ với Request panel. */
@Composable
private fun SettingsCard(title: String, summary: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
