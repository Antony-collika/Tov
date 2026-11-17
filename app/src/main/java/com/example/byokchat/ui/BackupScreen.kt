package com.example.byokchat.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.byokchat.backup.ApplyResult
import com.example.byokchat.backup.BackupOpenError
import com.example.byokchat.backup.ConflictChoice
import com.example.byokchat.backup.CurrentBackupState
import com.example.byokchat.backup.RestorePlan
import com.example.byokchat.backup.applyRestorePlan
import com.example.byokchat.backup.buildBackupZip
import com.example.byokchat.backup.collectBackupData
import com.example.byokchat.backup.computeRestorePlan
import com.example.byokchat.backup.encodeBackupData
import com.example.byokchat.backup.encryptBackupFile
import com.example.byokchat.backup.isBackupFile
import com.example.byokchat.backup.needsReview
import com.example.byokchat.backup.openBackupFile
import com.example.byokchat.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore (17/11/2026): one encrypted file, no online sync.
 * The file follows Duy's ClipNest backup (a JSON envelope a text editor
 * can read the structure of — format, version, cipher, kdf, iterations,
 * salt, nonce, ciphertext). The user presses the button, sets any
 * password she likes (no rules — she decides), and enters the password
 * again to open a backup file. Restoring never replaces: new chats,
 * providers and agents are appended, and where the backup differs from
 * this device she chooses keep current or use backup, once, on one
 * screen after decrypting — and only when there is anything to decide:
 * with no conflicts the restore applies straight away, with no screen
 * at all. The review screen also has keep-all / use-all buttons, so she
 * never has to tap every conflict one by one. DB only: files in the
 * playground, the Request panel, theme, max tool rounds and the request
 * log are not backed up at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val playgroundUris by container.settings.playgroundUris.collectAsState()
    val sessionStorageUri by container.settings.sessionStorageUri.collectAsState()
    val playgroundPaths by produceState<List<String>>(emptyList(), playgroundUris) {
        value = withContext(Dispatchers.IO) { container.fs.playgroundDisplayPaths() }
    }
    val sessionPath by produceState<String?>(null, sessionStorageUri) {
        value = withContext(Dispatchers.IO) { container.fs.displaySessionPath() }
    }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showExportPassword by remember { mutableStateOf(false) }
    // The envelope text waiting for its password (import), or the
    // password waiting for the save picker (export).
    var pendingEnvelope by remember { mutableStateOf<String?>(null) }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    var plan by remember { mutableStateOf<RestorePlan?>(null) }
    val decisions = remember { mutableStateMapOf<String, ConflictChoice>() }
    var done by remember { mutableStateOf<ApplyResult?>(null) }
    // Image bytes of the opened backup, kept until the restore is applied.
    var pendingImages by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun backupFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return "tov-backup-$date.tovbackup"
    }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val password = pendingPassword
        pendingPassword = null
        if (uri == null || password == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val (data, imageBytes) = collectBackupData(
                    db = container.db,
                    settings = container.settings,
                    registry = container.registry,
                    agentStore = container.agentStore,
                    playgroundFolderNames = playgroundPaths,
                    sessionFolderName = sessionPath,
                )
                val zip = buildBackupZip(encodeBackupData(data), imageBytes)
                val envelope = encryptBackupFile(zip, password)
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(envelope.toByteArray(Charsets.UTF_8))
                } ?: error("Could not open the file")
            }.isSuccess
            withContext(Dispatchers.Main) {
                busy = false
                if (ok) toast("Backup saved") else error = "Backup failed. Try again."
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            }.getOrNull()
            if (text.isNullOrBlank() || !isBackupFile(text)) {
                withContext(Dispatchers.Main) {
                    busy = false
                    error = "This is not a Tov backup file."
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                busy = false
                pendingEnvelope = text
            }
        }
    }

    fun applyRestore(toApply: RestorePlan) {
        busy = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                applyRestorePlan(
                    db = container.db,
                    settings = container.settings,
                    registry = container.registry,
                    agentStore = container.agentStore,
                    imagesDir = File(context.filesDir, "images"),
                    plan = toApply,
                    decisions = decisions.toMap(),
                    images = pendingImages,
                )
            }
            withContext(Dispatchers.Main) {
                busy = false
                result.onSuccess {
                    pendingImages = emptyMap()
                    plan = null
                    done = it
                }.onFailure {
                    error = "Restore failed: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        }
    }

    fun startRestore(password: CharArray) {
        val envelope = pendingEnvelope ?: return
        pendingEnvelope = null
        busy = true
        scope.launch(Dispatchers.IO) {
            val opened = runCatching { openBackupFile(envelope, password) }
            val planned: Pair<RestorePlan, Map<String, ByteArray>>? = opened.getOrNull()?.let { (data, images) ->
                runCatching {
                    val current = CurrentBackupState(
                        providers = container.settings.providers.value,
                        activeProviderId = container.settings.activeProviderId.value,
                        currentModelProviderId = container.settings.currentModelProviderId.value,
                        currentModel = container.settings.currentModel.value,
                        metadata = container.settings.metadata.value,
                        searchKeys = container.settings.searchKeysSnapshot(),
                        searchProvider = container.settings.searchProvider.value,
                        youtubeKey = container.settings.youtubeApiKey.value,
                        toolPrefs = container.registry.toolPrefsSnapshot(),
                        toolNames = container.registry.all.associate {
                            it.uid to container.registry.effectiveName(it)
                        },
                        toolDescriptions = container.registry.all.associate {
                            it.uid to container.registry.effectiveDescription(it)
                        },
                        agents = container.agentStore.team.value.agents,
                        defaultPrompt = container.settings.defaultSystemPrompt.value,
                        conversationIds = container.db.conversations().getAllIds().toSet(),
                        noteIds = container.db.notes().all().map { it.id }.toSet(),
                        todoIds = container.db.todos().all().map { it.id }.toSet(),
                    )
                    val computed = computeRestorePlan(current, data)
                    // Only the image bytes the plan actually imports; a
                    // missing entry is skipped at apply time.
                    val imageMap = computed.newImageEntries.mapNotNull { entry ->
                        images[entry]?.let { entry to it }
                    }.toMap()
                    computed to imageMap
                }.getOrNull()
            }
            val failure = opened.exceptionOrNull()
                ?.let { (it as? BackupOpenError)?.message ?: "Could not open the backup file." }
            withContext(Dispatchers.Main) {
                busy = false
                if (planned != null) {
                    val (computed, imageMap) = planned
                    pendingImages = imageMap
                    if (!computed.needsReview()) {
                        // No conflicts: apply straight away, no review
                        // screen — the user is never asked a question that
                        // has no decision in it.
                        applyRestore(computed)
                    } else {
                        decisions.clear()
                        computed.conflicts.forEach { decisions[it.id] = ConflictChoice.KEEP_CURRENT }
                        plan = computed
                    }
                } else {
                    error = failure ?: "Could not read the backup contents."
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val currentPlan = plan
            val result = done
            when {
                result != null -> {
                    Text("Restore done", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Added ${result.newChats} chat(s), ${result.newProviders} provider(s), " +
                            "${result.newAgents} sub-agent(s).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (result.skipped.isNotEmpty()) {
                        Text(
                            "Could not restore: ${result.skipped.joinToString(", ")}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "If the backup mentioned folders to pick again, choose them in " +
                            "Settings → Select Storage and Playground folder — the backup cannot " +
                            "restore folder permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                currentPlan != null -> {
                    RestoreReview(
                        plan = currentPlan,
                        decisions = decisions,
                        onDecision = { id, choice -> decisions[id] = choice },
                        onApply = { applyRestore(currentPlan) },
                        onCancel = { plan = null; pendingImages = emptyMap() },
                        busy = busy,
                    )
                }
                else -> {
                    Text(
                        "One encrypted file holds everything in the database: providers and API keys, " +
                            "sub-agents, chats, notes, tool edits and the default system prompt. " +
                            "The Request panel, theme, max tool rounds and the request log are not " +
                            "backed up. Files in the playground are not included either — copy them yourself.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Card(onClick = { showExportPassword = true }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Backup data", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Save an encrypted backup file. You set any password you like — " +
                                    "there are no rules; you decide.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Card(onClick = { openLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Restore backup", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Open a backup file with its password. Nothing is replaced: new chats, " +
                                    "providers and agents are added, and where the backup differs from " +
                                    "this device you choose keep current or use backup.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showExportPassword) {
        BackupPasswordDialog(
            title = "Set the backup password",
            confirmLabel = "Backup",
            needConfirm = true,
            onDismiss = { showExportPassword = false },
            onConfirm = { password ->
                showExportPassword = false
                pendingPassword = password
                busy = true
                createLauncher.launch(backupFileName())
            },
        )
    }
    val envelope = pendingEnvelope
    if (envelope != null) {
        BackupPasswordDialog(
            title = "Enter the backup password",
            confirmLabel = "Open backup",
            needConfirm = false,
            onDismiss = { pendingEnvelope = null },
            onConfirm = { password -> startRestore(password) },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Backup") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}

/** The review screen shown after a backup file is decrypted. */
@Composable
private fun RestoreReview(
    plan: RestorePlan,
    decisions: Map<String, ConflictChoice>,
    onDecision: (String, ConflictChoice) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    busy: Boolean,
) {
    val date = if (plan.backupCreatedAt > 0) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(plan.backupCreatedAt))
    } else {
        "Unknown date"
    }
    Text("Restore backup from $date", style = MaterialTheme.typography.titleMedium)
    if (plan.appendLines.isNotEmpty() || plan.silentLines.isNotEmpty()) {
        Text("Will be added", style = MaterialTheme.typography.titleSmall)
        (plan.appendLines + plan.silentLines).forEach { line ->
            Text("• $line", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (plan.skippedLines.isNotEmpty()) {
        Text("Skipped", style = MaterialTheme.typography.titleSmall)
        plan.skippedLines.forEach { line ->
            Text(
                "• $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (plan.conflicts.isNotEmpty()) {
        Text(
            "Decide — keep what is on this device, or use the backup's:",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { plan.conflicts.forEach { onDecision(it.id, ConflictChoice.KEEP_CURRENT) } },
                modifier = Modifier.weight(1f),
            ) { Text("Keep all current") }
            OutlinedButton(
                onClick = { plan.conflicts.forEach { onDecision(it.id, ConflictChoice.USE_BACKUP) } },
                modifier = Modifier.weight(1f),
            ) { Text("Use all backup") }
        }
        plan.conflicts.forEach { conflict ->
            val choice = decisions[conflict.id] ?: ConflictChoice.KEEP_CURRENT
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(conflict.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Current: ${conflict.currentSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Backup: ${conflict.backupSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = choice == ConflictChoice.KEEP_CURRENT,
                        onClick = { onDecision(conflict.id, ConflictChoice.KEEP_CURRENT) },
                        role = Role.RadioButton,
                    ).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = choice == ConflictChoice.KEEP_CURRENT,
                        onClick = { onDecision(conflict.id, ConflictChoice.KEEP_CURRENT) },
                    )
                    Text("Keep current", modifier = Modifier.clickable { onDecision(conflict.id, ConflictChoice.KEEP_CURRENT) })
                }
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = choice == ConflictChoice.USE_BACKUP,
                        onClick = { onDecision(conflict.id, ConflictChoice.USE_BACKUP) },
                        role = Role.RadioButton,
                    ).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = choice == ConflictChoice.USE_BACKUP,
                        onClick = { onDecision(conflict.id, ConflictChoice.USE_BACKUP) },
                    )
                    Text("Use backup", modifier = Modifier.clickable { onDecision(conflict.id, ConflictChoice.USE_BACKUP) })
                }
            }
            HorizontalDivider()
        }
    }
    if (plan.folderReminder.isNotEmpty()) {
        Text(
            "After restoring, pick these folders again in Settings → Select Storage and " +
                "Playground folder — the backup cannot restore folder permissions: " +
                plan.folderReminder.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onApply, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Restore with these choices")
    }
    OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel")
    }
}

/**
 * Password dialog for backup export (asks twice) and import (asks once).
 * Any password works — no length or character rules; the user decides.
 * The only rule is that it must not be empty, because the file is
 * always password-encrypted.
 */
@Composable
private fun BackupPasswordDialog(
    title: String,
    confirmLabel: String,
    needConfirm: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Any password works — short or long, any characters. There are no rules; you decide.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "If you forget the password, the backup cannot be opened — there is no way to recover it. " +
                        "A weak password can be guessed by trying many passwords offline. " +
                        "The backup file contains your API keys — keep it private, don't send it to anyone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; mismatch = false },
                    label = { Text("Password") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (needConfirm) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it; mismatch = false },
                        label = { Text("Repeat password") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (mismatch) {
                    Text(
                        "The two passwords do not match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && (!needConfirm || confirm.isNotEmpty()),
                onClick = {
                    if (needConfirm && password != confirm) {
                        mismatch = true
                        return@TextButton
                    }
                    onConfirm(password.toCharArray())
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
