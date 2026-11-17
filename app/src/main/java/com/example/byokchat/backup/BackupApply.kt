package com.example.byokchat.backup

import com.example.byokchat.ai.MetadataParams
import com.example.byokchat.chat.SubAgentStore
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.SettingsRepository
import com.example.byokchat.tools.ToolMode
import com.example.byokchat.tools.ToolPref
import com.example.byokchat.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** What one restore did, for the result message. */
data class ApplyResult(
    val newChats: Int,
    val newProviders: Int,
    val newAgents: Int,
    /** Agents/providers whose save failed (e.g. an invalid config in the backup). */
    val skipped: List<String>,
)

/**
 * Apply a [RestorePlan] with the user's [decisions] (one per conflict id;
 * missing = KEEP_CURRENT). Never replaces: new rows are appended, rows
 * whose id already exists are skipped, single values are taken only when
 * the plan has no conflict for them or the user chose USE_BACKUP.
 *
 * Order matters: images are written first (their new paths go into the
 * message rows), then conversations, then the rows that reference them,
 * then providers/agents/tool edits, then the single values (metadata
 * before the active model, because choosing a model can reset an
 * unsaved metadata default — see SettingsRepository.setCurrentModel).
 * Restoring is re-runnable: a second run of the same backup skips
 * everything it already imported.
 */
suspend fun applyRestorePlan(
    db: AppDatabase,
    settings: SettingsRepository,
    registry: ToolRegistry,
    agentStore: SubAgentStore,
    imagesDir: File,
    plan: RestorePlan,
    decisions: Map<String, ConflictChoice>,
    images: Map<String, ByteArray>,
): ApplyResult = withContext(Dispatchers.IO) {
    val backup = plan.backup
    val skipped = mutableListOf<String>()

    fun useBackup(id: String): Boolean =
        plan.conflicts.none { it.id == id } || decisions[id] == ConflictChoice.USE_BACKUP

    // --- Images first: write the bytes, remember old path -> new path. ---
    val imagePathMap = mutableMapOf<String, String>()
    for (entryName in plan.newImageEntries) {
        val bytes = images[entryName] ?: continue
        val oldPath = backup.images.entries.firstOrNull { it.value == entryName }?.key ?: continue
        imagesDir.mkdirs()
        var target = File(imagesDir, File(entryName).name)
        if (target.exists()) {
            // Same name already here (e.g. restoring twice): keep both.
            target = File(imagesDir, "${UUID.randomUUID()}.jpg")
        }
        runCatching { target.writeBytes(bytes) }.onSuccess {
            imagePathMap[oldPath] = target.absolutePath
        }
    }
    // Rewrite the imported messages' image paths to the new files.
    val json = Json { ignoreUnknownKeys = true }
    val messages = plan.newMessages.map { msg ->
        if (imagePathMap.isEmpty()) return@map msg
        val paths = decodeImagePaths(msg.imagePaths)
        if (paths.none { it in imagePathMap }) return@map msg
        msg.copy(
            imagePaths = json.encodeToString(
                ListSerializer(String.serializer()),
                paths.map { imagePathMap[it] ?: it },
            ),
        )
    }

    // --- Room rows: conversations first (messages/sub-runs/recently-played
    // reference them), all filtered by the plan so nothing is overwritten. ---
    for (conv in plan.newConversations) {
        runCatching { db.conversations().insert(conv) }
    }
    if (messages.isNotEmpty()) {
        runCatching { db.messages().upsertAll(messages) }
    }
    if (plan.newSubRuns.isNotEmpty()) {
        runCatching { db.subRuns().insertAllIgnore(plan.newSubRuns) }
    }
    if (plan.newRecentlyPlayed.isNotEmpty()) {
        runCatching { db.recentlyPlayed().insertAllIgnore(plan.newRecentlyPlayed) }
    }
    for (note in plan.newNotes) {
        runCatching { db.notes().upsert(note) }
    }
    for (todo in plan.newTodos) {
        runCatching { db.todos().upsert(todo) }
    }

    // --- New providers and agents (appended). ---
    for (p in plan.newProviders) {
        runCatching { settings.saveProvider(p) }
            .onFailure { skipped += "Provider ${p.name}" }
    }
    for (a in plan.newAgents) {
        agentStore.addAgent(a).onFailure { skipped += "Sub-agent ${a.name}" }
    }

    // --- Tool edits the device has none of: take them silently, but only
    // the parts the backup actually edited (a mode-only edit must not
    // touch the description, and vice versa). ---
    for ((uid, pref) in plan.newToolPrefs) {
        applyToolPrefSilent(registry, uid, pref)
    }

    // --- Conflicts the user resolved with USE_BACKUP. ---
    for ((id, choice) in decisions) {
        if (choice != ConflictChoice.USE_BACKUP) continue
        when {
            id.startsWith("provider:") -> {
                backup.providers.firstOrNull { it.id == id.removePrefix("provider:") }
                    ?.let { settings.saveProvider(it) }
            }
            id.startsWith("agent:") -> {
                backup.agents.firstOrNull { it.id == id.removePrefix("agent:") }
                    ?.let { agentStore.updateAgent(it).onFailure { _ -> skipped += "Sub-agent ${it.name}" } }
            }
            id.startsWith("toolPref:") -> {
                val uid = id.removePrefix("toolPref:")
                val pref = backup.toolPrefs[uid] ?: ToolPref()
                applyToolPrefConflict(registry, uid, pref, backup.toolDescriptions[uid])
            }
        }
    }

    // --- Single values: take the backup's when there is no conflict for
    // them, or when the user chose USE_BACKUP. Blank backup values never
    // clear what the device already has. ---
    if (useBackup("defaultPrompt") && backup.defaultPrompt.isNotBlank()) {
        settings.setDefaultSystemPrompt(backup.defaultPrompt)
    }
    if (useBackup("metadata") && backup.metadata != MetadataParams()) {
        settings.setMetadata(backup.metadata)
    }
    if (useBackup("activeModel")) {
        val activeId = backup.activeProviderId
        val modelProviderId = backup.currentModelProviderId
        if (activeId != null || modelProviderId != null || backup.currentModel != null) {
            // Only point at a provider that is actually on this device
            // after the import above.
            val providerExists = activeId != null && settings.provider(activeId) != null
            if (activeId == null || providerExists) {
                settings.setActiveProvider(activeId)
                settings.setCurrentModel(modelProviderId, backup.currentModel)
            }
        }
    }
    if (useBackup("searchProvider") && backup.searchProvider != settings.searchProvider.value) {
        settings.setSearchProvider(backup.searchProvider)
    }
    if (useBackup("youtubeKey") && backup.youtubeKey.isNotBlank()) {
        settings.setYoutubeApiKey(backup.youtubeKey)
    }
    for ((engine, key) in backup.searchKeys) {
        if (key.isNotBlank() && useBackup("searchKey:$engine")) {
            settings.setSearchKeyForEngine(engine, key)
        }
    }

    ApplyResult(
        newChats = plan.newConversations.size,
        newProviders = plan.newProviders.size,
        newAgents = plan.newAgents.size,
        skipped = skipped,
    )
}

