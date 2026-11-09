package com.example.byokchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.byokchat.AppContainer
import com.example.byokchat.ai.ProviderFactory
import com.example.byokchat.container
import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Kết quả của nút "Check" ở bước 2. */
sealed interface CheckState {
    data object Idle : CheckState
    data object Checking : CheckState
    data object Ok : CheckState
    data class Failed(val message: String) : CheckState
}

/** Giữ toàn bộ trạng thái màn chào để không mất khi xoay màn hình. API key chỉ nằm ở đây, không lưu qua rememberSaveable. */
class OnboardingViewModel(private val app: AppContainer) : ViewModel() {
    var step by mutableIntStateOf(1)
        private set
    var type by mutableStateOf<ProviderType?>(null)
        private set
    var apiKey by mutableStateOf("")
        private set
    var baseUrl by mutableStateOf("")
        private set
    var showKey by mutableStateOf(false)
    var check by mutableStateOf<CheckState>(CheckState.Idle)
        private set
    var models by mutableStateOf<List<String>>(emptyList())
        private set
    val picked = mutableStateListOf<String>()
    var filter by mutableStateOf("")
    var finishing by mutableStateOf(false)
        private set

    val canCheck: Boolean
        get() {
            val t = type ?: return false
            if (check is CheckState.Checking) return false
            return if (t == ProviderType.CUSTOM) baseUrl.isNotBlank() else apiKey.isNotBlank()
        }

    fun chooseType(t: ProviderType) {
        if (type != t) {
            type = t
            apiKey = ""
            baseUrl = ""
            check = CheckState.Idle
            models = emptyList()
            picked.clear()
            filter = ""
        }
        step = 2
    }

    fun onApiKeyChange(v: String) {
        apiKey = v
        check = CheckState.Idle
    }

    fun onBaseUrlChange(v: String) {
        baseUrl = v
        check = CheckState.Idle
    }

    fun back() {
        if (step > 1) step -= 1
    }

    fun toggle(model: String) {
        if (model in picked) picked.remove(model) else picked.add(model)
    }

