package com.example.byokchat.chat

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A test agent built directly — there is no seed agent anymore. */
internal fun testAgent(
    id: String = "review",
    name: String = "Review",
    role: String = "Review agent",
    description: String = "Reviews a draft.",
    enabled: Boolean = true,
) = SubAgentConfig(
    id = id,
    name = name,
    role = role,
    description = description,
    systemPrompt = "You are Review. Check the draft Main gives you and report back.",
    toolUids = listOf("web.search", "web.fetch"),
    workspaceFolder = "Review",
    enabled = enabled,
)

/** Tests for the team model (config, validation) and the file store. No agent is shipped. */
class SubAgentsTest {

    @Test fun fallbackBriefPrefersTheLastMessageSentToTheUser() {
        assertEquals("Sent brief.", fallbackBrief("Long final answer.", listOf("First note.", "Sent brief.")))
        assertEquals("First paragraph here.", fallbackBrief("First paragraph here.\n\nSecond paragraph.", emptyList()))
        assertEquals("", fallbackBrief("", emptyList()))
        assertEquals("Only text.", fallbackBrief("Only text.", listOf("", "  ")))
    }

    @Test fun emptyTeamHasNoAgents() {
        val team = emptyTeam()
        assertTrue(team.agents.isEmpty())
        assertTrue(team.enabledAgents().isEmpty())
        assertNull(team.byId("research"))
        assertEquals("ghost", team.displayName("ghost"))
    }

    @Test fun configDefaultsAreTheDecidedCeilings() {
        val agent = testAgent()
        assertEquals(10, agent.roundCap)
        assertEquals(32_000, agent.tokenBudget)
        assertEquals(listOf("web.search", "web.fetch"), agent.toolUids)
        assertEquals("Review", agent.workspaceFolder)
        assertTrue(agent.enabled)
        assertEquals(HistoryVisibility.BRIEF_ONLY, agent.historyVisibility)
        assertNull(agent.model)
        assertNull(agent.modelProviderId)
    }

    @Test fun configSanitizingClampsCeilingsIntoRange() {
        val agent = testAgent().copy(roundCap = 0, tokenBudget = 999_999)
        val clean = agent.sanitized()
        assertEquals(SubAgentConfig.MIN_ROUND_CAP, clean.roundCap)
        assertEquals(SubAgentConfig.MAX_TOKEN_BUDGET, clean.tokenBudget)
        assertEquals(SubAgentConfig.MAX_ROUND_CAP, testAgent().copy(roundCap = 99).sanitized().roundCap)
        assertEquals(SubAgentConfig.MIN_TOKEN_BUDGET, testAgent().copy(tokenBudget = 100).sanitized().tokenBudget)
    }

    @Test fun configValidationFlagsBlankFieldsAndHalfAModel() {
        val blank = testAgent().copy(name = " ", workspaceFolder = "")
        assertTrue(blank.validate().size >= 2)
        val halfModel = testAgent().copy(modelProviderId = "p1", model = null)
        assertEquals(1, halfModel.validate().size)
        assertTrue(testAgent().validate().isEmpty())
        assertTrue(testAgent().copy(modelProviderId = "p1", model = "m1").validate().isEmpty())
    }

    @Test fun teamLookupsResolveIdAndName() {
        val team = SubAgentTeam(agents = listOf(testAgent()))
        assertEquals("Review", team.displayName("review"))
        assertEquals("Review", team.displayName("Review"))
        assertEquals("ghost", team.displayName("ghost"))
        assertEquals(listOf("review"), team.enabledAgents().map { it.id })
        assertTrue(team.copy(agents = listOf(testAgent().copy(enabled = false))).enabledAgents().isEmpty())
    }

    @Test fun activeSubRunCarriesLiveProgressDefaults() {
        val run = ActiveSubRun(agentId = "review", name = "Review", brief = "Check X", startedAt = 1000)
        assertEquals(0, run.rounds)
        assertEquals(10, run.roundCap)
        assertEquals(0, run.tokensUsed)
        assertEquals(32_000, run.tokenBudget)
    }

    @Test fun subRunRecordRoundTripsAsJson() {
        val json = Json { ignoreUnknownKeys = true }
        val record = SubRunRecord(
            agent = "review", agentName = "Review", brief = "Check X",
            status = "done", rounds = 2, tokens = 1234,
            toolCalls = listOf("web_search"), startedAt = 1000, finishedAt = 2000,
            resultSummary = "Found it.", error = null,
        )
        val decoded = json.decodeFromString(SubRunRecord.serializer(), encodeSubRunRecord(record))
        assertEquals(record, decoded)
    }
}

/** Tests for SubAgentStore: empty team on first launch, save/load, corrupt file, Research drop. */
class SubAgentStoreTest {

