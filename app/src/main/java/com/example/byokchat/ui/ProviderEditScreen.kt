package com.example.byokchat.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.byokchat.ai.ProviderFactory
import com.example.byokchat.container
import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(providerId: String?, newType: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = context.container.settings
    val activeId by settings.activeProviderId.collectAsState()
    val existing = remember { providerId?.let { settings.provider(it) } }
    val type = existing?.type ?: runCatching { ProviderType.valueOf(newType ?: "") }.getOrDefault(ProviderType.OPENAI)
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name ?: type.label.substringBefore(" (")) }
    var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    val models = remember { mutableStateListOf<String>().apply { addAll(existing?.models ?: emptyList()) } }
    var defaultModel by remember { mutableStateOf(existing?.defaultModel ?: "") }
    var newModel by remember { mutableStateOf("") }
    var contextText by remember { mutableStateOf(existing?.contextTokens?.takeIf { it > 0 }?.toString() ?: "") }
    var maxOutText by remember { mutableStateOf((existing?.maxOutputTokens ?: 8192).toString()) }
    var makeDefault by remember { mutableStateOf(existing == null || existing.id == activeId) }
    var fetching by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun buildConfig(): ProviderConfig {
        val list = models.toList()
        return ProviderConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            type = type,
            name = name.trim().ifBlank { type.label },
            apiKey = apiKey.trim(),
            baseUrl = baseUrl.trim(),
            models = list,
            defaultModel = defaultModel.takeIf { it in list } ?: list.firstOrNull() ?: "",
            contextTokens = contextText.trim().toIntOrNull() ?: 0,
            maxOutputTokens = maxOutText.trim().toIntOrNull()?.coerceAtLeast(256) ?: 8192,
        )
    }

    // Cảnh báo (không chặn lưu): API key sắp được gửi qua địa chỉ http không mã hóa.
    val insecureHttp = baseUrl.trim().startsWith("http://", ignoreCase = true) && apiKey.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Thêm nhà cung cấp" else "Sửa nhà cung cấp") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val cfg = buildConfig()
                        when {
                            type == ProviderType.CUSTOM && !cfg.baseUrl.startsWith("http") ->
                                toast("Loại Tùy chỉnh cần địa chỉ máy chủ bắt đầu bằng http:// hoặc https://")
                            type != ProviderType.CUSTOM && cfg.apiKey.isBlank() ->
                                toast("Hãy nhập API key.")
                            else -> {
                                settings.saveProvider(cfg, makeDefault)
                                onDone()
                            }
                        }
                    }) { Text("Lưu") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(type.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Tên hiển thị") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(if (type == ProviderType.CUSTOM) "API key (để trống nếu máy chủ không cần)" else "API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Ẩn" else "Hiện") } },
                supportingText = { Text("Được mã hóa và chỉ lưu trên máy này.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(if (type == ProviderType.CUSTOM) "Địa chỉ máy chủ (bắt buộc)" else "Địa chỉ máy chủ (không bắt buộc)") },
                placeholder = { Text(if (type == ProviderType.CUSTOM) "https://ten-may-chu/v1" else existing?.effectiveBaseUrl() ?: ProviderConfig("", type, "").effectiveBaseUrl()) },
                singleLine = true,
                supportingText = if (insecureHttp) {
                    {
                        Text(
                            "Địa chỉ này dùng http (không mã hóa). API key sẽ bị gửi đi dạng thô, người khác trong cùng mạng có thể đọc được. Chỉ dùng khi đây là máy chủ trong mạng nhà bạn.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Danh sách mô hình", style = MaterialTheme.typography.titleSmall)
            Text(
                "Chọn một mô hình làm mặc định cho cuộc trò chuyện mới.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            models.toList().forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { defaultModel = m }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = defaultModel == m, onClick = { defaultModel = m })
                    Text(m, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        models.remove(m)
                        if (defaultModel == m) defaultModel = models.firstOrNull() ?: ""
                    }) { Icon(Icons.Default.Close, contentDescription = "Xóa mô hình") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newModel,
                    onValueChange = { newModel = it },
                    label = { Text("Thêm tên mô hình") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        val m = newModel.trim()
                        if (m.isNotEmpty() && m !in models) {
                            models.add(m)
                            if (defaultModel.isBlank()) defaultModel = m
                        }
                        newModel = ""
                    },
                ) { Text("Thêm") }
            }
            Button(
                enabled = !fetching,
                onClick = {
                    val cfg = buildConfig()
                    if (cfg.effectiveBaseUrl().isBlank()) {
                        toast("Hãy nhập địa chỉ máy chủ trước.")
                        return@Button
                    }
                    scope.launch {
                        fetching = true
                        try {
                            fetched = ProviderFactory.create(cfg).listModels()
                        } catch (e: Exception) {
                            toast("Không lấy được danh sách: ${e.message}")
                        } finally {
                            fetching = false
                        }
                    }
                },
            ) {
                if (fetching) CircularProgressIndicator(Modifier.padding(end = 8.dp).heightIn(max = 18.dp), strokeWidth = 2.dp)
                Text("Lấy danh sách mô hình từ máy chủ")
            }

            OutlinedTextField(
                value = contextText,
                onValueChange = { contextText = it.filter(Char::isDigit) },
                label = { Text("Giới hạn ngữ cảnh (token) – để trống = tự đoán") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = maxOutText,
                onValueChange = { maxOutText = it.filter(Char::isDigit) },
                label = { Text("Độ dài trả lời tối đa (token)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dùng làm mặc định cho cuộc trò chuyện mới", modifier = Modifier.weight(1f))
                Switch(checked = makeDefault, onCheckedChange = { makeDefault = it })
            }
            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.padding(bottom = 24.dp),
                ) { Text("Xóa nhà cung cấp này", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    fetched?.let { all ->
        var query by remember { mutableStateOf("") }
        val picked = remember { mutableStateListOf<String>() }
        val shown = all.filter { it.contains(query, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { fetched = null },
            title = { Text("Chọn mô hình (${all.size})") },
            text = {
                Column {
                    OutlinedTextField(query, { query = it }, label = { Text("Lọc") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it }) { m ->
                            Row(
                                Modifier.fillMaxWidth().clickable { if (m in picked) picked.remove(m) else picked.add(m) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = m in picked || m in models, onCheckedChange = null, enabled = m !in models)
                                Text(m, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    picked.forEach { if (it !in models) models.add(it) }
                    if (defaultModel.isBlank()) defaultModel = models.firstOrNull() ?: ""
                    fetched = null
                }) { Text("Thêm đã chọn") }
            },
            dismissButton = { TextButton(onClick = { fetched = null }) { Text("Đóng") } },
        )
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "Xóa nhà cung cấp?",
            message = "\"${existing.name}\" và API key đã lưu sẽ bị xóa khỏi máy. Các cuộc trò chuyện cũ vẫn được giữ.",
            confirmLabel = "Xóa",
            onConfirm = { settings.deleteProvider(existing.id); confirmDelete = false; onDone() },
            onDismiss = { confirmDelete = false },
        )
    }
}
