package com.example.byokchat.chat

import com.example.byokchat.data.ContextLimits
import com.example.byokchat.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Số thật đã dùng trong hộp «Context window» (20/11/2026): tổng input +
 * output server đếm ở lượt Main gần nhất, điều chỉnh cho các lượt mới hơn
 * và các lượt bị cắt. Reasoning KHÔNG cộng thêm — nó đã nằm trong output.
 */
class ContextUsageTest {

    private fun row(
        id: String = "m1",
        turnId: String = "t1",
        turnNo: Int = 1,
        role: String = "user",
        text: String = "hello",
    ) = MessageEntity(
        id = id,
        conversationId = "c1",
        turnId = turnId,
        turnNo = turnNo,
        seq = 1,
        role = role,
        text = text,
        createdAt = 0,
    )

    @Test
    fun `no newer or dropped turns returns last total unchanged`() {
        // Tin assistant của chính lượt đã đếm (cùng turnNo) không cộng thêm:
        // nó là output, đã nằm trong tổng rồi.
        val rows = listOf(
            row(id = "u1", turnNo = 1, role = "user", text = "hello"),
            row(id = "a1", turnNo = 1, role = "assistant", text = "hello world, this is a long reply"),
        )
        assertEquals(1000, realUsedTokens(1000, 1, rows, emptySet()))
    }

    @Test
    fun `newer turns are added by estimate`() {
        // "hello" = ceil(5 * 0.28) + 6 = 8; "hello world" = ceil(11 * 0.28) + 6 = 10.
        val rows = listOf(
            row(id = "u2", turnId = "t2", turnNo = 2, text = "hello"),
            row(id = "a2", turnId = "t2", turnNo = 2, role = "assistant", text = "hello world"),
        )
        assertEquals(1018, realUsedTokens(1000, 1, rows, emptySet()))
    }

    @Test
    fun `dropped turns are subtracted`() {
        val rows = listOf(row(id = "u1", turnId = "t1", turnNo = 1, text = "hello"))
        assertEquals(992, realUsedTokens(1000, 2, rows, setOf("t1")))
    }

    @Test
    fun `result never goes below zero`() {
        val rows = listOf(row(id = "u1", turnId = "t1", turnNo = 1, text = "hello"))
        assertEquals(0, realUsedTokens(5, 2, rows, setOf("t1")))
    }

    @Test
    fun `default global limit is one of the slider stops`() {
        assertEquals(100_000, ContextLimits.DEFAULT_GLOBAL)
        assertEquals(ContextLimits.DEFAULT_GLOBAL, ContextLimits.snapToStop(100_000))
        assertEquals(100_000, ContextLimits.snapToStop(90_000))
        assertEquals(128_000, ContextLimits.snapToStop(150_000))
    }
}