    private fun tempFile(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "tov-agents-${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, "agents.json")
    }

    @Test fun firstLaunchWritesAnEmptyTeam() {
        val file = tempFile()
        val store = SubAgentStore(file)
        assertTrue(store.team.value.agents.isEmpty())
        assertTrue(file.exists())
        assertFalse(file.readText().contains("research"))
        assertNull(store.lastLoadError.value)
    }

    @Test fun saveAndReloadRoundTrips() {
        val file = tempFile()
        val store = SubAgentStore(file)
        assertTrue(store.addAgent(testAgent()).isSuccess)
        val edited = testAgent().copy(roundCap = 25, tokenBudget = 64_000, enabled = false)
        assertTrue(store.updateAgent(edited).isSuccess)
        val reloaded = SubAgentStore(file)
        assertEquals(25, reloaded.team.value.byId("review")?.roundCap)
        assertEquals(64_000, reloaded.team.value.byId("review")?.tokenBudget)
        assertEquals(false, reloaded.team.value.byId("review")?.enabled)
    }

    @Test fun corruptFileFallsBackToEmptyTeamAndLeavesTheFileUntouched() {
        val file = tempFile()
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json")
        val store = SubAgentStore(file)
        assertTrue(store.team.value.agents.isEmpty())
        assertTrue(store.lastLoadError.value?.contains("agents.json") == true)
        assertEquals("{ this is not json", file.readText())
    }

    @Test fun storedResearchAgentIsDroppedOnLoadAndOtherAgentsAreKept() {
        val file = tempFile()
        file.parentFile?.mkdirs()
        file.writeText(teamJson(researchAgentJson(), reviewAgentJson()))
        val store = SubAgentStore(file)
        assertEquals(listOf("review"), store.team.value.agents.map { it.id })
        assertEquals("Reviews a draft.", store.team.value.byId("review")?.description)
        // The file was rewritten without Research.
        assertFalse(file.readText().contains("research"))
        assertTrue(file.readText().contains("review"))
    }

    @Test fun storedTeamOfOnlyResearchLoadsEmpty() {
        val file = tempFile()
        file.parentFile?.mkdirs()
        file.writeText(teamJson(researchAgentJson()))
        val store = SubAgentStore(file)
        assertTrue(store.team.value.agents.isEmpty())
        assertFalse(file.readText().contains("research"))
    }

    @Test fun updateAgentRejectsUnknownIdAndInvalidAgent() {
        val file = tempFile()
        val store = SubAgentStore(file)
        assertTrue(store.updateAgent(testAgent().copy(id = "ghost")).isFailure)
        assertTrue(store.updateAgent(testAgent().copy(name = " ")).isFailure)
    }

    @Test fun addAndRemoveAgent() {
        val file = tempFile()
        val store = SubAgentStore(file)
        assertTrue(store.addAgent(testAgent()).isSuccess)
        assertTrue(store.addAgent(testAgent()).isFailure) // duplicate id
        assertEquals(listOf("review"), store.team.value.agents.map { it.id })
        assertTrue(store.removeAgent("review").isSuccess)
        assertTrue(store.removeAgent("review").isFailure)
        assertTrue(store.team.value.agents.isEmpty())
    }

    /** An agents.json holding the given agents (raw JSON, e.g. from an older app version). */
    private fun teamJson(vararg agents: JsonObject): String {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
        return json.encodeToString(JsonObject(mapOf("version" to JsonPrimitive(1), "agents" to JsonArray(agents.toList()))))
    }

    /** The removed Phase 3 seed, as stored on device — including the old defaultGreeting field. */
    private fun researchAgentJson(): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive("research"),
            "name" to JsonPrimitive("Research"),
            "role" to JsonPrimitive("Research agent"),
            "description" to JsonPrimitive("Researches a topic on the web and reports back with sources."),
            "systemPrompt" to JsonPrimitive("You are Research. Research the web."),
            "toolUids" to JsonArray(listOf(JsonPrimitive("web.search"), JsonPrimitive("web.fetch"))),
            "workspaceFolder" to JsonPrimitive("Research"),
            "defaultGreeting" to JsonPrimitive("Hi, I'm Research — I'll look into this right away."),
        ),
    )

    private fun reviewAgentJson(): JsonObject = JsonObject(
        mapOf(
            "id" to JsonPrimitive("review"),
            "name" to JsonPrimitive("Review"),
            "role" to JsonPrimitive("Review agent"),
            "description" to JsonPrimitive("Reviews a draft."),
            "systemPrompt" to JsonPrimitive("You are Review. Check the draft Main gives you and report back."),
            "toolUids" to JsonArray(listOf(JsonPrimitive("web.search"), JsonPrimitive("web.fetch"))),
            "workspaceFolder" to JsonPrimitive("Review"),
        ),
    )
}
