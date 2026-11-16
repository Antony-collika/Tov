package com.example.byokchat.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.debug.DebugProbes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

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

    @Test fun coroutineDumpWritesNotInstalledNoteWhenProbesAreNotInstalled() {
        // No test installs the probes, so isInstalled is false here: the
        // block must carry the note, not an empty dump and not a throw.
        val log = DiagnosticsLog(tempDir())
        log.log("handoff/test", "before")
        log.coroutineDump()
        log.log("handoff/test", "after")
        val text = log.readText()
        assertTrue(text.contains("--- coroutine dump ---"))
        assertTrue(text.contains("DebugProbes not installed"))
        assertTrue(text.contains("--- end coroutine dump ---"))
        // The block sits between the surrounding lines, in order.
        assertTrue(text.indexOf("before") < text.indexOf("--- coroutine dump ---"))
        assertTrue(text.indexOf("--- end coroutine dump ---") < text.indexOf("after"))
    }

    @Test fun coroutineDumpNeverThrowsOnAnUnwritableDir() {
        val blocker = File(System.getProperty("java.io.tmpdir"), "tov-diag-block-${System.nanoTime()}")
        blocker.writeText("not a dir")
        val log = DiagnosticsLog(blocker)
        log.coroutineDump()
        assertEquals("", log.readText())
    }

    @Test fun formatCoroutineDumpShowsNoteWhenNotInstalled() {
        val dump = formatCoroutineDump("", installed = false, "2026-11-16 12:00:00.000")
        assertTrue(dump.contains("2026-11-16 12:00:00.000 [coroutine-dump] --- coroutine dump ---"))
        assertTrue(dump.contains("DebugProbes not installed"))
        assertTrue(dump.contains("--- end coroutine dump ---"))
    }

    @Test fun formatCoroutineDumpKeepsDumpTextWhenInstalled() {
        val dump = formatCoroutineDump(
            "Coroutines dump 2026/11/16 12:00:00\n\nCoroutine \"main#1\":StandaloneCoroutine{Active}@1, state: SUSPENDED\n",
            installed = true,
            "2026-11-16 12:00:00.000",
        )
        assertTrue(dump.contains("--- coroutine dump ---"))
        assertTrue(dump.contains("Coroutine \"main#1\""))
        assertTrue(dump.contains("--- end coroutine dump ---"))
        assertTrue(!dump.contains("not installed"))
    }

    @Test fun formatCoroutineDumpSaysSoWhenInstalledButEmpty() {
        val dump = formatCoroutineDump("", installed = true, "2026-11-16 12:00:00.000")
        assertTrue(dump.contains("(no coroutines)"))
        assertTrue(dump.contains("--- end coroutine dump ---"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun dumpCoroutinesWithoutInstallThrows() {
        // Library behaviour, verified against the kotlinx-coroutines-debug
        // 1.9.0 source (DebugProbes.kt: "All introspecting methods throw
        // IllegalStateException if debug probes were not installed") — it
        // does NOT return an empty dump. That is why coroutineDump() checks
        // isInstalled first instead of calling it blindly.
        assertFalse(DebugProbes.isInstalled)
        assertThrows(IllegalStateException::class.java) {
            val bytes = ByteArrayOutputStream()
            PrintStream(bytes, true, Charsets.UTF_8).use { DebugProbes.dumpCoroutines(it) }
        }
    }

    @Test fun clearDeletesTheFileAndLaterWritesRecreateIt() {
        val log = DiagnosticsLog(tempDir())
        log.log("handoff/test", "old line")
        assertTrue(log.file().exists())
        log.clear()
        assertEquals("", log.readText())
        assertTrue(!log.file().exists())
        log.log("handoff/test", "new line")
        val text = log.readText()
        assertTrue(text.contains("new line"))
        assertTrue(!text.contains("old line"))
    }

    @Test fun clearOnAnEmptyLogIsANoop() {
        val log = DiagnosticsLog(tempDir())
        log.clear()
        assertEquals("", log.readText())
    }

    @Test fun clearNeverThrowsOnAnUnwritableDir() {
        val blocker = File(System.getProperty("java.io.tmpdir"), "tov-diag-block-${System.nanoTime()}")
        blocker.writeText("not a dir")
        val log = DiagnosticsLog(blocker)
        log.clear()
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
