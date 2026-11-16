package com.example.byokchat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the Diagnostics screen's pure helpers (the composable itself needs Android). */
class DiagnosticsScreenTest {

    @Test fun sessionsInLogAreDistinctAndInOrder() {
        val text = """
            2026-11-16 08:38:19.503 [handoff/sub.run.return] conv=ba949149 turn=t1 agent=research status=DONE
            2026-11-16 08:38:19.600 [handoff/engine.tool-result.upsert.end] conv=ba949149 turn=t1 tool=delegate_to_subagent
            2026-11-16 09:02:11.000 [handoff/sub.run.return] conv=77aa12cd turn=t2 agent=research status=DONE
            2026-11-16 09:02:12.000 [handoff/sub.run.return] conv=ba949149 turn=t3 agent=research status=DONE
        """.trimIndent()
        assertEquals(listOf("ba949149", "77aa12cd"), sessionsInDiagnosticsLog(text))
    }

    @Test fun sessionsInEmptyLogIsEmpty() {
        assertEquals(emptyList<String>(), sessionsInDiagnosticsLog(""))
        assertEquals(emptyList<String>(), sessionsInDiagnosticsLog("no handoff lines here\n"))
    }

    @Test fun sessionsStillParsedWhenLinesCarryThreadNames() {
        // Handoff lines now end with thread=<name>; a thread dump block is
        // in the same file. Neither must change session parsing.
        val text = """
            2026-11-16 12:21:40.870 [handoff/sub.run.return] conv=dd287ba1 turn=t1 agent=monica-the-singer status=DONE thread=DefaultDispatcher-worker-1
            2026-11-16 12:21:40.872 [handoff/delegate.handler.return] conv=dd287ba1 turn=t1 agent=monica-the-singer status=DONE thread=DefaultDispatcher-worker-1
            2026-11-16 12:22:00.000 [thread-dump] --- thread dump: 2 threads ---
            "DefaultDispatcher-worker-1" state=RUNNABLE daemon=false
                at com.example.byokchat.tools.ToolRegistry.execute(ToolRegistry.kt:270)
            "main" state=RUNNABLE daemon=false
                at android.os.Looper.loop(Looper.java:123)
            --- end thread dump ---
        """.trimIndent()
        assertEquals(listOf("dd287ba1"), sessionsInDiagnosticsLog(text))
    }

    @Test fun sessionsStillParsedWhenCoroutineDumpPresent() {
        // A coroutine dump block lives in the same file; it must not change
        // session parsing (its lines carry no conv= ids).
        val text = """
            2026-11-16 12:21:40.870 [handoff/sub.run.return] conv=dd287ba1 turn=t1 agent=monica-the-singer status=DONE thread=DefaultDispatcher-worker-1
            2026-11-16 12:22:00.000 [coroutine-dump] --- coroutine dump ---
            Coroutines dump 2026/11/16 12:22:00

            Coroutine "engine#1":StandaloneCoroutine{Active}@1, state: SUSPENDED
                at com.example.byokchat.chat.ChatEngine.run(ChatEngine.kt:200)
            --- end coroutine dump ---
        """.trimIndent()
        assertEquals(listOf("dd287ba1"), sessionsInDiagnosticsLog(text))
    }

    @Test fun bundleContainsLogSessionsAndOtherLogPaths() {
        val chat = LatestChatFiles(
            sessionId = "ba949149",
            dir = "sessions/2026-11-16_ba949149",
            historyPath = "sessions/2026-11-16_ba949149/history.md",
            metaPath = "sessions/2026-11-16_ba949149/history.meta.json",
            subRunsPath = "sessions/2026-11-16_ba949149/sub_runs.meta.json",
        )
        val bundle = diagnosticsBundle(
            "2026-11-16 08:38:19.503 [handoff/sub.run.return] conv=ba949149 turn=t1\n",
            listOf("ba949149"),
            chat,
        )
        assertTrue(bundle.contains("# Diagnostics"))
        assertTrue(bundle.contains("- ba949149"))
        assertTrue(bundle.contains("[handoff/sub.run.return] conv=ba949149"))
        assertTrue(bundle.contains("sessions/2026-11-16_ba949149/history.md"))
        assertTrue(bundle.contains("sessions/2026-11-16_ba949149/history.meta.json"))
        assertTrue(bundle.contains("sessions/2026-11-16_ba949149/sub_runs.meta.json"))
        assertTrue(bundle.contains("Request log"))
    }

    @Test fun bundleWithNoLogAndNoChatSaysSo() {
        val bundle = diagnosticsBundle("", emptyList(), null)
        assertTrue(bundle.contains("(none yet)"))
        assertTrue(bundle.contains("(empty — no handoff has been logged yet)"))
        assertTrue(bundle.contains("No chat with messages yet"))
    }
}
