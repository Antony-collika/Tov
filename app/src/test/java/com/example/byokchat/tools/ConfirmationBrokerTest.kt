package com.example.byokchat.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Allow all in this round": covers later and already-waiting requests of one agent in one run, never deletes, never other agents or runs. */
class ConfirmationBrokerTest {
    private fun req(
        risk: Risk,
        turn: String = "t1",
        agent: String = "Main",
        round: String = "main:t1",
    ) = ConfirmRequest("tool", "x.y", risk, "detail", turn, agentName = agent, roundId = round)

    @Test fun allowAllCoversLaterRequestsOfTheSameRound() = runBlocking {
        val b = ConfirmationBroker()
        val first = req(Risk.WRITE)
        val answer = async { b.ask(first) }
        yield()
        b.allowAllInRound(first)
        assertTrue(answer.await())
        // a later write request in the same round is allowed without asking
        assertTrue(b.ask(req(Risk.WRITE)))
        assertTrue(b.pending.value.isEmpty())
    }

    @Test fun allowAllAlsoAnswersRequestsAlreadyWaiting() = runBlocking {
        val b = ConfirmationBroker()
        val first = req(Risk.WRITE)
        val second = req(Risk.WRITE)
        val a1 = async { b.ask(first) }
        val a2 = async { b.ask(second) }
        yield()
        assertEquals(2, b.pending.value.size)
        b.allowAllInRound(first)
        assertTrue(a1.await())
        assertTrue(a2.await())
    }

    @Test fun deleteIsNeverCoveredAndAlwaysAsked() = runBlocking {
        val b = ConfirmationBroker()
        val first = req(Risk.WRITE)
        val a1 = async { b.ask(first) }
        yield()
        b.allowAllInRound(first)
        a1.await()

        val del = req(Risk.DELETE)
        val a2 = async { b.ask(del) }
        yield()
        assertFalse(a2.isCompleted)
        assertEquals(1, b.pending.value.size)
        b.resolve(del, false)
        assertFalse(a2.await())
    }

    @Test fun otherRoundsAreNotCovered() = runBlocking {
        // Main calling Bob twice in one turn is two rounds: Allow-all in
        // the first run must not cover the second run, which may ask for
        // very different permissions.
        val b = ConfirmationBroker()
        val first = req(Risk.WRITE, agent = "Bob", round = "sub:bob:111")
        val a1 = async { b.ask(first) }
        yield()
        b.allowAllInRound(first)
        a1.await()

        val other = req(Risk.WRITE, agent = "Bob", round = "sub:bob:222")
        val a2 = async { b.ask(other) }
        yield()
        assertFalse(a2.isCompleted)
        b.resolve(other, true)
        assertTrue(a2.await())
    }

    @Test fun otherAgentsAreNotCovered() = runBlocking {
        val b = ConfirmationBroker()
        val first = req(Risk.WRITE, agent = "Bob", round = "sub:bob:111")
        val a1 = async { b.ask(first) }
        yield()
        b.allowAllInRound(first)
        a1.await()

        val other = req(Risk.WRITE, agent = "Danny", round = "sub:danny:111")
        val a2 = async { b.ask(other) }
        yield()
        assertFalse(a2.isCompleted)
        b.resolve(other, true)
        assertTrue(a2.await())
    }

    @Test fun endTurnForgetsThePermission() = runBlocking {
        val b = ConfirmationBroker()
        val first = req(Risk.WRITE)
        val a1 = async { b.ask(first) }
        yield()
        b.allowAllInRound(first)
        a1.await()
        b.endTurn("t1")

        val again = req(Risk.WRITE)
        val a2 = async { b.ask(again) }
        yield()
        assertFalse(a2.isCompleted)
        b.resolve(again, false)
        assertFalse(a2.await())
    }

    @Test fun endRunForgetsThePermission() = runBlocking {
        val b = ConfirmationBroker()
        val first = req(Risk.WRITE, agent = "Bob", round = "sub:bob:111")
        val a1 = async { b.ask(first) }
        yield()
        b.allowAllInRound(first)
        a1.await()
        b.endRun("sub:bob:111")

        val again = req(Risk.WRITE, agent = "Bob", round = "sub:bob:111")
        val a2 = async { b.ask(again) }
        yield()
        assertFalse(a2.isCompleted)
        b.resolve(again, false)
        assertFalse(a2.await())
    }

    @Test fun requestNamesTheAskingAgent() {
        assertEquals("Main", req(Risk.WRITE).agentName)
        assertEquals("Bob", req(Risk.WRITE, agent = "Bob").agentName)
    }
}
