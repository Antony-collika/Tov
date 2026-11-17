package com.example.byokchat.backup

import com.example.byokchat.chat.SubAgentStore
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.SettingsRepository
import com.example.byokchat.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Collect everything the backup holds (DB only — Duy, 2026-11-17): the
 * providers + keys, tool edits, the sub-agent team, the default system
 * prompt, metadata, and the Room tables, plus the attached images the
 * backed-up messages reference. No files from the playground, no session
 * files, no SAF permissions, no Request panel layout, no theme / max tool
 * rounds / request log (all excluded by Duy's decisions).
 *
 * Folder display names come from the UI (Settings already loads them off
 * the main thread): they only remind the user which folders to pick again
 * after a restore, because SAF permissions cannot be restored.
 */
suspend fun collectBackupData(
    db: AppDatabase,
    settings: SettingsRepository,
    registry: ToolRegistry,
    agentStore: SubAgentStore,
    playgroundFolderNames: List<String>,
    sessionFolderName: String?,
): CollectedBackup = withContext(Dispatchers.IO) {
    val messages = db.messages().all()
    // Attached images: old absolute path -> ZIP entry name. Two different
    // files with the same name get a numbered suffix so nothing collides.
    val usedEntryNames = mutableSetOf<String>()
    val imagePaths = messages.flatMap { decodeImagePaths(it.imagePaths) }.distinct()
    val images = mutableMapOf<String, String>()
    val imageBytes = mutableMapOf<String, ByteArray>()
    for (oldPath in imagePaths) {
        val file = File(oldPath)
        if (!file.isFile) continue
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
        var entryName = "images/${file.name}"
        var n = 2
        while (entryName in usedEntryNames) {
            entryName = "images/${file.nameWithoutExtension}-$n.${file.extension}"
            n++
        }
        usedEntryNames += entryName
        images[oldPath] = entryName
        imageBytes[entryName] = bytes
    }
    val data = BackupData(
        createdAt = System.currentTimeMillis(),
        providers = settings.providers.value,
        activeProviderId = settings.activeProviderId.value,
        currentModelProviderId = settings.currentModelProviderId.value,
        currentModel = settings.currentModel.value,
        metadata = settings.metadata.value,
        searchKeys = settings.searchKeysSnapshot(),
        searchProvider = settings.searchProvider.value,
        youtubeKey = settings.youtubeApiKey.value,
        toolPrefs = registry.toolPrefsSnapshot(),
        toolNames = registry.all.associate { it.uid to registry.effectiveName(it) },
        toolDescriptions = registry.all.associate { it.uid to registry.effectiveDescription(it) },
        agents = agentStore.team.value.agents,
        defaultPrompt = settings.defaultSystemPrompt.value,
        playgroundFolderNames = playgroundFolderNames,
        sessionFolderName = sessionFolderName,
        conversations = db.conversations().observeAllOnce(),
        messages = messages,
        subRuns = db.subRuns().all(),
        recentlyPlayed = db.recentlyPlayed().all(),
        notes = db.notes().all(),
        todos = db.todos().all(),
        images = images,
    )
    data to imageBytes
}

/** The collected backup plus the image bytes to put in the ZIP. */
typealias CollectedBackup = Pair<BackupData, Map<String, ByteArray>>
