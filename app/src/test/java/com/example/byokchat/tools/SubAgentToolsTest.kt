package com.example.byokchat.tools

import com.example.byokchat.chat.defaultResearchAgent
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

    @Test fun delegateSchemaListsEnabledAgentsOnly() {
        val review = defaultResearchAgent().copy(id = "review", name = "Review", description = "Reviews a draft.")
        val off = defaultResearchAgent().copy(id = "off", name = "Off", description = "Turned off.")
        val schema = SubAgentTools.delegateSchema(listOf(defaultResearchAgent(), review, off.copy(enabled = false)))
        assertEquals(listOf("research", "review"), enumOf(schema))
        val desc = agentParam(schema)["description"] as JsonPrimitive
        assertTrue(desc.content.contains("research — Researches a topic"))
        assertTrue(desc.content.contains("review — Reviews a draft."))
        assertTrue(!desc.content.contains("off"))
    }

    @Test fun delegateSchemaWithNoEnabledAgentHasNoEnum() {
        val schema = SubAgentTools.delegateSchema(listOf(defaultResearchAgent().copy(enabled = false)))
        assertNull(enumOf(schema))
        val desc = agentParam(schema)["description"] as JsonPrimitive
        assertTrue(desc.content.contains("No sub-agents are enabled"))
    }

    @Test fun delegateSchemaWithEmptyTeamHasNoEnum() {
        assertNull(enumOf(SubAgentTools.delegateSchema(emptyList())))
    }
}
