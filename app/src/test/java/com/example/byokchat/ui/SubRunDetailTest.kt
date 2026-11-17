package com.example.byokchat.ui

import com.example.byokchat.data.SubRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the run detail screen's mapping from a stored run record. */
class SubRunDetailTest {

    private fun storedRun() = SubRunEntity(
        conversationId = "conv-1", turnNo = 3, agent = "monica", agentName = "Monica",
        title = "Find cheerful morning music", brief = "Find cheerful morning music on YouTube and play it.",
        status = "done", rounds = 4, tokens = 4756,
        toolCallsJson = "[]", messagesToMainJson = "[]",
        fullReport = "Sub-agent Monica finished: done.\n\nSongs that'll make you dance.",
        reasoning = "Let me think about cheerful music.",
        startedAt = 1000L, finishedAt = 2000L,
    )

    @Test fun toDetailCarriesTheStoredRecord() {
        val detail = storedRun().toDetail()
        assertEquals("Find cheerful morning music", detail.title)
        assertEquals("Monica", detail.agentName)
        assertEquals(3, detail.turnNo)
        assertEquals("done", detail.status)
        assertEquals("Find cheerful morning music on YouTube and play it.", detail.brief)
        assertEquals(4, detail.rounds)
        assertEquals(4756, detail.tokens)
        assertEquals("Sub-agent Monica finished: done.\n\nSongs that'll make you dance.", detail.fullReport)
        assertEquals("Let me think about cheerful music.", detail.reasoning)
        assertNull(detail.error)
    }

    @Test fun toDetailKeepsTheErrorOfAFailedRun() {
        val detail = storedRun().copy(status = "failed", error = "No AI provider is selected.").toDetail()
        assertEquals("failed", detail.status)
        assertEquals("No AI provider is selected.", detail.error)
    }
}
