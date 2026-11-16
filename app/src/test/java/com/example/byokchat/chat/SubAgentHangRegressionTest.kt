package com.example.byokchat.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression test for the 16/11/2026 sub-agent hang (5 stalls on device,
 * same joint every time): the delegate tool's `withContext` never
 * returned after a sub finished normally.
 *
 * The shape, mirrored from the real code: the delegate tool runs under
 * `withContext` (ToolRegistry.execute); `SubAgentRunner.run` creates a
 * bare child Job of that Job (`subJob`, SubAgentRunner.kt) and runs the
 * sub's provider loop under it (`withContext(subJob)`). A Job only
 * completes once all its children complete — so if the child Job is
 * never completed, the parent `withContext` Job stays in COMPLETING
 * forever and `execute()` never returns: no tool-result upsert, no
 * round-2 request, dispatchers idle, "Main is typing" forever.
 *
 * A full SubAgentRunner test is not possible in plain JUnit (it needs
 * SettingsRepository, Room and a provider), so these tests encode the
 * exact Job structure instead. The first test is the fixed shape (the
 * `finally` in `run()` completes the child Job); the second proves the
 * pre-fix shape really hangs.
 */
class SubAgentHangRegressionTest {

    @Test
    fun delegateWithContextReturnsWhenSubJobIsCompleted() = runBlocking {
        val result = withTimeout(10_000) {
            withContext(Dispatchers.Default) {
                val subJob = Job(parent = currentCoroutineContext()[Job])
                try {
                    withContext(subJob) { "sub-work-done" }
                } finally {
                    // The fix (SubAgentRunner.run finally): complete the
                    // child Job so the parent withContext Job can complete.
                    subJob.complete()
                }
                "delegate-result"
            }
        }
        assertEquals("delegate-result", result)
    }

    @Test
    fun delegateWithContextHangsWhenSubJobIsNeverCompleted() = runBlocking {
        // Documents the stall: without complete(), the outer withContext
        // never resumes — withTimeoutOrNull proves it (null = timed out).
        val result = withTimeoutOrNull(2_000) {
            withContext(Dispatchers.Default) {
                val subJob = Job(parent = currentCoroutineContext()[Job])
                try {
                    withContext(subJob) { "sub-work-done" }
                } finally {
                    // No complete() — the pre-fix code.
                }
                "delegate-result"
            }
        }
        assertNull(result)
    }
}
