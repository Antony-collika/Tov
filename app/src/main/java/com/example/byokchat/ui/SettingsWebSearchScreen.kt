package com.example.byokchat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.byokchat.container

/** Màn chi tiết "Web search" (Đợt 7): nội dung dời nguyên từ SettingsScreen cũ. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsWebSearchScreen(onBack: () -> Unit) {
    val settings = LocalContext.current.container.settings
    val searchProvider by settings.searchProvider.collectAsState()
    var searchKey by remember { mutableStateOf(settings.searchApiKey.value) }
    var showKey by remember { mutableStateOf(false) }
    var youtubeKey by remember { mutableStateOf(settings.youtubeApiKey.value) }
    var showYoutubeKey by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Web search") },
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
            Text("Search engine (for the web_search tool)")
            val engines = listOf("BRAVE" to "Brave Search", "TAVILY" to "Tavily")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                engines.forEachIndexed { index, (id, label) ->
                    SegmentedButton(
                        selected = searchProvider == id,
                        onClick = { settings.setSearchProvider(id) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = engines.size),
                        label = { Text(label) },
                    )
                }
            }
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
            Text("YouTube Data API v3 key (for the YouTube tools)", modifier = Modifier.padding(top = 16.dp))
            OutlinedTextField(
                value = youtubeKey,
                onValueChange = { youtubeKey = it; settings.setYoutubeApiKey(it.trim()) },
                label = { Text("Your YouTube Data API v3 key") },
                singleLine = true,
                visualTransformation = if (showYoutubeKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showYoutubeKey = !showYoutubeKey }) { Text(if (showYoutubeKey) "Hide" else "Show") } },
                supportingText = { Text("If empty, YouTube search and comments are not offered to the AI. Downloads and audio playback do not need this key. The key is encrypted on this device.") },
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
