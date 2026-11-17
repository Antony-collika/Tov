package com.example.byokchat.backup

import com.example.byokchat.ai.MetadataParams
import com.example.byokchat.chat.SubAgentConfig
import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.NoteEntity
import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import com.example.byokchat.data.SubRunEntity
import com.example.byokchat.data.TodoEntity
import com.example.byokchat.tools.ToolPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restore-plan rules (Duy, 2026-11-17): never replace — append where
 * possible; where there is only one value, ask once on a single screen
 * (each conflict: keep current / use backup, default keep current).
 * The screen appears only when there is a conflict to decide — no
 * conflicts, no screen, the restore applies straight away (2026-11-17 ~15:32).
 */
class BackupPlanTest {
    private fun provider(id: String, name: String, key: String) =
        ProviderConfig(id = id, type = ProviderType.OPENAI, name = name, apiKey = key)

    private fun agent(id: String, name: String) = SubAgentConfig(
        id = id, name = name, role = "Researcher", description = "d",
        systemPrompt = "p", toolUids = emptyList(), workspaceFolder = "Research",
    )

    private fun chat(id: String) = ConversationEntity(id, "Chat $id", 100, 200)

    private fun message(id: String, convId: String, imagePaths: String = "[]") =
        MessageEntity(id = id, conversationId = convId, turnId = "t1", turnNo = 1, seq = 1L, role = "user", text = "hello", imagePaths = imagePaths, createdAt = 100)

    private fun current(
        providers: List<ProviderConfig> = emptyList(),
        toolPrefs: Map<String, ToolPref> = emptyMap(),
        toolNames: Map<String, String> = mapOf("fs.list" to "List directory"),
        toolDescriptions: Map<String, String> = mapOf("fs.list" to "List the files in a folder."),
        agents: List<SubAgentConfig> = emptyList(),
        defaultPrompt: String = "",
        searchKeys: Map<String, String> = emptyMap(),
        youtubeKey: String = "",
        conversationIds: Set<String> = emptySet(),
        noteIds: Set<String> = emptySet(),
        todoIds: Set<String> = emptySet(),
    ) = CurrentBackupState(
        providers = providers,
        activeProviderId = providers.firstOrNull()?.id,
        currentModelProviderId = providers.firstOrNull()?.id,
        currentModel = providers.firstOrNull()?.let { "gpt-4" },
        metadata = MetadataParams(),
        searchKeys = searchKeys,
        searchProvider = "BRAVE",
        youtubeKey = youtubeKey,
        toolPrefs = toolPrefs,
        toolNames = toolNames,
        toolDescriptions = toolDescriptions,
        agents = agents,
        defaultPrompt = defaultPrompt,
        conversationIds = conversationIds,
        noteIds = noteIds,
        todoIds = todoIds,
    )

    private fun backup(
        conversations: List<ConversationEntity> = emptyList(),
        messages: List<MessageEntity> = emptyList(),
        subRuns: List<SubRunEntity> = emptyList(),
        notes: List<NoteEntity> = emptyList(),
        todos: List<TodoEntity> = emptyList(),
        providers: List<ProviderConfig> = emptyList(),
        agents: List<SubAgentConfig> = emptyList(),
        toolPrefs: Map<String, ToolPref> = emptyMap(),
        toolNames: Map<String, String> = emptyMap(),
        toolDescriptions: Map<String, String> = emptyMap(),
        defaultPrompt: String = "",
        searchKeys: Map<String, String> = emptyMap(),
        youtubeKey: String = "",
        images: Map<String, String> = emptyMap(),
    ) = BackupData(
        conversations = conversations,
        messages = messages,
        subRuns = subRuns,
        notes = notes,
        todos = todos,
        providers = providers,
        activeProviderId = providers.firstOrNull()?.id,
        currentModelProviderId = providers.firstOrNull()?.id,
        currentModel = providers.firstOrNull()?.let { "gpt-4" },
        agents = agents,
        toolPrefs = toolPrefs,
        toolNames = toolNames,
        toolDescriptions = toolDescriptions,
        defaultPrompt = defaultPrompt,
        searchKeys = searchKeys,
        youtubeKey = youtubeKey,
        images = images,
    )

