package com.example.byokchat.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Allow all in this turn": covers later and already-waiting requests of one turn, never deletes, never other turns. */
class ConfirmationBrokerTest {
    private fun req(risk: Risk, turn: String = "t1") = ConfirmRequest("tool", "x.y", risk, "detail", turn)

    @Test fun allowAllCoversLaterRequestsOfTheSameTurn() = runBlocking {
        val b = ConfirmationBroker()
        val first = req(Risk.WRITE)
        val answer = async { b.ask(first) }
        yield()
        b.allowAllInTurn(first)
        assertTrue(answer.await())
        // a later write request in the same turn is allowed without asking
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
        b.allowAllInTurn(first)
        assertTrue(a1.await())
        assertTrue(a2.await())
    }

    @Test fun deleteIsNeverCoveredAndAlwaysAsked() = runBlocking {
        val b = ConfirmationBroker()
        val first = req(Risk.WRITE)
        val a1 = async { b.ask(first) }
        yield()
        b.allowAllInTurn(first)
        a1.await()

        val del = req(Risk.DELETE)
        val a2 = async { b.ask(del) }
        yield()
        assertFalse(a2.isCompleted)
        assertEquals(1, b.pending.value.size)
        b.resolve(del, false)
        assertFalse(a2.await())
    }

    @Test fun otherTurnsAreNotCovered() = runBlocking {
        val b = ConfirmationBroker()
        val first = req(Risk.WRITE, "t1")
        val a1 = async { b.ask(first) }
        yield()
        b.allowAllInTurn(first)
        a1.await()

        val other = req(Risk.WRITE, "t2")
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
        b.allowAllInTurn(first)
        a1.await()
        b.endTurn("t1")

        val again = req(Risk.WRITE)
        val a2 = async { b.ask(again) }
        yield()
        assertFalse(a2.isCompleted)
        b.resolve(again, false)
        assertFalse(a2.await())
    }
}
