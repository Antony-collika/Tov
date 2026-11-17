package com.example.byokchat.ui

import com.example.byokchat.chat.SubAgentConfig
import com.example.byokchat.chat.SubAgentStore
import com.example.byokchat.chat.SubAgentTeam
import com.example.byokchat.data.MessageEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChatTest {

    /** Name-resolution tests need a known agent in the store snapshot; there is no seed anymore. */
    private fun installReviewAgent() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tov-agents-${System.nanoTime()}")
        dir.mkdirs()
        val agent = SubAgentConfig(
            id = "review", name = "Review", role = "Review agent",
            description = "Reviews a draft.",
            systemPrompt = "You are Review. Check the draft Main gives you and report back.",
            toolUids = listOf("web.search", "web.fetch"),
            workspaceFolder = "Review",
        )
        val store = SubAgentStore(File(dir, "agents.json"))
        assertTrue(store.save(SubAgentTeam(agents = listOf(agent))).isSuccess)
    }

    private fun member(id: String, status: MemberStatus) =
        GroupMember(id = id, name = id, role = "agent", status = status)

    private fun msg(id: String, turnId: String, seq: Long, role: String, sender: String? = null) =
        MessageEntity(
            id = id, conversationId = "c1", turnId = turnId,
            seq = seq, role = role, text = "x", sender = sender, createdAt = seq,
        )

    @Test fun headerOnlyOnFirstAssistantMessageOfEachTurn() {
        val rows = listOf(
            msg("u1", "t1", 1, "user"),
            msg("a1", "t1", 2, "assistant"),
            msg("a2", "t1", 3, "assistant"),
            msg("a3", "t1", 4, "assistant"),
            msg("u2", "t2", 5, "user"),
            msg("a4", "t2", 6, "assistant"),
            msg("a5", "t2", 7, "assistant"),
        )
        assertEquals(setOf("a1", "a4"), mainHeaderMessageIds(rows))
    }

    @Test fun headerLogicIgnoresUserMessagesAndEmptyList() {
        val rows = listOf(
            msg("u1", "t1", 1, "user"),
            msg("u2", "t2", 2, "user"),
        )
        assertEquals(emptySet<String>(), mainHeaderMessageIds(rows))
        assertEquals(emptySet<String>(), mainHeaderMessageIds(emptyList()))
    }

    @Test fun defaultMembersAreYouAndMain() {
        val members = defaultMembers()
        assertEquals(listOf("you", "main"), members.map { it.id })
        assertEquals(true, members[0].isUser)
        assertEquals(MemberStatus.IDLE, members[1].status)
    }

    @Test fun sessionTokenPercentIsZeroWithoutBudget() {
        assertEquals(0, sessionTokenPercent(0, 0))
        assertEquals(0, sessionTokenPercent(100, 0))
        assertEquals(38, sessionTokenPercent(380, 1000))
        assertEquals(100, sessionTokenPercent(9999, 100))
        assertEquals(0, sessionTokenPercent(0, 32000))
    }

    @Test fun formatElapsedCountsMinutesAndHours() {
        assertEquals("00:00", formatElapsed(0))
        assertEquals("00:05", formatElapsed(5_000))
        assertEquals("09:12", formatElapsed(552_000))
        assertEquals("1:02:03", formatElapsed(3_723_000))
        assertEquals("00:00", formatElapsed(-1000))
    }

    @Test fun buildSessionInfoAddsFinishedAndLiveTokens() {
        val info = buildSessionInfo(
            conversationId = "abcdef12-3456", startedAt = 1000, peakMembers = 3,
            messageCount = 19, subMessageCount = 4,
            finishedSubTokens = 300, liveSubTokens = 80,
            teamTokenBudget = 32000, journalPath = "sessions/x/sub_runs.meta.json",
        )
        assertEquals("abcdef12", info.sessionShortId)
        assertEquals(3, info.peakMembers)
        assertEquals(380, info.sessionTokensUsed)
        assertEquals(32000, info.sessionTokenBudget)
        assertEquals(2, info.membersAtStart)
    }

    @Test fun groupsAreOrderedByUrgencyAndEmptyGroupsAreSkipped() {
        val members = listOf(
            member("a", MemberStatus.IDLE),
            member("b", MemberStatus.WORKING),
            member("c", MemberStatus.NEEDS_INPUT),
            member("d", MemberStatus.DONE),
        )
        val groups = groupMembers(members)
        assertEquals(
            listOf("Needs you", "Working", "Done", "Available"),
            groups.map { it.title },
        )
        assertEquals(listOf("c"), groups[0].members.map { it.id })
        assertEquals(listOf("b"), groups[1].members.map { it.id })
    }

    @Test fun waitingComesAfterWorkingAndBeforeDone() {
        val members = listOf(
            member("a", MemberStatus.DONE),
            member("b", MemberStatus.WAITING),
            member("c", MemberStatus.WORKING),
        )
        assertEquals(
            listOf("Working", "Waiting", "Done"),
            groupMembers(members).map { it.title },
        )
    }

    @Test fun statusLabelsArePlainWords() {
        assertEquals("Working", memberStatusLabel(MemberStatus.WORKING))
        assertEquals("Available", memberStatusLabel(MemberStatus.IDLE))
        assertEquals("Waiting", memberStatusLabel(MemberStatus.WAITING))
        assertEquals("Done", memberStatusLabel(MemberStatus.DONE))
        assertEquals("Needs you", memberStatusLabel(MemberStatus.NEEDS_INPUT))
    }

    // Speaker-change rule (17/11/2026): this used to expect only "a1" —
    // one header per turn. Now Main's answer after the sub's message is
    // the handover point, so it carries the header again ("a2"); the sub's
    // own message still never carries Main's header.
    @Test fun headerReturnsAfterSubAgentMessage() {
        val rows = listOf(
            msg("u1", "t1", 1, "user"),
            msg("a1", "t1", 2, "assistant"),
            msg("s1", "t1", 3, "assistant", sender = "research"),
            msg("a2", "t1", 4, "assistant"),
        )
        assertEquals(setOf("a1", "a2"), mainHeaderMessageIds(rows))
    }

    @Test fun headerDoesNotRepeatForMainMessagesAfterHandover() {
        val rows = listOf(
            msg("u1", "t1", 1, "user"),
            msg("a1", "t1", 2, "assistant"),
            msg("s1", "t1", 3, "assistant", sender = "research"),
            msg("a2", "t1", 4, "assistant"),
            msg("a3", "t1", 5, "assistant"),
        )
        assertEquals(setOf("a1", "a2"), mainHeaderMessageIds(rows))
    }

    @Test fun headerOnMainMessageAfterLeadingSubMessage() {
        val rows = listOf(
            msg("u1", "t1", 1, "user"),
            msg("s1", "t1", 2, "assistant", sender = "research"),
            msg("a1", "t1", 3, "assistant"),
        )
        assertEquals(setOf("a1"), mainHeaderMessageIds(rows))
    }

    @Test fun subAgentMemberUsesConfigForKnownAgent() {
        installReviewAgent()
        val m = subAgentMember("review")
        assertEquals("review", m.id)
        assertEquals("Review", m.name)
    }

    @Test fun subAgentMemberFallsBackToRawSenderForUnknownAgent() {
        val m = subAgentMember("ghost")
        assertEquals("ghost", m.name)
    }
}
