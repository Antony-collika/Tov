package com.example.byokchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.tools.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn chi tiết "Select Storage and Playground folder".
 * - Select AI playground: mỗi folder đã chọn một dòng "./tên" kèm ❌ (xóa
 *   có hộp xác nhận); nút "Add folder" luôn thêm mới, không đổi folder.
 *   Trùng tên (kể cả chọn lại đúng folder cũ) thì báo popup, không lưu.
 *   Mỗi dòng có trạng thái truy cập riêng; nút "Check read/write access"
 *   kiểm tra từng folder và báo kết quả theo từng dòng.
 * - Session storage: thư mục riêng lưu sessions/<ngày>_<session-id>/
 *   history.md + history.meta.json, notes/, to-dos/, template/
 *   (setting session_storage_uri, Đợt E); chưa chọn thì không có
 *   session root — chat vẫn chạy, chỉ không lưu file ra ngoài.
 * - Allowed write subfolder: setting đã có từ Đợt E, áp dụng BÊN TRONG
 *   từng folder playground.
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
    val playgroundUris by settings.playgroundUris.collectAsState()
    val sessionStorageUri by settings.sessionStorageUri.collectAsState()
    val accessible by container.fs.accessible.collectAsState()
    val allowedSubfolder by settings.allowedWriteSubfolder.collectAsState()
    var subfolderText by remember { mutableStateOf(allowedSubfolder) }
    var confirmRemove by remember { mutableStateOf<WorkspaceFs.RootAccess?>(null) }
    var confirmClearSession by remember { mutableStateOf(false) }
    var conflictName by remember { mutableStateOf<String?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var selfTestResult by remember { mutableStateOf<String?>(null) }
    val pickPlayground = rememberFolderPicker(
        onConflict = { conflictName = it },
        onPickError = { pickError = it },
    )
    val pickSession = rememberSessionFolderPicker()
    // Reading folder names touches the document provider, so it is done off the main thread.
    val accessDetails by produceState<List<WorkspaceFs.RootAccess>>(emptyList(), playgroundUris, accessible) {
        value = withContext(Dispatchers.IO) { container.fs.accessDetails() }
    }
    val sessionPath by produceState<String?>(null, sessionStorageUri) {
        value = withContext(Dispatchers.IO) { container.fs.displaySessionPath() }
    }
    val duplicateNames = remember(accessDetails) { container.fs.duplicateRootNames() }
    val lostNames = remember(accessDetails) { accessDetails.filter { !it.ok }.map { "./${it.name}" } }

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
            // ---------- Select AI playground ----------
            StorageSectionTitle("Select AI playground")
            if (playgroundUris.isEmpty()) {
                ListItem(
                    headlineContent = { Text("No folder chosen") },
                    supportingContent = { Text("File tools are blocked until you add a folder.") },
                )
            } else {
                for (entry in accessDetails) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "./${entry.name}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        IconButton(onClick = { confirmRemove = entry }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ./${entry.name}")
                        }
                    }
                    if (!entry.ok) {
                        Text(
                            "Access was lost or this folder no longer exists. Remove it and add it again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    when {
                        lostNames.isNotEmpty() ->
                            "Access was lost or a folder no longer exists: ${lostNames.joinToString(", ")}. Remove it and add it again."
                        duplicateNames.isNotEmpty() ->
                            "Two folders have the same name: ${duplicateNames.joinToString(", ") { "./$it" }}. Only the first one is used — remove the other one and add it again."
                        else -> "Active. The AI can only see and change things inside these folders."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = pickPlayground) { Text("Add folder") }
            }
            if (playgroundUris.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val lines = accessDetails.map { entry ->
                                "./${entry.name}: ${container.fs.selfTestFolder(entry.uri.toString())}"
                            }
                            withContext(Dispatchers.Main) { selfTestResult = lines.joinToString("\n") }
                        }
                    },
                    modifier = Modifier.padding(vertical = 4.dp),
                ) { Text("Check read/write access") }
            }

            // ---------- Session storage ----------
            StorageSectionTitle("Session storage")
            ListItem(
                headlineContent = { Text(sessionPath ?: "Not chosen") },
                supportingContent = {
                    Text("Where the app saves your data: chats in sessions/, plus notes/, to-dos/ and template/. Separate from the playground where the AI works.")
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
                supportingText = { Text("Empty means the AI can write anywhere inside the playground folders. Example: notes — then every AI write must stay under notes/ inside each folder.") },
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

    confirmRemove?.let { entry ->
        ConfirmDialog(
            title = "Remove ./${entry.name} from the AI playground?",
            message = "The app will forget its access to this folder. The files in the folder are not affected.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmRemove = null
                scope.launch(Dispatchers.IO) { container.fs.removeFolder(entry.uri.toString()) }
            },
            onDismiss = { confirmRemove = null },
        )
    }
    if (confirmClearSession) {
        ConfirmDialog(
            title = "Remove the session storage folder?",
            message = "The app will stop saving chats, notes, to-dos and templates as files. The files in the folder are not affected.",
            confirmLabel = "Remove",
            onConfirm = { confirmClearSession = false; scope.launch(Dispatchers.IO) { container.fs.clearSessionStorage() } },
            onDismiss = { confirmClearSession = false },
        )
    }
    conflictName?.let { name ->
        InfoDialog(
            title = "Folder name conflict",
            message = "A folder named $name already exists in your AI playground. Please choose a folder with a different name.",
            onDismiss = { conflictName = null },
        )
    }
    pickError?.let { message ->
        InfoDialog(
            title = "Could not add folder",
            message = message,
            onDismiss = { pickError = null },
        )
    }
    selfTestResult?.let { result ->
        InfoDialog(
            title = "Read/write access",
            message = result,
            onDismiss = { selfTestResult = null },
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
