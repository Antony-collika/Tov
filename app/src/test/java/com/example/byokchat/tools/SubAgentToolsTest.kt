package com.example.byokchat.tools

import com.example.byokchat.chat.SubAgentConfig
import com.example.byokchat.chat.SubAgentRunner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // The registry is what Main sees: the delegate is the only sub tool
        // in it. send_message is offered by the runner to subs only, so
        // Main can never call it — but it is still shown in the agent
        // settings as a built-in row, so the user knows the sub has it.
        val tools = SubAgentTools.build({ null })
        assertEquals(listOf("sub.delegate"), tools.map { it.uid })
        assertTrue(tools.none { it.uid == SubAgentRunner.SEND_TOOL_UID })
        assertTrue(tools.none { it.defaultName == SubAgentRunner.SEND_TOOL_NAME })
        assertEquals("sub.send", SubAgentRunner.SEND_TOOL_UID)
        assertEquals("send_message", SubAgentRunner.SEND_TOOL_NAME)
    }
}
