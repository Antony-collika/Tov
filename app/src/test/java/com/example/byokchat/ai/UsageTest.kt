package com.example.byokchat.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun parsesOpenAiUsage() {
        val u = Usage.fromOpenAi(obj("""{"prompt_tokens":300,"completion_tokens":40,"prompt_tokens_details":{"cached_tokens":250},"completion_tokens_details":{"reasoning_tokens":15}}"""))
        assertEquals(300, u.inputTokens)
        assertEquals(40, u.outputTokens)
        assertEquals(250, u.cachedTokens)
        assertEquals(15, u.reasoningTokens)
        assertNull(u.cacheCreationTokens)
    }

    @Test
    fun parsesResponsesUsage() {
        val u = Usage.fromResponses(obj("""{"input_tokens":100,"output_tokens":50,"input_tokens_details":{"cached_tokens":80,"cache_write_tokens":5},"output_tokens_details":{"reasoning_tokens":30},"total_tokens":150}"""))
        assertEquals(100, u.inputTokens)
        assertEquals(50, u.outputTokens)
        assertEquals(80, u.cachedTokens)
        assertEquals(5, u.cacheCreationTokens)
        assertEquals(30, u.reasoningTokens)
        assertEquals("[usage] prompt 100 · cached 80 · cache write 5 · completion 50 · reasoning 30", u.summaryLine())
    }

    @Test
    fun parsesGeminiUsage() {
        val u = Usage.fromGemini(obj("""{"promptTokenCount":500,"candidatesTokenCount":60,"cachedContentTokenCount":400}"""))
        assertEquals(500, u.inputTokens)
        assertEquals(60, u.outputTokens)
        assertEquals(400, u.cachedTokens)
    }

    @Test
    fun mergeKeepsLatestNonNull() {
        val start = Usage(inputTokens = 1200, cachedTokens = 1000)
        val delta = Usage(outputTokens = 56)
        assertEquals(Usage(1200, 56, 1000, null), Usage.merge(start, delta))
        assertEquals(start, Usage.merge(start, null))
        assertEquals(delta, Usage.merge(null, delta))
        assertNull(Usage.merge(null, null))
    }

    @Test
    fun emptyUsageReportsNotReported() {
        assertEquals("[usage] not reported", Usage().summaryLine())
    }
}
