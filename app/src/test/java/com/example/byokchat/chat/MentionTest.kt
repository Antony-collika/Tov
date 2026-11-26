package com.example.byokchat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for @mention parsing, routing and the picker helpers (GĐ5, 26/11/2026). */
class MentionTest {

    private val bob = testAgent(id = "bob", name = "Bob")
    private val danny = testAgent(id = "danny", name = "Danny")
    private val bobSmith = testAgent(id = "bob-smith", name = "Bob Smith")
    private val bobby = testAgent(id = "bob-1", name = "Bobby")
    private val team = SubAgentTeam(agents = listOf(bob, danny, bobSmith, bobby))

    @Test fun parseMentionLeadingName() {
        val target = parseMention("@Bob find the latest news", team)
        assertEquals(bob, target?.agent)
        assertEquals("find the latest news", target?.brief)
    }

    @Test fun parseMentionCaseInsensitive() {
        val target = parseMention("@bOb find the latest news", team)
        assertEquals(bob, target?.agent)
        assertEquals("find the latest news", target?.brief)
    }

    @Test fun parseMentionById() {
        val target = parseMention("@bob-1 find the latest news", team)
        assertEquals(bobby, target?.agent)
        assertEquals("find the latest news", target?.brief)
    }

    @Test fun parseMentionUnknownNameIsNoRoute() {
        assertNull(parseMention("@Nobody find the latest news", team))
    }

    @Test fun parseMentionNameWithoutBriefIsNoRoute() {
        assertNull(parseMention("@Bob", team))
        assertNull(parseMention("@Bob   ", team))
        assertNull(parseMention("@", team))
    }

    @Test fun parseMentionNotAtTheStartIsNoRoute() {
        assertNull(parseMention("Hello @Bob find the latest news", team))
    }

    @Test fun parseMentionNameMustEndAtAWordBoundary() {
        // "@BobSmith" is one word, not "@Bob" — it flows to Main.
        assertNull(parseMention("@BobSmith find the latest news", team))
    }

    @Test fun parseMentionLongestNameWins() {
        val target = parseMention("@Bob Smith find the latest news", team)
        assertEquals(bobSmith, target?.agent)
        assertEquals("find the latest news", target?.brief)
    }

    @Test fun parseMentionExtraMentionsArePlainText() {
        val target = parseMention("@Bob @Danny find the latest news", team)
        assertEquals(bob, target?.agent)
        assertEquals("@Danny find the latest news", target?.brief)
    }

    @Test fun parseMentionTrimsSurroundingSpaces() {
        val target = parseMention("  @Bob find the latest news  ", team)
        assertEquals(bob, target?.agent)
        assertEquals("find the latest news", target?.brief)
    }

    @Test fun decideMentionRoutesKnownEnabledIdleAgent() {
        val decision = decideMention("@Bob find news", team) { false }
        assertEquals(MentionDecision.Route(bob, "find news"), decision)
    }

    @Test fun decideMentionOffAgent() {
        val offTeam = SubAgentTeam(agents = listOf(testAgent(id = "bob", name = "Bob", enabled = false)))
        assertEquals(MentionDecision.AgentOff(offTeam.agents[0]), decideMention("@Bob find news", offTeam) { false })
    }

    @Test fun decideMentionBusyAgent() {
        assertEquals(
            MentionDecision.AgentBusy(bob),
            decideMention("@Bob find news", team) { agentId -> agentId == "bob" },
        )
    }

    @Test fun decideMentionUnknownIsNoMention() {
        assertEquals(MentionDecision.NoMention, decideMention("@Nobody find news", team) { false })
        assertEquals(MentionDecision.NoMention, decideMention("just a message", team) { false })
    }

    @Test fun mentionTitleFirstLineCappedAt40() {
        assertEquals("find the latest news", mentionTitle("find the latest news\nsecond line", bob))
        assertEquals("find news", mentionTitle("   \nfind news", bob))
        val long = "a".repeat(50)
        assertEquals("a".repeat(40), mentionTitle(long, bob))
    }

    @Test fun mentionQueryTrailingAtWord() {
        assertEquals("", mentionQuery("@"))
        assertEquals("Bo", mentionQuery("@Bo"))
        assertEquals("Bo", mentionQuery("hi @Bo"))
        assertNull(mentionQuery("hi Bob"))
        assertNull(mentionQuery("@Bo hello"))
        assertNull(mentionQuery("hi @Bo\nx"))
        assertNull(mentionQuery(""))
    }

    @Test fun filterMentionAgentsByPrefix() {
        assertEquals(listOf(bob, danny, bobSmith, bobby), filterMentionAgents("", team.agents))
        assertEquals(listOf(bob, bobSmith, bobby), filterMentionAgents("bo", team.agents))
        assertEquals(listOf(danny), filterMentionAgents("D", team.agents))
        assertEquals(listOf(bobby), filterMentionAgents("bob-1", team.agents))
        assertTrue(filterMentionAgents("z", team.agents).isEmpty())
    }

    @Test fun insertMentionReplacesTrailingAtWord() {
        assertEquals("@Bob ", insertMention("@Bo", "Bob"))
        assertEquals("hi @Danny ", insertMention("hi @Da", "Danny"))
        assertEquals("hello", insertMention("hello", "Bob"))
    }
}