    @Test
    fun `importing the devices own backup again duplicates nothing`() {
        val plan = computeRestorePlan(
            current(conversationIds = setOf("c1"), noteIds = setOf("n1"), todoIds = setOf("t1")),
            backup(
                conversations = listOf(chat("c1")),
                messages = listOf(message("m1", "c1")),
                notes = listOf(NoteEntity("n1", "N", "C", 1, 1)),
                todos = listOf(TodoEntity("t1", "do it", createdAt = 1)),
            ),
        )
        assertTrue(plan.newConversations.isEmpty())
        assertTrue(plan.newMessages.isEmpty())
        assertTrue(plan.newNotes.isEmpty())
        assertTrue(plan.newTodos.isEmpty())
        assertTrue(plan.skippedLines.any { it.contains("1 chat(s) already") })
    }

    @Test
    fun `new chats are appended with their rows and original timestamps kept`() {
        val run = SubRunEntity(
            conversationId = "c2", turnNo = 1, agent = "bob", agentName = "Bob",
            title = "Search", brief = "b", status = "done", rounds = 2, tokens = 100,
            toolCallsJson = "[]", messagesToMainJson = "[]", fullReport = "r",
            reasoning = "", startedAt = 100, finishedAt = 200,
        )
        val plan = computeRestorePlan(
            current(),
            backup(
                conversations = listOf(chat("c2")),
                messages = listOf(message("m2", "c2")),
                subRuns = listOf(run),
            ),
        )
        assertEquals(listOf("c2"), plan.newConversations.map { it.id })
        assertEquals(100L, plan.newConversations.first().createdAt)
        assertEquals(200L, plan.newConversations.first().updatedAt)
        assertEquals(listOf("m2"), plan.newMessages.map { it.id })
        assertEquals(1, plan.newSubRuns.size)
    }

    @Test
    fun `images are imported only for imported messages`() {
        val plan = computeRestorePlan(
            current(conversationIds = setOf("c1")),
            backup(
                conversations = listOf(chat("c1"), chat("c2")),
                messages = listOf(
                    message("m1", "c1", imagePaths = """["/old/a.jpg"]"""),
                    message("m2", "c2", imagePaths = """["/old/b.jpg"]"""),
                ),
                images = mapOf("/old/a.jpg" to "images/a.jpg", "/old/b.jpg" to "images/b.jpg"),
            ),
        )
        assertEquals(setOf("images/b.jpg"), plan.newImageEntries)
    }

