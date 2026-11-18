package com.example.byokchat.tools

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
        // The registry is what Main sees: the delegate, the stop and the
        // recall are the only sub tools in it. send_message is offered by
        // the runner to subs only, so Main can never call it — but it is
        // still shown in the agent settings as a built-in row, so the user
        // knows the sub has it.
        val tools = SubAgentTools.build({ null })
        assertEquals(listOf("sub.delegate", "sub.stop", "sub.recall"), tools.map { it.uid })
        assertTrue(tools.none { it.uid == SubAgentRunner.SEND_TOOL_UID })
        assertTrue(tools.none { it.defaultName == SubAgentRunner.SEND_TOOL_NAME })
        assertEquals("sub.send", SubAgentRunner.SEND_TOOL_UID)
        assertEquals("send_message", SubAgentRunner.SEND_TOOL_NAME)
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

    @Test fun formatSubRunRecordOmitsEmptySections() {
        val record = storedRun(toolCallsJson = "[]", reasoning = "", fullReport = "Sub-agent Monica finished: done.")
        val text = SubAgentTools.formatSubRunRecord(record)
        assertTrue(!text.contains("Tools called:"))
        assertTrue(!text.contains("Reasoning:"))
        assertTrue(text.contains("Full report:"))
    }
}
