package com.example.byokchat.ui

import com.example.byokchat.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupChatTest {

    private fun member(id: String, status: MemberStatus) =
        GroupMember(id = id, name = id, role = "agent", status = status)

    private fun msg(id: String, turnId: String, seq: Long, role: String) =
        MessageEntity(
            id = id, conversationId = "c1", turnId = turnId,
            seq = seq, role = role, text = "x", createdAt = seq,
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
        assertEquals(setOf("a1", "a4"), firstAssistantMessageIds(rows))
    }

    @Test fun headerLogicIgnoresUserMessagesAndEmptyList() {
        val rows = listOf(
            msg("u1", "t1", 1, "user"),
            msg("u2", "t2", 2, "user"),
        )
        assertEquals(emptySet<String>(), firstAssistantMessageIds(rows))
        assertEquals(emptySet<String>(), firstAssistantMessageIds(emptyList()))
    }

    @Test fun defaultMembersAreYouAndMain() {
        val members = defaultMembers()
        assertEquals(listOf("you", "main"), members.map { it.id })
        assertEquals(true, members[0].isUser)
        assertEquals(MemberStatus.IDLE, members[1].status)
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
}
