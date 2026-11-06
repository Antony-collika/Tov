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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    val workspaceUri by settings.workspaceUri.collectAsState()
    val accessible by container.fs.accessible.collectAsState()
    val searchProvider by settings.searchProvider.collectAsState()
    var prompt by remember { mutableStateOf(settings.defaultSystemPrompt.value) }
    var searchKey by remember { mutableStateOf(settings.searchApiKey.value) }
    var showKey by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val pickFolder = rememberFolderPicker()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle("AI")
            ListItem(
                modifier = Modifier.clickable(onClick = onProviders),
                headlineContent = { Text("Nhà cung cấp AI") },
                supportingContent = { Text(if (providers.isEmpty()) "Chưa có nhà cung cấp nào" else providers.joinToString(", ") { it.name }) },
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it; settings.setDefaultSystemPrompt(it) },
                    label = { Text("Prompt hệ thống mặc định") },
                    supportingText = { Text("Áp dụng cho các cuộc trò chuyện mới. Từng cuộc vẫn sửa riêng được.") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Công cụ cho AI")
            ListItem(
                modifier = Modifier.clickable(onClick = onTools),
                headlineContent = { Text("Quản lý công cụ") },
                supportingContent = { Text("Bật/tắt, mức kiểm soát, đổi tên và mô tả từng công cụ") },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onNotes),
                headlineContent = { Text("Ghi chú & việc cần làm") },
                supportingContent = { Text("Xem những gì AI đã lưu giúp bạn") },
            )

            SectionTitle("Thư mục làm việc (workspace)")
            ListItem(
                headlineContent = { Text(if (workspaceUri == null) "Chưa chọn thư mục" else container.fs.rootName() ?: "Đã chọn") },
                supportingContent = {
                    Text(
                        when {
                            workspaceUri == null -> "Các công cụ tệp sẽ bị chặn cho đến khi bạn chọn một thư mục."
                            accessible -> "Đang hoạt động. AI chỉ thấy và sửa được bên trong thư mục này."
                            else -> "Mất quyền truy cập hoặc thư mục không còn tồn tại. Hãy chọn lại."
                        }
                    )
                },
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = pickFolder) { Text(if (workspaceUri == null) "Chọn thư mục" else "Đổi thư mục") }
                if (workspaceUri != null) {
                    OutlinedButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val msg = container.fs.selfTest()
                            launch(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("Kiểm tra") }
                    TextButton(onClick = { confirmClear = true }) { Text("Bỏ chọn") }
                }
            }

            SectionTitle("Tìm kiếm web (cho công cụ web_search)")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("BRAVE" to "Brave Search", "TAVILY" to "Tavily").forEach { (id, label) ->
                    Row(
                        Modifier.clickable { settings.setSearchProvider(id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = searchProvider == id, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = searchKey,
                    onValueChange = { searchKey = it; settings.setSearchApiKey(it.trim()) },
                    label = { Text("API key dịch vụ tìm kiếm của bạn") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Ẩn" else "Hiện") } },
                    supportingText = { Text("Để trống thì công cụ tìm kiếm web không được gửi cho AI. Key được mã hóa trên máy.") },
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Giao diện")
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().clickable { settings.setThemeMode(mode) }.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = theme == mode, onClick = null)
                    Text(mode.label, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Bỏ chọn thư mục làm việc?",
            message = "App sẽ quên quyền truy cập thư mục này. File trong thư mục không bị ảnh hưởng.",
            confirmLabel = "Bỏ chọn",
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
