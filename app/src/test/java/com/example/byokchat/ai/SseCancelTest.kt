package com.example.byokchat.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Stop during an SSE stream (17/11/2026): cancelling the collector must
 * surface as [CancellationException], never as a raw IOException or a
 * network error — otherwise the sub run is recorded FAILED and Main gets
 * a tool error instead of "Interrupted by the user."
 *
 * The server sends one event, then holds the connection open, so the
 * client is blocked in a read when it is cancelled — the same position
 * as a sub reading a live provider stream when the user presses Stop.
 */
class SseCancelTest {

    @Test(timeout = 30_000)
    fun cancellingMidStreamThrowsCancellationException() = runBlocking {
        val server = ServerSocket(0)
        val firstEvent = CountDownLatch(1)
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n")
                    out.write("data: {\"hello\":1}\n\n")
                    out.flush()
                    // Hold the stream open: the client reads nothing more
                    // until it is cancelled. Daemon thread, so a slow
                    // teardown never blocks the test JVM.
                    Thread.sleep(10_000)
                }
            } catch (_: Exception) {
                // Teardown closes the socket; nothing to report.
            }
        }.also { it.isDaemon = true; it.start() }
        try {
            val thrown = AtomicReference<Throwable?>(null)
            val job = launch {
                try {
                    Http.client.sseFlow(
                        Request.Builder().url("http://127.0.0.1:${server.localPort}/").build(),
                    ).collect { firstEvent.countDown() }
                } catch (e: Throwable) {
                    thrown.set(e)
                }
            }
            assertTrue("no SSE event arrived", firstEvent.await(10, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            val error = thrown.get()
            assertTrue(
                "expected CancellationException on Stop, got ${error?.javaClass?.simpleName}: ${error?.message}",
                error is CancellationException,
            )
        } finally {
            runCatching { server.close() }
            serverThread.join(2_000)
        }
    }
}
