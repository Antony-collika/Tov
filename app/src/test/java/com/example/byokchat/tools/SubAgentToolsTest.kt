package com.example.byokchat.tools

import com.example.byokchat.chat.ActiveSubRun
import com.example.byokchat.chat.SubAgentConfig
import com.example.byokchat.chat.SubAgentRunner
import com.example.byokchat.chat.SubRunToolCall
import com.example.byokchat.chat.encodeSubRunToolCalls
import com.example.byokchat.data.SubRunEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Tests for the delegate tool's schema, built from the loaded team (Phase 3). */
class SubAgentToolsTest {

    private fun agentParam(schema: JsonObject): JsonObject =
        (schema["properties"] as JsonObject)["agent"] as JsonObject

    private fun enumOf(schema: JsonObject): List<String>? =
        (agentParam(schema)["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun testAgent(
        id: String = "research",
        name: String = "Research",
        description: String = "Researches a topic.",
        enabled: Boolean = true,
    ) = SubAgentConfig(
        id = id, name = name, role = "Research agent", description = description,
        systemPrompt = "You are Research. Research the web.",
        toolUids = listOf("web.search", "web.fetch"),
        workspaceFolder = "Research",
        enabled = enabled,
    )

    @Test fun delegateSchemaListsEnabledAgentsOnly() {
        val review = testAgent(id = "review", name = "Review", description = "Reviews a draft.")
        val off = testAgent(id = "off", name = "Off", description = "Turned off.")
        val schema = SubAgentTools.delegateSchema(listOf(testAgent(), review, off.copy(enabled = false)))
        assertEquals(listOf("research", "review"), enumOf(schema))
        val desc = agentParam(schema)["description"] as JsonPrimitive
        assertTrue(desc.content.contains("research — Researches a topic."))
        assertTrue(desc.content.contains("review — Reviews a draft."))
        assertTrue(!desc.content.contains("off"))
    }

    @Test fun delegateSchemaWithNoEnabledAgentHasNoEnum() {
        val schema = SubAgentTools.delegateSchema(listOf(testAgent().copy(enabled = false)))
        assertNull(enumOf(schema))
        val desc = agentParam(schema)["description"] as JsonPrimitive
        assertTrue(desc.content.contains("No sub-agents are enabled"))
    }

    @Test fun delegateSchemaWithEmptyTeamHasNoEnum() {
        assertNull(enumOf(SubAgentTools.delegateSchema(emptyList())))
    }

    @Test fun sendMessageIsNotInTheRegistry_MainCannotCallIt() {
        // The registry is what Main sees: the delegate, the stop, the
        // recall, the recruit and the wake are the sub tools in it.
        // send_message is offered by the runner to subs only, so Main can
        // never call it — but it is still shown in the agent settings as
        // a built-in row, so the user knows the sub has it. The wake tool
        // IS in the registry (subs pick it in their settings), but Main
        // never holds it: the Tools picker and the engine's selection both
        // filter it out — Main talks to agents with delegate and recall.
        val tools = SubAgentTools.build({ null })
        assertEquals(listOf("sub.delegate", "sub.stop", "sub.recall", "sub.recruit", "sub.wake"), tools.map { it.uid })
        assertTrue(tools.none { it.uid == SubAgentRunner.SEND_TOOL_UID })
        assertTrue(tools.none { it.defaultName == SubAgentRunner.SEND_TOOL_NAME })
        assertEquals("sub.send", SubAgentRunner.SEND_TOOL_UID)
        assertEquals("send_message", SubAgentRunner.SEND_TOOL_NAME)
        assertEquals("recruit_teammate", tools.first { it.uid == SubAgentTools.RECRUIT_TOOL_UID }.defaultName)
        assertEquals("wake_agent", tools.first { it.uid == SubAgentTools.WAKE_TOOL_UID }.defaultName)
    }

    @Test fun delegateSchemaHasRequiredTitleParam() {
        val schema = SubAgentTools.delegateSchema(listOf(testAgent()))
        val props = schema["properties"] as JsonObject
        val title = props["title"] as JsonObject
        assertEquals("string", (title["type"] as JsonPrimitive).content)
        val required = (schema["required"] as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        assertEquals(listOf("agent", "title", "brief"), required)
    }

    @Test fun stopSchemaListsEnabledAgentsOnly() {
        val review = testAgent(id = "review", name = "Review", description = "Reviews a draft.")
        val schema = SubAgentTools.stopSchema(listOf(testAgent(), review, testAgent(id = "off", name = "Off").copy(enabled = false)))
        assertEquals(listOf("research", "review"), enumOf(schema))
    }

    @Test fun stopSchemaHasRequiredAgentParamOnly() {
        val schema = SubAgentTools.stopSchema(listOf(testAgent()))
        val required = (schema["required"] as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        assertEquals(listOf("agent"), required)
    }

    @Test fun recallSchemaListsEnabledAgentsOnly() {
        val review = testAgent(id = "review", name = "Review", description = "Reviews a draft.")
        val schema = SubAgentTools.recallSchema(listOf(testAgent(), review, testAgent(id = "off", name = "Off").copy(enabled = false)))
        assertEquals(listOf("research", "review"), enumOf(schema))
    }

    @Test fun recallSchemaWithEmptyTeamHasNoEnum() {
        assertNull(enumOf(SubAgentTools.recallSchema(emptyList())))
    }

    @Test fun recallSchemaHasRequiredTurnParam() {
        val schema = SubAgentTools.recallSchema(listOf(testAgent()))
        val props = schema["properties"] as JsonObject
        val turn = props["turn"] as JsonObject
        assertEquals("integer", (turn["type"] as JsonPrimitive).content)
        val required = (schema["required"] as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        assertEquals(listOf("agent", "turn"), required)
    }

    @Test fun wakeSchemaOffersMainPlusEnabledAgents() {
        val review = testAgent(id = "review", name = "Review", description = "Reviews a draft.")
        val schema = SubAgentTools.wakeSchema(listOf(testAgent(), review, testAgent(id = "off", name = "Off").copy(enabled = false)))
        assertEquals(listOf("main", "research", "review"), enumOf(schema))
        val desc = agentParam(schema)["description"] as JsonPrimitive
        assertTrue(desc.content.contains("main"))
        assertTrue(desc.content.contains("review — Reviews a draft."))
        assertTrue(!desc.content.contains("off"))
        val required = (schema["required"] as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        assertEquals(listOf("agent", "message"), required)
    }

    @Test fun wakeSchemaWithEmptyTeamStillOffersMain() {
        assertEquals(listOf("main"), enumOf(SubAgentTools.wakeSchema(emptyList())))
    }

    @Test fun wakeRunTitleReusesLatestTitleOrFallsBackToWaker() {
        assertEquals("Find football news", SubAgentTools.wakeRunTitle("Find football news", "Danny"))
        assertEquals("Tin từ Danny", SubAgentTools.wakeRunTitle(null, "Danny"))
        assertEquals("Tin từ Danny", SubAgentTools.wakeRunTitle("", "Danny"))
        assertEquals("Tin từ Danny", SubAgentTools.wakeRunTitle("   ", "Danny"))
    }

    @Test fun wakeTextsNameTheWaker() {
        val toMain = SubAgentTools.wakeTextForMain("Danny", "done: 3 goals, see the file")
        assertTrue(toMain.contains("Danny"))
        assertTrue(toMain.contains("done: 3 goals, see the file"))
        val toSub = SubAgentTools.wakeBriefForSub("Danny", "done: 3 goals, see the file")
        assertTrue(toSub.contains("Danny"))
        assertTrue(toSub.contains("done: 3 goals, see the file"))
    }

    private fun storedRun(
        toolCallsJson: String = encodeSubRunToolCalls(
            listOf(SubRunToolCall("youtube_search", "{\"q\": \"lofi morning\"}")),
        ),
        reasoning: String = "Let me think about cheerful music.",
        fullReport: String = "Sub-agent Monica finished: done.\nTask: Find cheerful morning music.\n\nSongs that'll make you dance.",
    ) = SubRunEntity(
        conversationId = "conv-1", turnNo = 3, agent = "monica", agentName = "Monica",
        title = "Find cheerful morning music", brief = "Find cheerful morning music on YouTube and play it.",
        status = "done", rounds = 4, tokens = 4756,
        toolCallsJson = toolCallsJson, messagesToMainJson = "[]",
        fullReport = fullReport, reasoning = reasoning,
        startedAt = 1000L, finishedAt = 2000L,
    )

    @Test fun recallSubRunReturnsFormattedRecord() = runBlocking {
        val record = storedRun()
        val text = SubAgentTools.recallSubRun(
            lookup = { _, agent, turn -> if (agent == "monica" && turn == 3) record else null },
            conversationId = "conv-1", agentId = "monica", turnNo = 3,
        )
        assertTrue(text.contains("\"Find cheerful morning music\""))
        assertTrue(text.contains("Songs that'll make you dance"))
        assertTrue(text.contains("youtube_search"))
        assertTrue(text.contains("lofi morning"))
        assertTrue(text.contains("Let me think about cheerful music."))
    }

    @Test fun recallSubRunThrowsClearErrorWhenNotFound() = runBlocking {
        try {
            SubAgentTools.recallSubRun(
                lookup = { _, _, _ -> null },
                conversationId = "conv-1", agentId = "monica", turnNo = 9,
            )
            fail("Expected ToolException for a missing run.")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("monica"))
            assertTrue(e.message!!.contains("9"))
        }
    }

    private fun runningDanny(turnNo: Int = 1) = ActiveSubRun(
        agentId = "danny", name = "Danny", brief = "Find football news.",
        startedAt = 1000L, turnNo = turnNo, title = "Find football news",
        startedBy = "bob", startedByName = "Bob",
    )

    @Test fun recallSubRunReturnsRefusalWhenSameTurnRunStillWorking() = runBlocking {
        // Bob delegated Danny in turn 1 and polls it in the same turn:
        // no stored record yet, the run is still working — return the
        // refusal as a normal result, not an error, so the caller reads
        // it as an instruction to finish its turn and wait to be woken.
        val text = SubAgentTools.recallSubRun(
            lookup = { _, _, _ -> null },
            conversationId = "conv-1", agentId = "danny", turnNo = 1,
            activeRun = runningDanny(turnNo = 1), callerTurnNo = 1,
        )
        assertTrue(text.contains("Danny"))
        assertTrue(text.contains("turn 1"))
        assertTrue(text.contains("Find football news"))
        assertTrue(text.contains("Finish your turn now"))
        assertTrue(text.contains("Do not call this tool while the sub-agent is working"))
        assertTrue(text.contains("Do not start Danny's work yourself"))
    }

    @Test fun recallSubRunStillReturnsFinishedRecordWhenRunAlreadyStored() = runBlocking {
        // Stored record wins: even if a run with the same agent and turn
        // is somehow still listed as active, a finished turn 1 reads back.
        val text = SubAgentTools.recallSubRun(
            lookup = { _, agent, turn -> if (agent == "danny" && turn == 1) storedRun() else null },
            conversationId = "conv-1", agentId = "danny", turnNo = 1,
            activeRun = runningDanny(turnNo = 1), callerTurnNo = 1,
        )
        assertTrue(text.contains("Songs that'll make you dance"))
        assertTrue(!text.contains("still working"))
    }

    @Test fun recallSubRunThrowsWhenActiveRunIsADifferentTurn() = runBlocking {
        // Per-run check, not per-agent: Danny turn 2 is still working,
        // but the caller asked for turn 1, which was never stored and
        // is not running — so this is a missing run, not polling.
        try {
            SubAgentTools.recallSubRun(
                lookup = { _, _, _ -> null },
                conversationId = "conv-1", agentId = "danny", turnNo = 1,
                activeRun = runningDanny(turnNo = 2), callerTurnNo = 1,
            )
            fail("Expected ToolException for a missing run.")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("No stored result"))
        }
    }

    @Test fun recallSubRunThrowsWhenCallerIsInALaterTurn() = runBlocking {
        // Same-turn scope: the run was delegated in turn 1 and is still
        // working, but the caller is now in turn 2 — the refusal only
        // covers polling inside the turn that delegated it.
        try {
            SubAgentTools.recallSubRun(
                lookup = { _, _, _ -> null },
                conversationId = "conv-1", agentId = "danny", turnNo = 1,
                activeRun = runningDanny(turnNo = 1), callerTurnNo = 2,
            )
            fail("Expected ToolException for a missing run.")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("No stored result"))
        }
    }

    @Test fun buildRecallRunningRefusalNamesAgentTurnAndTitle() {
        val text = SubAgentTools.buildRecallRunningRefusal(runningDanny(turnNo = 2))
        assertTrue(text.contains("Danny"))
        assertTrue(text.contains("turn 2"))
        assertTrue(text.contains("Find football news"))
    }

    @Test fun formatSubRunRecordOmitsEmptySections() {
        val record = storedRun(toolCallsJson = "[]", reasoning = "", fullReport = "Sub-agent Monica finished: done.")
        val text = SubAgentTools.formatSubRunRecord(record)
        assertTrue(!text.contains("Tools called:"))
        assertTrue(!text.contains("Reasoning:"))
        assertTrue(text.contains("Full report:"))
    }

    private fun availableTools() = listOf(
        "web.search" to "web_search",
        "web.fetch" to "fetch_url",
        "txt.read" to "read_text",
    )

    private fun recruitConfig(
        name: String = "Music",
        role: String = "Music agent",
        description: String = "Finds and plays music.",
        systemPrompt: String = "You are Music. Find music.",
        workspaceFolder: String = "Music",
        tools: List<String> = listOf("web_search", "web.fetch"),
        existingIds: Set<String> = emptySet(),
    ) = SubAgentTools.buildRecruitConfig(
        name = name, role = role, description = description,
        systemPrompt = systemPrompt, workspaceFolder = workspaceFolder,
        tools = tools, availableTools = availableTools(), existingIds = existingIds,
    )

    @Test fun buildRecruitConfigSlugsIdAndKeepsDefaults() {
        val config = recruitConfig()
        assertEquals("music", config.id)
        assertEquals("Music", config.name)
        assertEquals("Music agent", config.role)
        assertEquals("Finds and plays music.", config.description)
        assertEquals("You are Music. Find music.", config.systemPrompt)
        assertEquals(listOf("web.search", "web.fetch"), config.toolUids)
        assertEquals("Music", config.workspaceFolder)
        assertEquals(10, config.roundCap)
        assertEquals(32_000, config.tokenBudget)
        assertNull(config.model)
        assertNull(config.modelProviderId)
        assertEquals(com.example.byokchat.chat.HistoryVisibility.BRIEF_ONLY, config.historyVisibility)
        assertTrue(config.enabled)
        assertTrue(config.validate().isEmpty())
    }

    @Test fun buildRecruitConfigResolvesUidsAsWellAsNames() {
        val config = recruitConfig(tools = listOf("web.search", "read_text"))
        assertEquals(listOf("web.search", "txt.read"), config.toolUids)
    }

    @Test fun buildRecruitConfigRejectsUnknownTool() {
        try {
            recruitConfig(tools = listOf("nope"))
            fail("Expected ToolException for an unknown tool.")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("nope"))
            assertTrue(e.message!!.contains("web_search"))
        }
    }

    @Test fun buildRecruitConfigRejectsEmptyTools() {
        try {
            recruitConfig(tools = emptyList())
            fail("Expected ToolException for empty tools.")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("tools"))
        }
    }

    @Test fun buildRecruitConfigRejectsBlankFields() {
        try {
            recruitConfig(name = "  ")
            fail("Expected ToolException for a blank name.")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("name"))
        }
        try {
            recruitConfig(systemPrompt = "")
            fail("Expected ToolException for a blank system prompt.")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("system_prompt"))
        }
        try {
            recruitConfig(workspaceFolder = "")
            fail("Expected ToolException for a blank workspace folder.")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("workspace_folder"))
        }
    }

    @Test fun buildRecruitConfigRejectsDuplicateId() {
        try {
            recruitConfig(existingIds = setOf("music"))
            fail("Expected ToolException for a duplicate id.")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("music"))
        }
    }

    @Test fun recruitSchemaHasRequiredParams() {
        val schema = SubAgentTools.recruitSchema()
        val props = schema["properties"] as JsonObject
        assertEquals("string", ((props["name"] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("array", ((props["tools"] as JsonObject)["type"] as JsonPrimitive).content)
        val required = (schema["required"] as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        assertEquals(listOf("name", "role", "description", "system_prompt", "workspace_folder", "tools"), required)
    }
}
