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
        assertTrue(text.contains("[handoff/test] hello thread="))
        assertTrue(text.matches(Regex("(?s)\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[handoff/test\\] hello thread=.+\\n")))
    }

    @Test fun logLineCarriesTheWritingThreadName() {
        val log = DiagnosticsLog(tempDir())
        log.log("handoff/test", "hello")
        // The test itself runs on this thread, so its name must be in the line.
        assertTrue(log.readText().contains("thread=${Thread.currentThread().name}\n"))
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

    @Test fun threadDumpWritesOneDelimitedBlock() {
        val log = DiagnosticsLog(tempDir())
        log.log("handoff/test", "before")
        log.threadDump()
        log.log("handoff/test", "after")
        val text = log.readText()
        assertTrue(text.contains("--- thread dump:"))
        assertTrue(text.contains("--- end thread dump ---"))
        // The calling thread is always in its own dump.
        assertTrue(text.contains("\"${Thread.currentThread().name}\" state="))
        // The block sits between the surrounding lines, in order.
        assertTrue(text.indexOf("before") < text.indexOf("--- thread dump:"))
        assertTrue(text.indexOf("--- end thread dump ---") < text.indexOf("after"))
    }

    @Test fun threadDumpNeverThrowsOnAnUnwritableDir() {
        val blocker = File(System.getProperty("java.io.tmpdir"), "tov-diag-block-${System.nanoTime()}")
        blocker.writeText("not a dir")
        val log = DiagnosticsLog(blocker)
        log.threadDump()
        assertEquals("", log.readText())
    }

    @Test fun formatThreadDumpSortsByNameAndShowsFrames() {
        val frames = arrayOf(StackTraceElement("com.example.Foo", "bar", "Foo.kt", 42))
        val dump = formatThreadDump(
            mapOf(
                Thread("zeta") to frames,
                Thread("alpha") to emptyArray(),
            ),
            "2026-11-16 12:00:00.000",
        )
        assertTrue(dump.contains("2026-11-16 12:00:00.000 [thread-dump] --- thread dump: 2 threads ---"))
        assertTrue(dump.contains("--- end thread dump ---"))
        // Sorted by name: alpha first, zeta second.
        assertTrue(dump.indexOf("\"alpha\"") < dump.indexOf("\"zeta\""))
        assertTrue(dump.contains("\"zeta\" state=NEW"))
        assertTrue(dump.contains("    at com.example.Foo.bar(Foo.kt:42)"))
        assertTrue(dump.contains("\"alpha\" state=NEW"))
        assertTrue(dump.contains("    (no stack trace)"))
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