/**
 * Silent take: the device has no edit for this tool, so apply only the
 * parts the backup actually edited. A tool that no longer exists on this
 * device is skipped.
 */
private fun applyToolPrefSilent(registry: ToolRegistry, uid: String, pref: ToolPref) {
    val tool = registry.tool(uid) ?: return
    pref.mode?.let { runCatching { ToolMode.valueOf(it) }.getOrNull() }
        ?.let { registry.setMode(tool, it) }
    pref.description?.let { registry.setDescription(tool, it) }
}

/**
 * Conflict resolved with USE_BACKUP: make the tool look like the backup
 * shows it. Mode from the backup's edit (cleared when the backup has
 * none), description = the backup's effective description — cleared
 * when it equals this device's default, so a code-version default
 * change does not freeze an old text in place as an override. A tool
 * that no longer exists on this device is skipped.
 */
private fun applyToolPrefConflict(
    registry: ToolRegistry,
    uid: String,
    pref: ToolPref,
    backupEffectiveDescription: String?,
) {
    val tool = registry.tool(uid) ?: return
    val mode = pref.mode?.let { runCatching { ToolMode.valueOf(it) }.getOrNull() }
    registry.setMode(tool, mode ?: tool.defaultMode)
    val description = pref.description ?: backupEffectiveDescription ?: tool.defaultDescription
    registry.setDescription(tool, description)
}