    /** Key hợp lệ khi và chỉ khi lấy được danh sách mô hình từ máy chủ. */
    fun runCheck() {
        val t = type ?: return
        if (check is CheckState.Checking) return
        val url = baseUrl.trim()
        if (t == ProviderType.CUSTOM && !(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) {
            check = CheckState.Failed("The server address must start with http:// or https://")
            return
        }
        val cfg = ProviderConfig(
            id = UUID.randomUUID().toString(),
            type = t,
            name = t.label.substringBefore(" ("),
            apiKey = apiKey.trim(),
            baseUrl = if (t == ProviderType.CUSTOM) url else "",
        )
        check = CheckState.Checking
        viewModelScope.launch {
            // supervisorScope + async: lỗi của lần gọi mạng không làm sập phạm vi cha, và hết 20 giây là báo ngay.
            supervisorScope {
                val job = async { ProviderFactory.create(cfg).listModels() }
                try {
                    val list = withTimeout(20_000) { job.await() }.distinct()
                    if (list.isEmpty()) {
                        check = CheckState.Failed(
                            "Connected, but the server has no models to choose from. " +
                                "You can tap \"Skip\" and connect an AI later from the chat screen."
                        )
                    } else {
                        models = list
                        picked.clear()
                        filter = ""
                        check = CheckState.Ok
                        step = 3
                    }
                } catch (e: TimeoutCancellationException) {
                    job.cancel()
                    check = CheckState.Failed("The server took too long to respond. Please try again.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    check = CheckState.Failed(e.message ?: "Couldn't check the connection. Please try again.")
                }
            }
        }
    }

    /** Lưu nhà cung cấp, đánh dấu đã xong màn chào, tạo cuộc trò chuyện trống. Trả về id cuộc trò chuyện mới. */
    suspend fun finish(): String? {
        val t = type ?: return null
        if (picked.isEmpty() || finishing) return null
        finishing = true
        try {
            val list = picked.toList()
            val cfg = ProviderConfig(
                id = UUID.randomUUID().toString(),
                type = t,
                name = t.label.substringBefore(" ("),
                apiKey = apiKey.trim(),
                baseUrl = if (t == ProviderType.CUSTOM) baseUrl.trim() else "",
                models = list,
            )
            app.settings.saveProvider(cfg, makeDefault = true)
            // Chat đầu tiên sau onboarding đã có model: chọn model đầu tiên user đã tick.
            app.settings.setCurrentModel(cfg.id, list.first())
            app.settings.setOnboardingDone(true)
            return app.conversations.create()
        } finally {
            finishing = false
        }
    }

    fun skip() {
        app.settings.setOnboardingDone(true)
    }
}

/** onExit(null) = bỏ qua; onExit(id) = xong, mở cuộc trò chuyện id. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onExit: (String?) -> Unit) {
    val container = LocalContext.current.container
    val vm: OnboardingViewModel = viewModel(factory = viewModelFactory {
        initializer { OnboardingViewModel(container) }
    })
    val scope = rememberCoroutineScope()

    BackHandler(enabled = vm.step > 1) { vm.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Step ${vm.step}/3") },
                actions = {
                    TextButton(onClick = { vm.skip(); onExit(null) }) { Text("Skip") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (vm.step) {
                    1 -> StepChooseProvider(vm)
                    2 -> StepEnterKey(vm)
                    else -> StepPickModels(vm)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.step > 1) TextButton(onClick = { vm.back() }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                when (vm.step) {
                    2 -> Button(onClick = { vm.runCheck() }, enabled = vm.canCheck) { Text("Check") }
                    3 -> Button(
                        onClick = {
                            scope.launch {
                                val id = vm.finish()
                                if (id != null) onExit(id)
                            }
                        },
                        enabled = vm.picked.isNotEmpty() && !vm.finishing,
                    ) { Text("Xong") }
                }
            }
        }
    }
}

@Composable
private fun ProviderChoiceCard(type: ProviderType, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(type.label, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepChooseProvider(vm: OnboardingViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Whose AI do you want to use?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Pick one. You need an API key (like a password for using the AI) from your account there.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ProviderChoiceCard(ProviderType.CLAUDE, "Use a key from your Anthropic account page.") { vm.chooseType(ProviderType.CLAUDE) }
        ProviderChoiceCard(ProviderType.OPENAI, "Use a key from your OpenAI account page.") { vm.chooseType(ProviderType.OPENAI) }
        ProviderChoiceCard(ProviderType.GEMINI, "Use a key from your Google account.") { vm.chooseType(ProviderType.GEMINI) }
        ProviderChoiceCard(
            ProviderType.CUSTOM,
            "Use another AI server (your own or another service) that connects the same way as OpenAI.",
        ) { vm.chooseType(ProviderType.CUSTOM) }
    }
}

@Composable
private fun StepEnterKey(vm: OnboardingViewModel) {
    val t = vm.type ?: return
    val busy = vm.check is CheckState.Checking
    val isCustom = t == ProviderType.CUSTOM
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Enter details for ${t.label.substringBefore(" (")}",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (isCustom) {
            OutlinedTextField(
                value = vm.baseUrl,
                onValueChange = { vm.onBaseUrlChange(it) },
                label = { Text("Server address") },
                placeholder = { Text("https://ten-may-chu/v1") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = vm.apiKey,
            onValueChange = { vm.onApiKeyChange(it) },
            label = { Text(if (isCustom) "API key (optional)" else "API key") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = if (vm.showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { vm.showKey = !vm.showKey }) { Text(if (vm.showKey) "Hide" else "Show") } },
            supportingText = { Text("Encrypted and stored only on this device.") },
            modifier = Modifier.fillMaxWidth(),
        )
        when (val c = vm.check) {
            CheckState.Idle -> {}
            CheckState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Checking…", modifier = Modifier.padding(start = 12.dp))
            }
            CheckState.Ok -> Text("Connected successfully.", color = MaterialTheme.colorScheme.primary)
            is CheckState.Failed -> Text(c.message, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StepPickModels(vm: OnboardingViewModel) {
    val shown = remember(vm.models, vm.filter) { vm.models.filter { it.contains(vm.filter, ignoreCase = true) } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose a model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        Text("Tick at least one model.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = vm.filter,
            onValueChange = { vm.filter = it },
            label = { Text("Filter") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(shown, key = { it }) { m ->
                Row(Modifier.fillMaxWidth().clickable { vm.toggle(m) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = m in vm.picked, onCheckedChange = null)
                    Text(m, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Text(
            "You can add more models later in Settings → AI providers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
