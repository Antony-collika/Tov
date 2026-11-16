package com.example.byokchat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the agent config markdown file (parse, serialize, sync check). */
class AgentConfigMdTest {

    @Test fun roundTripKeepsEveryField() {
        val agent = testAgent().copy(
            systemPrompt = "You are Review.\n\nCheck the draft Main gives you and report back.",
            modelProviderId = "gemini",
            model = "gemini-3.5-flash-lite",
            historyVisibility = HistoryVisibility.SEES_HISTORY,
            enabled = false,
        )
        val parsed = AgentConfigMd.parse(AgentConfigMd.serialize(agent))
        assertEquals(agent.sanitized(), parsed.mergeInto(testAgent()).sanitized())
    }

    @Test fun partialFileOnlyFillsTheFieldsItHas() {
        val parsed = AgentConfigMd.parse(
            """
            ## Name
            My Reviewer

            ## Tools
            web.search
            """.trimIndent(),
        )
        assertEquals("My Reviewer", parsed.name)
        assertEquals(listOf("web.search"), parsed.toolUids)
        assertNull(parsed.role)
        assertNull(parsed.systemPrompt)
        assertNull(parsed.modelProviderId)
        assertNull(parsed.roundCap)
        // Missing fields leave the base untouched on merge.
        val merged = parsed.mergeInto(testAgent())
        assertEquals("My Reviewer", merged.name)
        assertEquals(testAgent().role, merged.role)
        assertEquals(listOf("web.search"), merged.toolUids)
    }

    @Test fun notesAndUnknownSectionsAreIgnored() {
        val parsed = AgentConfigMd.parse(
            """
            > A note before the first heading.
            ## Name
            My Reviewer
            ## Notes for later
            Remember to add the fetch tool.
            ## Tools
            > (no tools)
            """.trimIndent(),
        )
        assertEquals("My Reviewer", parsed.name)
        assertEquals(emptyList<String>(), parsed.toolUids)
    }

    @Test fun modelNeedsBothHalves() {
        val onlyProvider = AgentConfigMd.parse("## Model\nprovider: gemini\n")
        assertEquals("gemini", onlyProvider.modelProviderId)
        assertNull(onlyProvider.model)
        // A lone provider does not change the base's model on merge.
        val merged = onlyProvider.mergeInto(testAgent().copy(modelProviderId = "openai", model = "gpt-5"))
        assertEquals("openai", merged.modelProviderId)
        assertEquals("gpt-5", merged.model)

        val both = AgentConfigMd.parse("## Model\nprovider: gemini\nmodel: gemini-3.5-flash-lite\n")
        val mergedBoth = both.mergeInto(testAgent())
        assertEquals("gemini", mergedBoth.modelProviderId)
        assertEquals("gemini-3.5-flash-lite", mergedBoth.model)
    }

    @Test fun historyAndEnabledAcceptOnlyKnownWords() {
        assertEquals(HistoryVisibility.SEES_HISTORY, AgentConfigMd.parse("## History\nhistory\n").historyVisibility)
        assertEquals(HistoryVisibility.BRIEF_ONLY, AgentConfigMd.parse("## History\nbrief\n").historyVisibility)
        assertNull(AgentConfigMd.parse("## History\nsometimes\n").historyVisibility)
        assertEquals(true, AgentConfigMd.parse("## Enabled\non\n").enabled)
        assertEquals(false, AgentConfigMd.parse("## Enabled\noff\n").enabled)
        assertNull(AgentConfigMd.parse("## Enabled\nmaybe\n").enabled)
    }

    @Test fun invalidNumbersAreIgnored() {
        val parsed = AgentConfigMd.parse("## Max rounds\nten\n\n## Token budget\n\n")
        assertNull(parsed.roundCap)
        assertNull(parsed.tokenBudget)
    }

    @Test fun differsFromIgnoresToolOrderButCatchesRealChanges() {
        val base = testAgent().copy(toolUids = listOf("web.search", "web.fetch"))
        assertFalse(AgentConfigMd.parse("## Tools\nweb.fetch\nweb.search\n").differsFrom(base))
        assertTrue(AgentConfigMd.parse("## Tools\nweb.search\n").differsFrom(base))
        assertFalse(AgentConfigMd.parse("## Name\nReview\n").differsFrom(base))
        assertTrue(AgentConfigMd.parse("## Name\nOther\n").differsFrom(base))
        assertFalse(AgentConfigMd.parse("").differsFrom(base))
    }

    @Test fun multiLinePromptKeepsItsParagraphBreak() {
        val parsed = AgentConfigMd.parse(
            """
            ## System prompt
            First paragraph.

            Second paragraph.
            """.trimIndent(),
        )
        assertEquals("First paragraph.\n\nSecond paragraph.", parsed.systemPrompt)
    }

    @Test fun agentTemplateParsesToAValidConfig() {
        val parsed = AgentConfigMd.parse(AgentConfigMd.agentTemplate())
        val config = parsed.mergeInto(testAgent())
        assertTrue(config.validate().isEmpty())
        assertEquals("My agent", config.name)
        assertTrue(config.toolUids.isNotEmpty())
    }
}
