package com.example.byokchat.chat

import com.example.byokchat.data.clampMaxConsecutiveToolErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the consecutive-error block (24/11/2026). */
class ToolErrorBlockerTest {

    @Test fun twoErrorsDoNotDisableThirdOneDoes() {
        val blocker = ToolErrorBlocker(3)
        blocker.record("web.fetch", true)
        blocker.record("web.fetch", true)
        assertFalse(blocker.isDisabled("web.fetch"))
        assertNull(blocker.refusalFor("web.fetch", "fetch_url"))
        blocker.record("web.fetch", true)
        assertTrue(blocker.isDisabled("web.fetch"))
        val refusal = blocker.refusalFor("web.fetch", "fetch_url")
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("fetch_url"))
        assertTrue(refusal.contains("3 times in a row"))
        assertTrue(refusal.contains("rest of this run"))
    }

    @Test fun successResetsTheCount() {
        val blocker = ToolErrorBlocker(3)
        blocker.record("web.fetch", true)
        blocker.record("web.fetch", true)
        blocker.record("web.fetch", false)
        blocker.record("web.fetch", true)
        blocker.record("web.fetch", true)
        assertFalse(blocker.isDisabled("web.fetch"))
        blocker.record("web.fetch", true)
        assertTrue(blocker.isDisabled("web.fetch"))
    }

    @Test fun otherToolsAreIndependent() {
        val blocker = ToolErrorBlocker(3)
        blocker.record("web.fetch", true)
        blocker.record("web.fetch", true)
        blocker.record("web.fetch", true)
        assertTrue(blocker.isDisabled("web.fetch"))
        assertFalse(blocker.isDisabled("web.search"))
        assertNull(blocker.refusalFor("web.search", "web_search"))
        blocker.record("web.search", false)
        assertFalse(blocker.isDisabled("web.search"))
    }

    @Test fun aNewBlockerStartsCountingAgain() {
        // A new run creates a fresh blocker: the same tool may fail
        // again from zero, exactly as Main re-delegating the sub does.
        val firstRun = ToolErrorBlocker(3)
        repeat(3) { firstRun.record("web.fetch", true) }
        assertTrue(firstRun.isDisabled("web.fetch"))
        val secondRun = ToolErrorBlocker(3)
        assertFalse(secondRun.isDisabled("web.fetch"))
        assertNull(secondRun.refusalFor("web.fetch", "fetch_url"))
    }

    @Test fun limitOneDisablesAfterTheFirstError() {
        val blocker = ToolErrorBlocker(1)
        blocker.record("web.fetch", true)
        assertTrue(blocker.isDisabled("web.fetch"))
    }

    @Test fun clampKeepsTheSettingInRange() {
        assertEquals(3, clampMaxConsecutiveToolErrors(3))
        assertEquals(1, clampMaxConsecutiveToolErrors(0))
        assertEquals(1, clampMaxConsecutiveToolErrors(-5))
        assertEquals(10, clampMaxConsecutiveToolErrors(99))
    }
}
