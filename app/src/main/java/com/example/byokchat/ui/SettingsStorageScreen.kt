
package com.example.byokchat.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsStorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val workspaceUris by settings.workspaceUris.collectAsState()
    val sessionStorageUri by settings.sessionStorageUri.collectAsState()
    val accessible by container.fs.accessible.collectAsState()
    val allowedSubfolder by settings.allowedWriteSubfolder.collectAsState()
    var subfolderText by remember { mutableStateOf(allowedSubfolder) }
    var workspaceError by remember { mutableStateOf<String?>(null) }
    var confirmClearSession by remember { mutableStateOf(false) }
    val pickWorkspace = rememberFolderPicker(onError = { workspaceError = it })
    val pickSession = rememberSessionFolderPicker()
    val workspacePaths by produceState<List<String>>(emptyList(), workspaceUris, accessible) {
        value = withContext(Dispatchers.IO) { container.fs.displayPaths() }
    }
    val workspaceAccess by produceState<Map<String, Boolean>>(emptyMap(), workspaceUris) {
        value = withContext(Dispatchers.IO) {
            workspaceUris.associateWith { container.fs.isFolderAccessible(it) }
        }
    }
    val sessionPath by produceState<String?>(null, sessionStorageUri) {
        value = withContext(Dispatchers.IO) { container.fs.displaySessionPath() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage & playground") },
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
            StorageSectionTitle("Select AI playground")
            if (workspaceUris.isEmpty()) {
                ListItem(
                    headlineContent = { Text("No folder chosen") },
                    supportingContent = { Text("File tools are blocked until you add a folder.") },
                )
            } else {
                workspaceUris.forEachIndexed { index, uri ->
                    val path = workspacePaths.getOrNull(index) ?: "./Selected folder"
                    ListItem(
                        headlineContent = { Text(path) },
                        supportingContent = {
                            Text(
                                if (workspaceAccess[uri] == true) "Available to AI tools."
                                else "Access unavailable. Re-select this folder to restore access.",
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) { container.fs.removeWorkspaceFolder(uri) }
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove $path")
                            }
                        },
                    )
                }
            }
            if (workspaceUris.isNotEmpty()) {
                Text(
                    if (accessible) "The AI sees these folders under one virtual workspace root."
                    else "File tools are blocked until at least one selected folder is accessible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Button(onClick = pickWorkspace, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Add folder")
            }
            if (workspaceUris.isNotEmpty() && accessible) {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val msg = container.fs.selfTest()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Check read/write access") }
            }

            StorageSectionTitle("Session storage")
            ListItem(
                headlineContent = { Text(sessionPath ?: "Not chosen") },
                supportingContent = {
                    Text("Where the app saves each chat as sessions/<date>_<session-id>/history.md. Separate from the AI playground.")
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = pickSession) { Text(if (sessionStorageUri == null) "Choose folder" else "Change folder") }
                if (sessionStorageUri != null) {
                    TextButton(onClick = { confirmClearSession = true }) { Text("Remove") }
                }
            }

            StorageSectionTitle("Allowed write subfolder")
            OutlinedTextField(
                value = subfolderText,
                onValueChange = { subfolderText = it; settings.setAllowedWriteSubfolder(it) },
                label = { Text("Subfolder the AI may write in") },
                singleLine = true,
                supportingText = {
                    Text("Empty means the AI can write anywhere inside each selected playground folder. Example: notes — then every AI write must stay under notes/.")
                },
                modifier = Modifier.padding(vertical = 4.dp),
            )

            StorageSectionTitle("What the AI may write")
            Text(
                "Text tools can only write text files — files whose content is plain text, whatever the extension. " +
                    "Other tools keep the file type their job needs, but every AI write must stay inside the allowed subfolder. " +
                    "This limit is always on; a blocked write is reported to the AI instead of asking you first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    workspaceError?.let { message ->
        AlertDialog(
            onDismissRequest = { workspaceError = null },
            title = { Text("Folder name conflict") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { workspaceError = null }) { Text("OK") } },
        )
    }
    if (confirmClearSession) {
        ConfirmDialog(
            title = "Remove the session storage folder?",
            message = "The app will forget this folder. The files in the folder are not affected.",
            confirmLabel = "Remove",
            onConfirm = { confirmClearSession = false; scope.launch(Dispatchers.IO) { container.fs.clearSessionStorage() } },
            onDismiss = { confirmClearSession = false },
        )
    }
}

@Composable
private fun StorageSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
