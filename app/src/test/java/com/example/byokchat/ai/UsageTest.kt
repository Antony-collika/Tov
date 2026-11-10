package com.example.byokchat.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun parsesClaudeUsage() {
        val u = Usage.fromClaude(obj("""{"input_tokens":1200,"output_tokens":56,"cache_read_input_tokens":1000,"cache_creation_input_tokens":200}"""))
        assertEquals(1200, u.inputTokens)
        assertEquals(56, u.outputTokens)
        assertEquals(1000, u.cachedTokens)
        assertEquals(200, u.cacheCreationTokens)
        assertEquals("[usage] prompt 1200 · cached 1000 · cache write 200 · completion 56", u.summaryLine())
    }

    @Test
    fun parsesOpenAiUsage() {
        val u = Usage.fromOpenAi(obj("""{"prompt_tokens":300,"completion_tokens":40,"prompt_tokens_details":{"cached_tokens":250}}"""))
        assertEquals(300, u.inputTokens)
        assertEquals(40, u.outputTokens)
        assertEquals(250, u.cachedTokens)
        assertNull(u.cacheCreationTokens)
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
