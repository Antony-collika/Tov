package com.example.byokchat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.byokchat.container
import com.example.byokchat.tools.ToolMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Colours hardcoded from the Settings mockup (04-settings.png) for now —
// the theme system comes in a later stage, do not build it here.
private val SettingsWhite = Color(0xFFFFFFFF)
private val SettingsInk = Color(0xFF191C20)
private val SettingsMuted = Color(0xFF5A5E66)
private val SettingsSectionPurple = Color(0xFF7E57C2)
private val SettingsCardBorder = Color(0xFFE6E4EA)
private val SettingsDivider = Color(0xFFF0F0F3)
private val SettingsChevron = Color(0xFF8A8D94)
private val SettingsBadgeBackground = Color(0xFFE6F4EA)
private val SettingsBadgeText = Color(0xFF137333)

/**
 * Settings (UI round 27/11/2026, per dac-ta-settings.md): grouped cards —
 * Connections, Intelligence, Capabilities, Data & Storage, Appearance —
 * each card opens its detail screen; no controls inline on this screen.
 * Badge counts and summaries are real data from the device, never the
 * mockup's sample values. Diagnostics was removed 27/11/2026 (spec §3).
 * Notes & to-dos sits in Intelligence, below the System prompt card
 * (Duy's decision 27/11/2026).
 */
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
    onBackup: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val theme by settings.themeMode.collectAsState()
    val providers by settings.providers.collectAsState()
    val searchProvider by settings.searchProvider.collectAsState()
    val searchKey by settings.searchApiKey.collectAsState()
    val youtubeKey by settings.youtubeApiKey.collectAsState()
    val playgroundUris by settings.playgroundUris.collectAsState()
    val sessionStorageUri by settings.sessionStorageUri.collectAsState()
    // Reading the folder name touches the document provider, so it is done off the main thread.
    val playgroundPaths by produceState<List<String>>(emptyList(), playgroundUris) {
        value = withContext(Dispatchers.IO) { container.fs.playgroundDisplayPaths() }
    }
    val sessionPath by produceState<String?>(null, sessionStorageUri) {
        value = withContext(Dispatchers.IO) { container.fs.displaySessionPath() }
    }
    // Enabled tools = every registered tool whose mode is not Off. The
    // registry reads the live prefs, so touch the collected map to
    // recompose when a tool is toggled in Manage tools.
    val toolPrefs by container.toolPrefs.prefs.collectAsState()
    @Suppress("UNUSED_VARIABLE") val touchToolPrefs = toolPrefs.size
    val enabledToolCount = container.registry.all.count { container.registry.mode(it) != ToolMode.OFF }

    Scaffold(
        containerColor = SettingsWhite,
        topBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = SettingsInk,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Text(
                        "Settings",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SettingsInk,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                HorizontalDivider(color = SettingsDivider, thickness = 1.dp)
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().background(SettingsWhite).padding(padding)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionTitle("Connections")
            SettingsCard(
                title = "AI providers",
                badge = "${providers.size} configured",
                summary = if (providers.isEmpty()) "No providers yet" else providers.joinToString(", ") { it.name },
                onClick = onProviders,
            )
            SettingsCard(
                title = "Web search",
                summary = (when (searchProvider) {
                    "TAVILY" -> "Tavily"
                    "DUCKDUCKGO" -> "DuckDuckGo"
                    "SERPER" -> "Serper"
                    else -> "Brave Search"
                }) +
                    when {
                        searchProvider == "DUCKDUCKGO" -> " · No API key needed"
                        searchKey.isBlank() -> " · No API key — web search is not offered"
                        else -> " · API key saved"
                    } +
                    if (youtubeKey.isBlank()) " · No YouTube key" else " · YouTube key saved",
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
            SettingsSectionTitle("Intelligence")
            SettingsCard(
                title = "System prompt & request panel",
                summary = "What the AI sees, and in which order — system prompt, time tool, history and tools",
                onClick = onRequestPanel,
            )
            SettingsCard(
                title = "Notes & to-dos",
                summary = "See what the AI has saved for you",
                onClick = onNotes,
            )
            SettingsSectionTitle("Capabilities")
            SettingsCard(
                title = "Manage tools",
                badge = "$enabledToolCount enabled",
                summary = "Turn tools on or off and choose how much control you keep. Edit the name and description the AI sees here.",
                onClick = onTools,
            )
            SettingsSectionTitle("Data & Storage")
            SettingsCard(
                title = "Backup",
                summary = "Save your providers and keys, sub-agents, chats and tool edits into one encrypted file — or restore one.",
                onClick = onBackup,
            )
            SettingsSectionTitle("Appearance")
            SettingsCard(
                title = "Appearance",
                summary = "Theme: ${theme.label}",
                onClick = onAppearance,
            )
        }
    }
}

/** A Settings group title: 13sp bold purple, 18dp above, 8dp below. */
@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        color = SettingsSectionPurple,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
    )
}

/**
 * One Settings card, per the mockup: 1dp #E6E4EA border, 16dp corners,
 * 14dp vertical / 16dp horizontal inner padding, 10dp below; title 15.5sp
 * bold on one line, summary 13.5sp / 1.45 line height, chevron at right,
 * optional green badge right after the title.
 */
@Composable
private fun SettingsCard(title: String, summary: String, badge: String? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = SettingsWhite,
        border = BorderStroke(1.dp, SettingsCardBorder),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = SettingsInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (badge != null) {
                    Text(
                        badge,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsBadgeText,
                        maxLines = 1,
                        modifier = Modifier.background(SettingsBadgeBackground, RoundedCornerShape(20.dp))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = SettingsChevron,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                summary,
                fontSize = 13.5.sp,
                lineHeight = 19.6.sp,
                color = SettingsMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
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
