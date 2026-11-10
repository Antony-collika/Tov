package com.example.byokchat.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RequestLogTest {
    private val dir: File = Files.createTempDirectory("request-log-test").toFile()
    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun body() = buildJsonObject {
        put("model", "test-model")
        put("messages", "hello")
    }

    @Test
    fun roundTripKeepsRequestResponseAndUsage() {
        val log = RequestLog(dir)
        val id = log.beginRequest("Claude", "claude-test", body())
        log.finishRequest(id, 200, "raw sse data", Usage(100, 20, 80, null), null)
        val entries = log.entries()
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("Claude", e.provider)
        assertEquals("claude-test", e.model)
        assertEquals(200, e.httpStatus)
        assertEquals("raw sse data", e.responseText)
        assertEquals(Usage(100, 20, 80, null), e.usage)
        assertTrue(e.requestBody.contains("test-model"))
        assertNull(e.error)
    }

    @Test
    fun disabledLogWritesNothing() {
        val log = RequestLog(dir, enabled = { false })
        val id = log.beginRequest("Claude", "claude-test", body())
        assertEquals(-1, id)
        log.finishRequest(id, 200, "x", null, null)
        assertEquals(emptyList<RequestLogEntry>(), log.entries())
    }

    @Test
    fun trimsToMaxEntries() {
        val log = RequestLog(dir, maxEntries = 3)
        repeat(5) { i ->
            val id = log.beginRequest("Claude", "m$i", body())
            log.finishRequest(id, 200, "r$i", null, null)
        }
        val entries = log.entries()
        assertEquals(3, entries.size)
        assertEquals(listOf("m4", "m3", "m2"), entries.map { it.model })
    }

    @Test
    fun clearRemovesAllEntries() {
        val log = RequestLog(dir)
        val id = log.beginRequest("Claude", "m", body())
        log.finishRequest(id, 200, "r", null, null)
        assertEquals(1, log.entries().size)
        log.clear()
        assertEquals(emptyList<RequestLogEntry>(), log.entries())
    }

    @Test
    fun base64ImagesAreShortenedButTextIsKept() {
        val log = RequestLog(dir)
        val b64 = "iVBORw0KGgo".repeat(500)
        val shortened = log.shortenForLog(
            buildJsonObject {
                put("image", b64)
                put("text", "plain hello")
            },
        )
        val obj = json.parseToJsonElement(shortened.toString()).jsonObject
        assertEquals("[image 4 KB]", obj["image"]?.jsonPrimitive?.content)
        assertEquals("plain hello", obj["text"]?.jsonPrimitive?.content)
        val dataUrl = buildJsonObject { put("url", "data:image/jpeg;base64," + "AAAA".repeat(1000)) }
        val shortenedUrl = log.shortenForLog(dataUrl)
        assertTrue(shortenedUrl.toString().contains("[image "))
    }
}
