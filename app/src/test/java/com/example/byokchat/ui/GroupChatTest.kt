package com.example.byokchat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupChatTest {

    private fun member(id: String, status: MemberStatus) =
        GroupMember(id = id, name = id, role = "agent", status = status)

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
