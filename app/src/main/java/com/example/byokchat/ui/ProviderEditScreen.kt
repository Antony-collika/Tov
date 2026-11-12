package com.example.byokchat.ui

import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
    val existing = remember { providerId?.let { settings.provider(it) } }
    val type = existing?.type ?: runCatching { ProviderType.valueOf(newType ?: "") }.getOrDefault(ProviderType.OPENAI)
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name ?: type.label.substringBefore(" (")) }
    var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    val models = remember { mutableStateListOf<String>().apply { addAll(existing?.models ?: emptyList()) } }
    var contextText by remember { mutableStateOf(existing?.contextTokens?.takeIf { it > 0 }?.toString() ?: "") }
    var maxOutText by remember { mutableStateOf((existing?.maxOutputTokens ?: 8192).toString()) }
    // Đợt 7: include_usage theo từng provider — chỉ có ý nghĩa với loại
    // Custom/DeepSeek (OpenAI Responses và Gemini tự gửi usage).
    // Provider mới mặc định bật.
    var includeUsage by remember { mutableStateOf(existing?.includeUsage ?: true) }
    var makeDefault by remember { mutableStateOf(existing == null || existing.id == activeId) }
    var fetching by remember { mutableStateOf(false) }
    /** Kết quả lần fetch gần nhất: null = chưa fetch. */
    var fetchMessage by remember { mutableStateOf<String?>(null) }
    var fetchFailed by remember { mutableStateOf(false) }
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
            contextTokens = contextText.trim().toIntOrNull() ?: 0,
            maxOutputTokens = maxOutText.trim().toIntOrNull()?.coerceAtLeast(256) ?: 8192,
            includeUsage = includeUsage,
        )
    }

    // Cảnh báo (không chặn lưu): API key sắp được gửi qua địa chỉ http không mã hóa.
    val insecureHttp = baseUrl.trim().startsWith("http://", ignoreCase = true) && apiKey.isNotBlank()

    fun save() {
        val cfg = buildConfig()
        when {
            type == ProviderType.CUSTOM && !cfg.baseUrl.startsWith("http") ->
                toast("The Custom type needs a server address starting with http:// or https://")
            type != ProviderType.CUSTOM && cfg.apiKey.isBlank() ->
                toast("Please enter an API key.")
            else -> {
                settings.saveProvider(cfg, makeDefault)
                onDone()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add provider" else "Edit provider") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { save() }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(type.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(if (type == ProviderType.CUSTOM) "API key (leave empty if the server does not need one)" else "API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                supportingText = { Text("Encrypted and stored only on this device.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(if (type == ProviderType.CUSTOM) "Server address (required)" else "Server address (optional)") },
                placeholder = { Text(if (type == ProviderType.CUSTOM) "https://ten-may-chu/v1" else existing?.effectiveBaseUrl() ?: ProviderConfig("", type, "").effectiveBaseUrl()) },
                singleLine = true,
                supportingText = if (insecureHttp) {
                    {
                        Text(
                            "This address uses http (not encrypted). The API key will be sent in plain text and others on the same network could read it. Only use this for a server on your own home network.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                enabled = !fetching,
                onClick = {
                    val cfg = buildConfig()
                    if (cfg.effectiveBaseUrl().isBlank()) {
                        toast("Enter the server address first.")
                        return@Button
                    }
                    scope.launch {
                        fetching = true
                        try {
                            val list = ProviderFactory.create(cfg).listModels().distinct()
                            models.clear()
                            models.addAll(list)
                            fetchFailed = false
                            fetchMessage = "Fetched ${list.size} models. Pick a model in the chat input bar."
                        } catch (e: Exception) {
                            fetchFailed = true
                            fetchMessage = "Failed to fetch models" + (e.message?.let { ": $it" } ?: "")
                        } finally {
                            fetching = false
                        }
                    }
                },
            ) {
                if (fetching) CircularProgressIndicator(Modifier.padding(end = 8.dp).heightIn(max = 18.dp), strokeWidth = 2.dp)
                Text("Fetch the model list from the server")
            }
            fetchMessage?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fetchFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }

            OutlinedTextField(
                value = contextText,
                onValueChange = { contextText = it.filter(Char::isDigit) },
                label = { Text("Context limit (tokens) – leave empty to auto-detect") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = maxOutText,
                onValueChange = { maxOutText = it.filter(Char::isDigit) },
                label = { Text("Maximum reply length (tokens)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            // Chỉ loại Custom/DeepSeek mới có trường này: OpenAI Responses tự
            // gửi usage trong event response.completed, Gemini tự gửi
            // usage nên không cần xin thêm, ẩn hẳn để user không nhầm.
            if (type == ProviderType.CUSTOM || type == ProviderType.DEEPSEEK) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ask for real token counts")
                        Text(
                            "Sends stream_options.include_usage so the log shows real counts. Some self-hosted servers reject this — turn it off if requests fail.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = includeUsage, onCheckedChange = { includeUsage = it })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use as the default for new conversations", modifier = Modifier.weight(1f))
                Switch(checked = makeDefault, onCheckedChange = { makeDefault = it })
            }
            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.padding(bottom = 24.dp),
                ) { Text("Delete this provider", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "Delete provider?",
            message = "\"${existing.name}\" and its saved API key will be removed from this device. Existing conversations are kept.",
            confirmLabel = "Delete",
            onConfirm = { settings.deleteProvider(existing.id); confirmDelete = false; onDone() },
            onDismiss = { confirmDelete = false },
        )
    }
}
