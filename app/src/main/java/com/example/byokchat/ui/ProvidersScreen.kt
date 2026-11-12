package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import com.example.byokchat.container
import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(onBack: () -> Unit, onAdd: (ProviderType) -> Unit, onEdit: (String) -> Unit) {
    val settings = LocalContext.current.container.settings
    val providers by settings.providers.collectAsState()
    val activeId by settings.activeProviderId.collectAsState()
    var addMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI providers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { addMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add provider")
                        }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            ProviderType.entries.forEach { t ->
                                DropdownMenuItem(text = { Text(t.label) }, onClick = { addMenu = false; onAdd(t) })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        // Đợt bỏ provider 12/11/2026: thông báo một lần khi provider đã lưu
        // được migrate (DeepSeek → Custom, Anthropic bị gỡ) — hiện trên đầu
        // danh sách cho tới khi user tắt.
        val notice by settings.providerMigrationNotice.collectAsState()
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (notice != null) {
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(notice!!, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { settings.dismissProviderMigrationNotice() }) { Text("Dismiss") }
                        }
                    }
                }
            }
            if (providers.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Tap + in the top corner to add an AI provider.")
                    }
                }
            } else {
                items(providers, key = { it.id }) { p: ProviderConfig ->
                    ListItem(
                        modifier = Modifier.fillMaxWidth().clickable { onEdit(p.id) },
                        headlineContent = { Text(p.name + if (p.id == activeId) "  (default)" else "") },
                        supportingContent = {
                            Text(
                                p.type.label +
                                    if (p.apiKey.isBlank() && p.type != ProviderType.CUSTOM) " · missing API key" else ""
                            )
                        },
                    )
                }
            }
        }
    }
}
