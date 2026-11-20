package com.example.byokchat.ai

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Provider tương thích trả `usage: null` ở mọi chunk trừ chunk cuối khi
 * stream_options.include_usage bật (tài liệu OpenAI), và có thể trả
 * `delta: null` / `*_tokens_details: null` — trước fix, `?.jsonObject`
 * ném "Element class ... is not a JsonObject" và làm hỏng cả lượt
 * (20/11/2026). Các chunk null đó phải được bỏ qua, chữ vẫn chảy ra,
 * số token thật ở chunk cuối vẫn được đọc.
 *
 * Server giả dùng ServerSocket thô như SseCancelTest, trả SSE theo
 * chunked framing giống server thật.
 */
class OpenAiStreamNullTest {

    @Test(timeout = 30_000)
    fun nullUsageAndNullDeltaDoNotFailTheTurn() = runBlocking {
        val server = ServerSocket(0)
        val served = CountDownLatch(1)
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    // Đọc hết header request rồi mới trả lời.
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    var line: String?
                    do { line = reader.readLine() } while (line != null && line.isNotEmpty())
                    val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n")
                    val events = listOf(
                        """data: {"id":"1","choices":[{"delta":{"content":"Hi"},"index":0}],"usage":null}""",
                        """data: {"id":"1","choices":[{"delta":null,"finish_reason":"stop"}],"usage":null}""",
                        """data: {"id":"1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"prompt_tokens_details":null,"completion_tokens_details":null}}""",
                        "data: [DONE]",
                    )
                    for (event in events) {
                        val payload = "$event\n\n"
                        val bytes = payload.toByteArray(Charsets.UTF_8)
                        out.write(bytes.size.toString(16) + "\r\n")
                        out.write(payload)
                        out.write("\r\n")
                        out.flush()
                    }
                    out.write("0\r\n\r\n")
                    out.flush()
                    served.countDown()
                }
            } catch (_: Exception) {
                // Teardown đóng socket; không có gì để báo.
            }
        }.also { it.isDaemon = true; it.start() }
        try {
            val cfg = ProviderConfig(
                id = "test",
                type = ProviderType.CUSTOM,
                name = "Test",
                baseUrl = "http://127.0.0.1:${server.localPort}",
                includeUsage = true,
            )
            val events = OpenAiProvider(cfg).stream(
                AiRequest(
                    model = "test-model",
                    system = "",
                    messages = listOf(AiMessage(Role.USER, "hi")),
                    tools = emptyList(),
                    maxOutputTokens = 100,
                ),
            ).toList()
            assertEquals(
                listOf(AiEvent.Text("Hi")),
                events.filterIsInstance<AiEvent.Text>(),
            )
            assertEquals("server did not finish serving", true, served.await(10, TimeUnit.SECONDS))
        } finally {
            runCatching { server.close() }
            serverThread.join(2_000)
        }
    }
}
