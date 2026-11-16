package com.example.byokchat.data

import com.example.byokchat.chat.ChatEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test cho Max tool rounds của Main (16/11/2026): trước đây fix cứng 12
 * ở ChatEngine, giờ chỉnh được ở Manage tools (1–50, mặc định 12).
 * SettingsRepository cần Android Context nên không khởi tạo được trong
 * unit test thuần (không có Robolectric) — test ở đây bao phủ logic thuần
 * (mặc định + kẹp) mà setter dùng, và mặc định khớp với hằng số của engine.
 */
class MaxToolRoundsTest {

    @Test
    fun `default is 12`() {
        assertEquals(12, MAX_TOOL_ROUNDS_DEFAULT)
    }

    @Test
    fun `default matches the engine constant`() {
        assertEquals(ChatEngine.MAX_TOOL_ROUNDS, MAX_TOOL_ROUNDS_DEFAULT)
    }

    @Test
    fun `clamp keeps in-range values`() {
        assertEquals(1, clampMaxToolRounds(1))
        assertEquals(12, clampMaxToolRounds(12))
        assertEquals(50, clampMaxToolRounds(50))
    }

    @Test
    fun `clamp floors below 1`() {
        assertEquals(1, clampMaxToolRounds(0))
        assertEquals(1, clampMaxToolRounds(-5))
    }

    @Test
    fun `clamp caps above 50`() {
        assertEquals(50, clampMaxToolRounds(51))
        assertEquals(50, clampMaxToolRounds(999))
    }
}