    @Test
    fun `new providers and agents are appended`() {
        val plan = computeRestorePlan(
            current(),
            backup(providers = listOf(provider("p1", "OpenAI", "k1")), agents = listOf(agent("a1", "Bob"))),
        )
        assertEquals(listOf("p1"), plan.newProviders.map { it.id })
        assertEquals(listOf("a1"), plan.newAgents.map { it.id })
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `same provider id with a different key is a conflict`() {
        val plan = computeRestorePlan(
            current(providers = listOf(provider("p1", "OpenAI", "old-key"))),
            backup(providers = listOf(provider("p1", "OpenAI", "new-key"))),
        )
        assertTrue(plan.newProviders.isEmpty())
        assertEquals(listOf("provider:p1"), plan.conflicts.map { it.id })
    }

    @Test
    fun `same agent id with different config is a conflict`() {
        val plan = computeRestorePlan(
            current(agents = listOf(agent("a1", "Bob"))),
            backup(agents = listOf(agent("a1", "Bobby"))),
        )
        assertTrue(plan.newAgents.isEmpty())
        assertEquals(listOf("agent:a1"), plan.conflicts.map { it.id })
    }

    @Test
    fun `tool with no local edit takes the backups edit silently`() {
        val plan = computeRestorePlan(
            current(),
            backup(
                toolPrefs = mapOf("fs.list" to ToolPref(description = "Edited description.")),
                toolDescriptions = mapOf("fs.list" to "Edited description."),
            ),
        )
        assertEquals(mapOf("fs.list" to ToolPref(description = "Edited description.")), plan.newToolPrefs)
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `tool edited on both sides differently is a conflict`() {
        val plan = computeRestorePlan(
            current(toolPrefs = mapOf("fs.list" to ToolPref(description = "My description."))),
            backup(
                toolPrefs = mapOf("fs.list" to ToolPref(description = "Other description.")),
                toolDescriptions = mapOf("fs.list" to "Other description."),
            ),
        )
        assertTrue(plan.newToolPrefs.isEmpty())
        assertEquals(listOf("toolPref:fs.list"), plan.conflicts.map { it.id })
    }

    @Test
    fun `tool whose backup description differs only because defaults changed is a conflict`() {
        // Neither side edited the tool, but the backup's effective
        // description differs from this device's (the app versions
        // rewrote the default) — ask, don't silently overwrite.
        val plan = computeRestorePlan(
            current(),
            backup(toolDescriptions = mapOf("fs.list" to "A newer default description.")),
        )
        assertEquals(listOf("toolPref:fs.list"), plan.conflicts.map { it.id })
    }

    @Test
    fun `tool that no longer exists on this device is skipped`() {
        val plan = computeRestorePlan(
            current(toolNames = emptyMap(), toolDescriptions = emptyMap()),
            backup(
                toolPrefs = mapOf("gone.tool" to ToolPref(description = "Edited.")),
                toolDescriptions = mapOf("gone.tool" to "Edited."),
            ),
        )
        assertTrue(plan.newToolPrefs.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
        assertTrue(plan.skippedLines.any { it.contains("gone.tool") })
    }

    @Test
    fun `default prompt conflict only when the device has a different one`() {
        // Fresh device (blank prompt) just takes the backup's silently.
        val fresh = computeRestorePlan(current(defaultPrompt = ""), backup(defaultPrompt = "Be helpful."))
        assertTrue(fresh.conflicts.none { it.id == "defaultPrompt" })
        // Device with a different prompt is asked.
        val clash = computeRestorePlan(
            current(defaultPrompt = "My prompt."),
            backup(defaultPrompt = "Be helpful."),
        )
        assertEquals(listOf("defaultPrompt"), clash.conflicts.map { it.id })
    }

    @Test
    fun `search key conflict only when both sides have a different key`() {
        val noneHere = computeRestorePlan(
            current(searchKeys = emptyMap()),
            backup(searchKeys = mapOf("BRAVE" to "bk")),
        )
        assertTrue(noneHere.conflicts.none { it.id.startsWith("searchKey:") })
        val clash = computeRestorePlan(
            current(searchKeys = mapOf("BRAVE" to "old")),
            backup(searchKeys = mapOf("BRAVE" to "new")),
        )
        assertEquals(listOf("searchKey:BRAVE"), clash.conflicts.map { it.id })
    }

    @Test
    fun `youtube key conflict only when both sides have a different key`() {
        val noneHere = computeRestorePlan(current(youtubeKey = ""), backup(youtubeKey = "yk"))
        assertTrue(noneHere.conflicts.none { it.id == "youtubeKey" })
        val clash = computeRestorePlan(current(youtubeKey = "old"), backup(youtubeKey = "new"))
        assertEquals(listOf("youtubeKey"), clash.conflicts.map { it.id })
    }

    @Test
    fun `fresh device takes single values from the backup silently`() {
        val plan = computeRestorePlan(
            current(defaultPrompt = ""),
            backup(defaultPrompt = "Be helpful.", youtubeKey = "yk", searchKeys = mapOf("BRAVE" to "bk")),
        )
        assertTrue(plan.conflicts.isEmpty())
        assertTrue(plan.silentLines.any { it.contains("Default system prompt") })
        assertTrue(plan.silentLines.any { it.contains("YouTube API key") })
        assertTrue(plan.silentLines.any { it.contains("BRAVE") })
    }

    @Test
    fun `theme max rounds and request log are never conflicts`() {
        // Those three are not backed up at all (Duy, 2026-11-17), so the
        // plan must never ask about them — even when everything else differs.
        val plan = computeRestorePlan(
            current(defaultPrompt = "Mine.", searchKeys = mapOf("BRAVE" to "old"), youtubeKey = "old"),
            backup(defaultPrompt = "Backup.", searchKeys = mapOf("BRAVE" to "new"), youtubeKey = "new"),
        )
        assertTrue(plan.conflicts.none { it.id in setOf("theme", "maxToolRounds", "requestLogEnabled") })
    }

    @Test
    fun `review screen only appears when there is a conflict to decide`() {
        // Duy, 2026-11-17 ~15:32: no conflicts means no screen at all —
        // the restore applies straight away, never a popup per conflict.
        val noConflicts = computeRestorePlan(
            current(defaultPrompt = ""),
            backup(defaultPrompt = "Be helpful.", youtubeKey = "yk"),
        )
        assertTrue(noConflicts.conflicts.isEmpty())
        assertEquals(false, noConflicts.needsReview())
        val oneConflict = computeRestorePlan(
            current(defaultPrompt = "Mine."),
            backup(defaultPrompt = "Backup."),
        )
        assertEquals(1, oneConflict.conflicts.size)
        assertEquals(true, oneConflict.needsReview())
    }
}
