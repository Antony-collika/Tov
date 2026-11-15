package com.example.byokchat.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests for the Phase 3 team model (config, validation) and the file store. */
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

    @Test fun fallbackBriefPrefersTheLastMessageSentToTheUser() {
        assertEquals("Sent brief.", fallbackBrief("Long final answer.", listOf("First note.", "Sent brief.")))
        assertEquals("First paragraph here.", fallbackBrief("First paragraph here.\n\nSecond paragraph.", emptyList()))
        assertEquals("", fallbackBrief("", emptyList()))
        assertEquals("Only text.", fallbackBrief("Only text.", listOf("", "  ")))
    }

    @Test fun seedTeamIsResearchOnlyWithDecidedCeilingsAndTools() {
        val team = defaultTeam()
        assertEquals(1, team.agents.size)
        val agent = team.agents[0]
        assertEquals("research", agent.id)
        assertEquals(10, agent.roundCap)
        assertEquals(32_000, agent.tokenBudget)
        assertEquals(listOf("web.search", "web.fetch"), agent.toolUids)
        assertEquals("Research", agent.workspaceFolder)
        assertTrue(agent.defaultGreeting.isNotBlank())
        assertTrue(agent.enabled)
        // Phase 3: no marker in the prompt — the sub sends its brief with the send_message tool.
        assertTrue(!agent.systemPrompt.contains("---BRIEF---"))
        assertTrue(agent.systemPrompt.contains("send_message"))
        assertEquals(HistoryVisibility.BRIEF_ONLY, agent.historyVisibility)
        assertNull(agent.model)
        assertNull(agent.modelProviderId)
    }

    @Test fun configSanitizingClampsCeilingsIntoRange() {
        val agent = defaultResearchAgent().copy(roundCap = 0, tokenBudget = 999_999)
        val clean = agent.sanitized()
        assertEquals(SubAgentConfig.MIN_ROUND_CAP, clean.roundCap)
        assertEquals(SubAgentConfig.MAX_TOKEN_BUDGET, clean.tokenBudget)
        assertEquals(SubAgentConfig.MAX_ROUND_CAP, defaultResearchAgent().copy(roundCap = 99).sanitized().roundCap)
        assertEquals(SubAgentConfig.MIN_TOKEN_BUDGET, defaultResearchAgent().copy(tokenBudget = 100).sanitized().tokenBudget)
    }

    @Test fun configValidationFlagsBlankFieldsAndHalfAModel() {
        val blank = defaultResearchAgent().copy(name = " ", workspaceFolder = "")
        assertTrue(blank.validate().size >= 2)
        val halfModel = defaultResearchAgent().copy(modelProviderId = "p1", model = null)
        assertEquals(1, halfModel.validate().size)
        assertTrue(defaultResearchAgent().validate().isEmpty())
        assertTrue(defaultResearchAgent().copy(modelProviderId = "p1", model = "m1").validate().isEmpty())
    }

    @Test fun teamLookupsResolveIdAndName() {
        val team = defaultTeam()
        assertEquals("Research", team.displayName("research"))
        assertEquals("Research", team.displayName("Research"))
        assertEquals("ghost", team.displayName("ghost"))
        assertEquals(listOf("research"), team.enabledAgents().map { it.id })
        assertTrue(team.copy(agents = listOf(defaultResearchAgent().copy(enabled = false))).enabledAgents().isEmpty())
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
}

/** Tests for SubAgentStore: seed on first launch, save/load, corrupt file. */
class SubAgentStoreTest {

    private fun tempFile(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "tov-agents-${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, "agents.json")
    }

    @Test fun firstLaunchSeedsTheFileWithResearch() {
        val file = tempFile()
        val store = SubAgentStore(file)
        assertEquals(listOf("research"), store.team.value.agents.map { it.id })
        assertTrue(file.exists())
        assertTrue(file.readText().contains("research"))
        assertNull(store.lastLoadError.value)
    }

    @Test fun saveAndReloadRoundTrips() {
        val file = tempFile()
        val store = SubAgentStore(file)
        val edited = defaultResearchAgent().copy(roundCap = 25, tokenBudget = 64_000, enabled = false)
        assertTrue(store.updateAgent(edited).isSuccess)
        val reloaded = SubAgentStore(file)
        assertEquals(25, reloaded.team.value.byId("research")?.roundCap)
        assertEquals(64_000, reloaded.team.value.byId("research")?.tokenBudget)
        assertEquals(false, reloaded.team.value.byId("research")?.enabled)
    }

    @Test fun corruptFileFallsBackToSeedAndLeavesTheFileUntouched() {
        val file = tempFile()
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json")
        val store = SubAgentStore(file)
        assertEquals(listOf("research"), store.team.value.agents.map { it.id })
        assertTrue(store.lastLoadError.value?.contains("agents.json") == true)
        assertEquals("{ this is not json", file.readText())
    }

    @Test fun updateAgentRejectsUnknownIdAndInvalidAgent() {
        val file = tempFile()
        val store = SubAgentStore(file)
        assertTrue(store.updateAgent(defaultResearchAgent().copy(id = "ghost")).isFailure)
        assertTrue(store.updateAgent(defaultResearchAgent().copy(name = " ")).isFailure)
    }

    @Test fun addAndRemoveAgent() {
        val file = tempFile()
        val store = SubAgentStore(file)
        val review = defaultResearchAgent().copy(id = "review", name = "Review", role = "Review agent")
        assertTrue(store.addAgent(review).isSuccess)
        assertTrue(store.addAgent(review).isFailure) // duplicate id
        assertEquals(listOf("research", "review"), store.team.value.agents.map { it.id })
        assertTrue(store.removeAgent("review").isSuccess)
        assertTrue(store.removeAgent("review").isFailure)
        assertEquals(listOf("research"), store.team.value.agents.map { it.id })
    }
}
