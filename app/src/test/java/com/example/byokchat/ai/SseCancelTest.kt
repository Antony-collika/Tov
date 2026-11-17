package com.example.byokchat.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 * The server sends one chunked event, then holds the connection open, so
 * the client is blocked in a read when it is cancelled — the same position
 * as a sub reading a live provider stream when the user presses Stop.
 * Chunked framing matches what real SSE servers send; an unframed body
 * would let the cancelled read end as a clean EOF instead of an error.
 *
 * The client job runs on [Dispatchers.IO]: the test thread blocks on the
 * latch, so a job launched on the runBlocking event loop could never run
 * (it deadlocked the first version of this test — no event ever arrived).
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
                    val payload = "data: {\"hello\":1}\n\n"
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n")
                    out.write(payload.toByteArray(Charsets.UTF_8).size.toString(16) + "\r\n")
                    out.write(payload)
                    out.write("\r\n")
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
            val job = launch(Dispatchers.IO) {
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
