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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

/**
 * Màn chi tiết "Select Storage and Playground folder" (Đợt 7).
 * - Workspace: dời nguyên nội dung cũ từ SettingsScreen (picker, trạng
 *   thái, Remove, Check read/write access).
 * - Session storage: thư mục riêng lưu sessions/<ngày>_<session-id>/
 *   history.md + history.meta.json (setting session_storage_uri, Đợt E);
 *   chưa chọn thì dùng chung thư mục workspace.
 * - Allowed write subfolder: setting đã có từ Đợt E.
 * - Giới hạn chỉ ghi .md: LUÔN BẬT, không có công tắc — txt.* WRITE chỉ
 *   được ghi file .md/.markdown (ToolRegistry.aiWriteViolation); các tool
 *   ghi khác giữ đuôi theo đúng chức năng (.zip/.json/.csv...) nhưng vẫn
 *   phải nằm dưới subfolder cho phép. Màn này chỉ hiện thông tin, không
 *   bịa thêm công tắc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsStorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val workspaceUri by settings.workspaceUri.collectAsState()
    val sessionStorageUri by settings.sessionStorageUri.collectAsState()
    val accessible by container.fs.accessible.collectAsState()
    val allowedSubfolder by settings.allowedWriteSubfolder.collectAsState()
    var subfolderText by remember { mutableStateOf(allowedSubfolder) }
    var confirmClearWorkspace by remember { mutableStateOf(false) }
    var confirmClearSession by remember { mutableStateOf(false) }
    val pickWorkspace = rememberFolderPicker()
    val pickSession = rememberSessionFolderPicker()
    // Reading the folder name touches the document provider, so it is done off the main thread.
    val workspacePath by produceState<String?>(null, workspaceUri, accessible) {
        value = withContext(Dispatchers.IO) { container.fs.displayPath() }
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
            // ---------- Workspace ----------
            StorageSectionTitle("Workspace folder")
            ListItem(
                headlineContent = { Text(if (workspaceUri == null) "No folder chosen" else workspacePath ?: "Folder selected") },
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = pickWorkspace) { Text(if (workspaceUri == null) "Choose folder" else "Change folder") }
                if (workspaceUri != null) {
                    TextButton(onClick = { confirmClearWorkspace = true }) { Text("Remove") }
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
                    modifier = Modifier.padding(vertical = 4.dp),
                ) { Text("Check read/write access") }
            }

            // ---------- Session storage ----------
            StorageSectionTitle("Session storage")
            ListItem(
                headlineContent = { Text(sessionPath ?: "Not chosen — uses the workspace folder") },
                supportingContent = {
                    Text("Where the app saves each chat as sessions/<date>_<session-id>/history.md. Separate from the workspace where the AI plays.")
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = pickSession) { Text(if (sessionStorageUri == null) "Choose folder" else "Change folder") }
                if (sessionStorageUri != null) {
                    TextButton(onClick = { confirmClearSession = true }) { Text("Remove") }
                }
            }

            // ---------- Allowed write subfolder ----------
            StorageSectionTitle("Allowed write subfolder")
            OutlinedTextField(
                value = subfolderText,
                onValueChange = { subfolderText = it; settings.setAllowedWriteSubfolder(it) },
                label = { Text("Subfolder the AI may write in") },
                singleLine = true,
                supportingText = { Text("Empty means the AI can write anywhere in the workspace. Example: notes — then every AI write must stay under notes/.") },
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // ---------- Text-only writes (always on, no switch) ----------
            StorageSectionTitle("What the AI may write")
            Text(
                "Text tools can only write text files — files whose content is plain text, " +
                    "whatever the extension (.md, .txt, .json, .csv, .html, …). " +
                    "Other tools keep the file type their job needs (.zip, .json, .csv, …), " +
                    "but every AI write must stay inside the subfolder above. " +
                    "This limit is always on — there is no switch for it — and a blocked " +
                    "write is reported to the AI instead of asking you first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClearWorkspace) {
        ConfirmDialog(
            title = "Remove the workspace folder?",
            message = "The app will forget its access to this folder. The files in the folder are not affected.",
            confirmLabel = "Remove",
            onConfirm = { confirmClearWorkspace = false; scope.launch(Dispatchers.IO) { container.fs.clear() } },
            onDismiss = { confirmClearWorkspace = false },
        )
    }
    if (confirmClearSession) {
        ConfirmDialog(
            title = "Remove the session storage folder?",
            message = "The app will forget this folder and save sessions under the workspace folder again. The files in the folder are not affected.",
            confirmLabel = "Remove",
            onConfirm = { confirmClearSession = false; scope.launch(Dispatchers.IO) { container.fs.clearSessionStorage() } },
            onDismiss = { confirmClearSession = false },
        )
    }
}

/** Tiêu đề mục trong màn Storage: màn này đã có padding ngang 16dp nên
 *  không dùng SectionTitle chung (nó cộng thêm start 16dp). */
@Composable
private fun StorageSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
