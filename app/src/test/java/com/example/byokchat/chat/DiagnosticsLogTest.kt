package com.example.byokchat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tests for the diagnostic log (16/11/2026): lines, handoff format, rolling cap. */
class DiagnosticsLogTest {

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "tov-diag-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test fun logWritesTimestampedTaggedLines() {
        val log = DiagnosticsLog(tempDir())
        log.log("handoff/test", "hello")
        val text = log.readText()
        assertTrue(text.contains("[handoff/test] hello"))
        assertTrue(text.matches(Regex("(?s)\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[handoff/test\\] hello\\n")))
    }

    @Test fun handoffLineCarriesConversationTurnAndDetail() {
        val log = DiagnosticsLog(tempDir())
        log.handoff("engine.tool-result.upsert.end", "ba949149-xxxx", "turn-1", "tool=delegate_to_subagent took=12ms")
        val text = log.readText()
        assertTrue(text.contains("[handoff/engine.tool-result.upsert.end]"))
        assertTrue(text.contains("conv=ba949149"))
        assertTrue(text.contains("turn=turn-1"))
        assertTrue(text.contains("tool=delegate_to_subagent took=12ms"))
    }

    @Test fun readTextIsEmptyBeforeAnyLine() {
        val log = DiagnosticsLog(tempDir())
        assertEquals("", log.readText())
    }

    @Test fun rollingCapTrimsTheOldestLines() {
        val log = DiagnosticsLog(tempDir(), maxBytes = 400)
        repeat(30) { log.log("handoff/test", "line $it padding padding padding padding") }
        val file = log.file()
        assertTrue(file.exists())
        assertTrue(file.length() <= 400 + 200) // a trim leaves the file near the cap, not at it
        val text = file.readText()
        assertTrue(text.contains("[trimmed"))
        assertTrue(text.contains("line 29")) // newest lines survive
        assertTrue(!text.contains("line 0")) // oldest lines are gone
    }

    @Test fun logNeverThrowsOnAnUnwritableDir() {
        // A file where the dir should be: every write fails silently.
        val blocker = File(System.getProperty("java.io.tmpdir"), "tov-diag-block-${System.nanoTime()}")
        blocker.writeText("not a dir")
        val log = DiagnosticsLog(blocker)
        log.log("handoff/test", "must not throw")
        assertEquals("", log.readText())
    }
}

/** Pure-logic checks for the anti-hang fallback (the withTimeout path itself needs Room, so it is verified on device). */
class ChatEngineHandoffTest {

    @Test fun toolResultUpsetTimeoutIsThirtySeconds() {
        assertEquals(30_000L, ChatEngine.TOOL_RESULT_UPSERT_TIMEOUT_MS)
    }

    @Test fun fallbackResultTextTellsMainTheReportIsMissing() {
        val text = ModelFacingText.subResultNotRecorded("delegate_to_subagent")
        assertTrue(text.contains("could not be recorded"))
        assertTrue(text.contains("Answer from the conversation history"))
        assertTrue(text.contains("delegate_to_subagent"))
    }
}
