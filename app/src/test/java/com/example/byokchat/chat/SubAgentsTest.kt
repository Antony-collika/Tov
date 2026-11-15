package com.example.byokchat.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the Phase 2 sub-agent split, config and journal record. */
class SubAgentsTest {

    @Test fun briefDetailSplitUsesTheMarker() {
        val (detail, brief) = splitBriefDetail("Full report.\nWith sources.\n---BRIEF---\nDone — found 5 sources.")
        assertEquals("Full report.\nWith sources.", detail)
        assertEquals("Done — found 5 sources.", brief)
    }

    @Test fun briefDetailSplitWithoutMarkerUsesFirstParagraph() {
        val answer = "First paragraph here.\n\nSecond paragraph."
        val (detail, brief) = splitBriefDetail(answer)
        assertEquals(answer, detail)
        assertEquals("First paragraph here.", brief)
    }

    @Test fun briefDetailSplitWithEmptyBriefFallsBackToDetailStart() {
        val (detail, brief) = splitBriefDetail("Some report.\n---BRIEF---\n")
        assertEquals("Some report.", detail)
        assertEquals("Some report.", brief)
    }

    @Test fun briefDetailSplitWithBlankAnswerIsBlank() {
        val (detail, brief) = splitBriefDetail("")
        assertEquals("", detail)
        assertEquals("", brief)
    }

    @Test fun researchAgentHasDecidedCeilingsAndTools() {
        val agent = SubAgents.RESEARCH
        assertEquals("research", agent.id)
        assertEquals(10, agent.roundCap)
        assertEquals(32_000, agent.tokenBudget)
        assertEquals(listOf("web.search", "web.fetch"), agent.toolUids)
        assertEquals("Research", agent.workspaceFolder)
        assertTrue(agent.defaultGreeting.isNotBlank())
        assertTrue(agent.systemPrompt.contains("---BRIEF---"))
    }

    @Test fun subRunRecordRoundTripsAsJson() {
        val json = Json { ignoreUnknownKeys = true }
        val record = SubRunRecord(
            agent = "research", agentName = "Research", brief = "Find X",
            status = "done", rounds = 2, tokens = 1234,
            toolCalls = listOf("web_search"), startedAt = 1000, finishedAt = 2000,
            resultSummary = "Found it.", error = null,
        )
        val decoded = json.decodeFromString(SubRunRecord.serializer(), encodeSubRunRecord(record))
        assertEquals(record, decoded)
    }

    @Test fun displayNameResolvesIdAndName() {
        assertEquals("Research", SubAgents.displayName("research"))
        assertEquals("Research", SubAgents.displayName("Research"))
        assertEquals("ghost", SubAgents.displayName("ghost"))
    }
}
