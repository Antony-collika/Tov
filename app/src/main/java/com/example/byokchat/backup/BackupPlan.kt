package com.example.byokchat.backup

import com.example.byokchat.ai.MetadataParams
import com.example.byokchat.chat.SubAgentConfig
import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.NoteEntity
import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.RecentlyPlayedEntity
import com.example.byokchat.data.SubRunEntity
import com.example.byokchat.data.TodoEntity
import com.example.byokchat.tools.ToolPref

/**
 * Restore rule (Duy, 2026-11-17 ~15:11): never replace — append where
 * possible; where there is only one value, ask once on a single screen
 * after decrypting (each conflict: keep current / use backup, default
 * keep current). The Request panel layout is NOT in the backup at all
 * (Duy, 2026-11-17 ~15:16), so there is no layout conflict.
 *
 * Everything in this file is pure (no Android), so the rules are
 * unit-tested without a device.
 */
enum class ConflictChoice { KEEP_CURRENT, USE_BACKUP }

/** One single-value clash the user must decide: keep current or use the backup's. */
data class ConflictItem(
    /** Stable key the UI's decision map uses, e.g. "defaultPrompt", "provider:<id>". */
    val id: String,
    val title: String,
    val currentSummary: String,
    val backupSummary: String,
)

/** What the device currently holds, as plain data for the plan computation. */
data class CurrentBackupState(
    val providers: List<ProviderConfig>,
    val activeProviderId: String?,
    val currentModelProviderId: String?,
    val currentModel: String?,
    val metadata: MetadataParams,
    val searchKeys: Map<String, String>,
    val searchProvider: String,
    val youtubeKey: String,
    val toolPrefs: Map<String, ToolPref>,
    /** Effective name + description the model sees of every tool, by uid. */
    val toolNames: Map<String, String>,
    val toolDescriptions: Map<String, String>,
    val agents: List<SubAgentConfig>,
    val defaultPrompt: String,
    val conversationIds: Set<String>,
    val noteIds: Set<String>,
    val todoIds: Set<String>,
)

/** The full restore plan: what is appended, what is skipped, what the user must decide. */
data class RestorePlan(
    val backupCreatedAt: Long,
    val newConversations: List<ConversationEntity>,
    val newMessages: List<MessageEntity>,
    val newSubRuns: List<SubRunEntity>,
    val newRecentlyPlayed: List<RecentlyPlayedEntity>,
    val newNotes: List<NoteEntity>,
    val newTodos: List<TodoEntity>,
    val newAgents: List<SubAgentConfig>,
    val newProviders: List<ProviderConfig>,
    /** Tool prefs the device has no edit for — taken from the backup without asking. */
    val newToolPrefs: Map<String, ToolPref>,
    /** Image entries to import, by ZIP entry name ("images/<file>"). Only images of imported messages. */
    val newImageEntries: Set<String>,
    val conflicts: List<ConflictItem>,
    /** Human-readable lines for the review screen: what will be added. */
    val appendLines: List<String>,
    /** Human-readable lines: what is skipped because it is already here. */
    val skippedLines: List<String>,
    /**
     * Human-readable lines: single values taken from the backup without
     * asking (no conflict — the device has nothing different there, e.g.
     * a fresh device). The applier takes them whenever the plan has no
     * conflict for that item; this list only tells the review screen.
     */
    val silentLines: List<String>,
    /** Folder display names to pick again (SAF permissions cannot be restored). */
    val folderReminder: List<String>,
    /** The backup itself, so apply-time can read the chosen values. */
    val backup: BackupData,
)

/**
 * The review screen appears only when there is at least one conflict to
 * decide. With no conflicts the restore applies straight away — the user
 * is never asked a question that has no decision in it.
 */
fun RestorePlan.needsReview(): Boolean = conflicts.isNotEmpty()

internal fun shorten(text: String, max: Int = 100): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}

internal fun metadataSummary(m: MetadataParams): String =
    "effort=${m.effort}, stop=${m.stopSequences.size}, " +
        "keepSignature=${m.keepThoughtSignature}, replay=${m.replayReasoning}"

internal fun providerSummary(p: ProviderConfig): String = "${p.name} (${p.type.label})"

