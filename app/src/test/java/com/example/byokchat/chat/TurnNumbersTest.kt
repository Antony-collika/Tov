package com.example.byokchat.chat

import com.example.byokchat.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Test cho Đợt 4: số lượt ổn định (turnNo), không đếm lại theo vị trí. */
class TurnNumbersTest {

    private fun msg(id: String, turnId: String, seq: Long, turnNo: Int) =
        MessageEntity(
            id = id, conversationId = "c1", turnId = turnId, turnNo = turnNo,
            seq = seq, role = "user", text = "x", createdAt = seq,
        )

    @Test
    fun storedNumbersAreKeptAndNextIsMaxPlusOne() {
        val rows = listOf(msg("m1", "t1", 1, 2), msg("m2", "t2", 2, 5))
        assertEquals(mapOf("t1" to 2, "t2" to 5), TurnNumbers.effectiveTurnNumbers(rows))
        assertEquals(6, TurnNumbers.nextTurnNo(rows))
    }

    @Test
    fun rowsWithoutNumberGetPositionalFallback() {
        val rows = listOf(msg("m1", "t1", 1, 0), msg("m2", "t2", 2, 0), msg("m3", "t3", 3, 5))
        assertEquals(mapOf("t1" to 1, "t2" to 2, "t3" to 5), TurnNumbers.effectiveTurnNumbers(rows))
        assertEquals(6, TurnNumbers.nextTurnNo(rows))
    }

    @Test
    fun fallbackSkipsNumbersAlreadyUsed() {
        val rows = listOf(msg("m1", "t1", 1, 0), msg("m2", "t2", 2, 1))
        assertEquals(mapOf("t1" to 2, "t2" to 1), TurnNumbers.effectiveTurnNumbers(rows))
        assertEquals(3, TurnNumbers.nextTurnNo(rows))
    }

    @Test
    fun emptyChatStartsAtOne() {
        assertEquals(emptyMap<String, Int>(), TurnNumbers.effectiveTurnNumbers(emptyList()))
        assertEquals(1, TurnNumbers.nextTurnNo(emptyList()))
    }
}
