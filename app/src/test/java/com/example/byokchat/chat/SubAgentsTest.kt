package com.example.byokchat.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests for the Phase 3 team model (config, validation) and the file store. */
class SubAgentsTest {

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
        assertTrue(agent.enabled)
        // Phase 3: no marker in the prompt — the sub sends its brief with the send_message tool.
        assertTrue(!agent.systemPrompt.contains("---BRIEF---"))
        assertTrue(agent.systemPrompt.contains("send_message"))
        // The sub greets the user itself with send_message — no Main-scripted greeting.
        assertTrue(agent.systemPrompt.contains("greet the user briefly"))
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

    @Test fun activeSubRunCarriesLiveProgressDefaults() {
        val run = ActiveSubRun(agentId = "research", name = "Research", brief = "Find X", startedAt = 1000)
        assertEquals(0, run.rounds)
        assertEquals(10, run.roundCap)
        assertEquals(0, run.tokensUsed)
        assertEquals(32_000, run.tokenBudget)
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

    @Test fun untouchedSeedPromptIsRefreshedToTheNewSeedOnLoad() {
        val file = tempFile()
        file.parentFile?.mkdirs()
        file.writeText(oldTeamJson(LEGACY_RESEARCH_SYSTEM_PROMPT))
        val store = SubAgentStore(file)
        assertEquals(defaultResearchAgent().systemPrompt, store.team.value.byId("research")?.systemPrompt)
        // The file was rewritten: the new prompt is in, the old greeting field is gone.
        assertTrue(file.readText().contains("greet the user briefly"))
        assertTrue(!file.readText().contains("defaultGreeting"))
    }

    @Test fun editedSeedPromptIsLeftAloneOnLoad() {
        val file = tempFile()
        file.parentFile?.mkdirs()
        val edited = "You are Research. Be extra careful with sources."
        file.writeText(oldTeamJson(edited))
        val store = SubAgentStore(file)
        assertEquals(edited, store.team.value.byId("research")?.systemPrompt)
        assertTrue(!file.readText().contains("greet the user briefly"))
    }

    /** An agents.json from before the greeting change: old prompt + the removed defaultGreeting field. */
    private fun oldTeamJson(prompt: String): String {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
        val agent = JsonObject(
            mapOf(
                "id" to JsonPrimitive("research"),
                "name" to JsonPrimitive("Research"),
                "role" to JsonPrimitive("Research agent"),
                "description" to JsonPrimitive("Researches a topic on the web and reports back with sources."),
                "systemPrompt" to JsonPrimitive(prompt),
                "toolUids" to JsonArray(listOf(JsonPrimitive("web.search"), JsonPrimitive("web.fetch"))),
                "workspaceFolder" to JsonPrimitive("Research"),
                "defaultGreeting" to JsonPrimitive("Hi, I'm Research — I'll look into this right away."),
            ),
        )
        return json.encodeToString(JsonObject(mapOf("version" to JsonPrimitive(1), "agents" to JsonArray(listOf(agent)))))
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