/**
 * Compute the restore plan for [backup] against [current].
 * Rules:
 * - Chats/notes/todos whose id already exists here are SKIPPED (importing
 *   the device's own backup again must not duplicate anything). Only new
 *   ids are appended, with their messages, sub runs and recently-played
 *   rows (kept under the original ids — ids are unique, so nothing collides).
 * - New providers/agents are appended; same id with different content is
 *   a conflict (the key/config may differ).
 * - Tools: a tool the device has no edit for takes the backup's edit
 *   silently; a tool whose backup name or description differs from this
 *   device's (or both sides edited it differently) is a conflict.
 * - Single values (default prompt, active model, search provider,
 *   YouTube key, per-engine search keys, metadata) are conflicts ONLY
 *   when the device has a non-default value that differs from the
 *   backup's — a fresh device just takes the backup silently.
 */
fun computeRestorePlan(current: CurrentBackupState, backup: BackupData): RestorePlan {
    val conflicts = mutableListOf<ConflictItem>()
    val appendLines = mutableListOf<String>()
    val skippedLines = mutableListOf<String>()

    // --- Chats: skip by id, append the rest with all their rows. ---
    val newConversations = backup.conversations.filter { it.id !in current.conversationIds }
    val skippedChats = backup.conversations.size - newConversations.size
    val newConvIds = newConversations.map { it.id }.toSet()
    val newMessages = backup.messages.filter { it.conversationId in newConvIds }
    val newSubRuns = backup.subRuns.filter { it.conversationId in newConvIds }
    val newRecentlyPlayed = backup.recentlyPlayed.filter { it.conversationId in newConvIds }
    if (newConversations.isNotEmpty()) {
        appendLines += "${newConversations.size} chat(s) with ${newMessages.size} message(s)"
    }
    if (skippedChats > 0) {
        skippedLines += "$skippedChats chat(s) already on this device — skipped"
    }

    // --- Notes / todos: skip by id. ---
    val newNotes = backup.notes.filter { it.id !in current.noteIds }
    val newTodos = backup.todos.filter { it.id !in current.todoIds }
    if (newNotes.isNotEmpty()) appendLines += "${newNotes.size} note(s)"
    if (newTodos.isNotEmpty()) appendLines += "${newTodos.size} to-do(s)"
    if (backup.notes.size - newNotes.size > 0) {
        skippedLines += "${backup.notes.size - newNotes.size} note(s) already on this device — skipped"
    }
    if (backup.todos.size - newTodos.size > 0) {
        skippedLines += "${backup.todos.size - newTodos.size} to-do(s) already on this device — skipped"
    }

    // --- Images: only the ones the imported messages reference. ---
    val newImageEntries = newMessages
        .flatMap { decodeImagePaths(it.imagePaths) }
        .mapNotNull { backup.images[it] }
        .toSet()
    if (newImageEntries.isNotEmpty()) appendLines += "${newImageEntries.size} attached image(s)"

    // --- Providers: append new, conflict on same id with different content. ---
    val currentProviders = current.providers.associateBy { it.id }
    val newProviders = mutableListOf<ProviderConfig>()
    for (p in backup.providers) {
        val existing = currentProviders[p.id]
        when {
            existing == null -> newProviders += p
            existing != p -> conflicts += ConflictItem(
                id = "provider:${p.id}",
                title = "Provider ${p.name}",
                currentSummary = providerSummary(existing) +
                    if (existing.apiKey != p.apiKey) " · key differs" else "",
                backupSummary = providerSummary(p) +
                    if (existing.apiKey != p.apiKey) " · key differs" else "",
            )
            // identical -> nothing to do
        }
    }
    if (newProviders.isNotEmpty()) {
        appendLines += "${newProviders.size} provider(s): ${newProviders.joinToString(", ") { it.name }}"
    }

    // --- Sub-agents: same rule as providers. ---
    val currentAgents = current.agents.associateBy { it.id }
    val newAgents = mutableListOf<SubAgentConfig>()
    for (a in backup.agents) {
        val existing = currentAgents[a.id]
        when {
            existing == null -> newAgents += a
            existing != a -> conflicts += ConflictItem(
                id = "agent:${a.id}",
                title = "Sub-agent ${a.name}",
                currentSummary = "${existing.name} — ${existing.role}",
                backupSummary = "${a.name} — ${a.role}",
            )
        }
    }
    if (newAgents.isNotEmpty()) {
        appendLines += "${newAgents.size} sub-agent(s): ${newAgents.joinToString(", ") { it.name }}"
    }

    // --- Tools (Duy, 2026-11-17 ~15:17): a tool whose backup name or
    // description differs from this device's is asked about. Rules:
    // - tool no longer on this device -> skipped, nothing to apply to;
    // - device has no edit and the backup has one -> take it silently
    //   (the description differs because of the backup's edit);
    // - neither side has an edit but the effective name/description
    //   differs (the app versions renamed a tool or rewrote its default
    //   description) -> conflict, asked about;
    // - both sides edited and anything differs (mode, name, description)
    //   -> conflict, asked about.
    // A conflict's USE_BACKUP applies the backup's edit for that tool
    // (see BackupApply): mode from the backup, description = the backup's
    // effective description unless it equals this device's default.
    val newToolPrefs = mutableMapOf<String, ToolPref>()
    val toolUids = (backup.toolPrefs.keys + backup.toolNames.keys + backup.toolDescriptions.keys).toSet()
    for (uid in toolUids.sorted()) {
        val currentName = current.toolNames[uid]
        val backupName = backup.toolNames[uid]
        val currentDesc = current.toolDescriptions[uid]
        val backupDesc = backup.toolDescriptions[uid]
        if (currentName == null || currentDesc == null) {
            skippedLines += "Tool $uid no longer exists on this device — skipped"
            continue
        }
        val existing = current.toolPrefs[uid] ?: ToolPref()
        val pref = backup.toolPrefs[uid] ?: ToolPref()
        // Only compare name/description when the backup actually recorded
        // them (old backups predate the maps and have no entries).
        val nameDiffers = backupName != null && backupName != currentName
        val descDiffers = backupDesc != null && backupDesc != currentDesc
        val prefDiffers = existing != pref
        if (!nameDiffers && !descDiffers && !prefDiffers) continue
        if (existing.isEmpty() && !pref.isEmpty() && !nameDiffers) {
            newToolPrefs[uid] = pref
            continue
        }
        fun toolSummary(name: String?, desc: String?, toolPref: ToolPref): String {
            val parts = mutableListOf<String>()
            if (name != null) parts += name
            if (desc != null) parts += shorten(desc, 80)
            toolPref.mode?.let { parts += "mode=$it" }
            return parts.joinToString(" · ").ifBlank { "(no edits)" }
        }
        conflicts += ConflictItem(
            id = "toolPref:$uid",
            title = "Tool $currentName",
            currentSummary = toolSummary(currentName, currentDesc, existing),
            backupSummary = toolSummary(backupName ?: currentName, backupDesc ?: currentDesc, pref),
        )
    }
    if (newToolPrefs.isNotEmpty()) appendLines += "${newToolPrefs.size} tool edit(s)"

    // --- Single values: conflict only when the device has a non-default value that differs. ---
    if (current.defaultPrompt.isNotBlank() && current.defaultPrompt != backup.defaultPrompt) {
        conflicts += ConflictItem(
            id = "defaultPrompt",
            title = "Default system prompt",
            currentSummary = shorten(current.defaultPrompt),
            backupSummary = shorten(backup.defaultPrompt),
        )
    }
    if (current.metadata != MetadataParams() && current.metadata != backup.metadata) {
        conflicts += ConflictItem(
            id = "metadata",
            title = "Metadata (effort, stop, reasoning)",
            currentSummary = metadataSummary(current.metadata),
            backupSummary = metadataSummary(backup.metadata),
        )
    }
    // NOTE: theme, max tool rounds and the request log are NOT backed up
    // (Duy, 2026-11-17 ~15:18) — no conflict items for them.
    val currentModel = Triple(current.activeProviderId, current.currentModelProviderId, current.currentModel)
    val backupModel = Triple(backup.activeProviderId, backup.currentModelProviderId, backup.currentModel)
    if ((currentModel.first != null || currentModel.second != null || currentModel.third != null) &&
        currentModel != backupModel
    ) {
        fun modelLabel(
            providers: List<ProviderConfig>,
            providerId: String?,
            model: String?,
        ): String {
            val name = providers.firstOrNull { it.id == providerId }?.name ?: providerId
            return if (name == null && model == null) "(none)" else "$name / ${model ?: "(no model)"}"
        }
        conflicts += ConflictItem(
            id = "activeModel",
            title = "Provider and model in use",
            currentSummary = modelLabel(current.providers, currentModel.first, currentModel.third),
            backupSummary = modelLabel(backup.providers, backupModel.first, backupModel.third),
        )
    }
    if (current.searchProvider != "BRAVE" && current.searchProvider != backup.searchProvider) {
        conflicts += ConflictItem(
            id = "searchProvider",
            title = "Web search engine",
            currentSummary = current.searchProvider,
            backupSummary = backup.searchProvider,
        )
    }
    if (current.youtubeKey.isNotBlank() && current.youtubeKey != backup.youtubeKey) {
        // Never show the key itself — only that a (different) key is saved.
        conflicts += ConflictItem(
            id = "youtubeKey",
            title = "YouTube API key",
            currentSummary = "Key saved",
            backupSummary = "Key saved (different)",
        )
    }
    val engines = (current.searchKeys.keys + backup.searchKeys.keys).toSet()
    for (engine in engines.sorted()) {
        val cur = current.searchKeys[engine].orEmpty()
        val bak = backup.searchKeys[engine].orEmpty()
        if (cur.isNotBlank() && bak.isNotBlank() && cur != bak) {
            conflicts += ConflictItem(
                id = "searchKey:$engine",
                title = "Web search key ($engine)",
                currentSummary = "Key saved",
                backupSummary = "Key saved (different)",
            )
        }
    }

    // --- Folder reminder: SAF permissions cannot be restored. ---
    val folderReminder = buildList {
        addAll(backup.playgroundFolderNames)
        backup.sessionFolderName?.let { add(it) }
    }

    // --- Single values taken silently: no conflict for them, so the
    // backup's value is used (a fresh device takes everything; a device
    // that already matches needs nothing). Blank backup values take
    // nothing — they must never clear what the device already has.
    fun hasConflict(id: String) = conflicts.any { it.id == id }
    val silentLines = buildList {
        if (!hasConflict("defaultPrompt") && backup.defaultPrompt.isNotBlank() &&
            backup.defaultPrompt != current.defaultPrompt
        ) add("Default system prompt from the backup")
        if (!hasConflict("metadata") && backup.metadata != MetadataParams() &&
            backup.metadata != current.metadata
        ) add("Metadata settings from the backup")
        if (!hasConflict("activeModel") &&
            (backupModel.first != null || backupModel.second != null || backupModel.third != null) &&
            backupModel != currentModel
        ) add("Provider and model in use from the backup")
        if (!hasConflict("searchProvider") && backup.searchProvider != current.searchProvider) {
            add("Web search engine from the backup (${backup.searchProvider})")
        }
        if (!hasConflict("youtubeKey") && backup.youtubeKey.isNotBlank() &&
            backup.youtubeKey != current.youtubeKey
        ) add("YouTube API key from the backup")
        val keyEngines = (current.searchKeys.keys + backup.searchKeys.keys).toSet()
        for (engine in keyEngines.sorted()) {
            val bak = backup.searchKeys[engine].orEmpty()
            if (!hasConflict("searchKey:$engine") && bak.isNotBlank() && bak != current.searchKeys[engine].orEmpty()) {
                add("Web search key ($engine) from the backup")
            }
        }
    }

    return RestorePlan(
        backupCreatedAt = backup.createdAt,
        newConversations = newConversations,
        newMessages = newMessages,
        newSubRuns = newSubRuns,
        newRecentlyPlayed = newRecentlyPlayed,
        newNotes = newNotes,
        newTodos = newTodos,
        newAgents = newAgents,
        newProviders = newProviders,
        newToolPrefs = newToolPrefs,
        newImageEntries = newImageEntries,
        conflicts = conflicts,
        appendLines = appendLines,
        skippedLines = skippedLines,
        silentLines = silentLines,
        folderReminder = folderReminder,
        backup = backup,
    )
}

/** Image paths stored in a message row (JSON array); broken JSON reads as empty. */
internal fun decodeImagePaths(imagePaths: String): List<String> {
    if (imagePaths.isBlank()) return emptyList()
    return runCatching {
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(imagePaths)
    }.getOrDefault(emptyList())
}
