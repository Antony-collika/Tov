package com.example.byokchat.ai

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test cho Đợt 6: body thật gửi đi của từng hãng (qua buildBody tách riêng
 * khỏi stream, không cần mạng). Kiểm tra metadata map đúng trường, và
 * KHÔNG BAO GIỜ gửi temperature/top_p/top_k.
 */
class ProviderBodyTest {

    private fun cfg(type: ProviderType) = ProviderConfig(
        id = "p1", type = type, name = "Test", apiKey = "k", maxOutputTokens = 8192,
    )

    private fun toolDefs() = listOf(
        ToolDef("time_now", "Get the current time", buildJsonObject {}),
    )

    private fun request(
        type: ProviderType,
        metadata: MetadataParams = MetadataParams(),
        tools: List<ToolDef> = toolDefs(),
    ) = AiRequest(
        model = "test-model",
        system = "Be helpful",
        messages = listOf(AiMessage(Role.USER, text = "Hi")),
        tools = tools,
        maxOutputTokens = 8192,
        metadata = metadata,
    )

    private fun assertNoSamplingFields(body: JsonObject) {
        assertNull(body["temperature"])
        assertNull(body["top_p"])
        assertNull(body["top_k"])
    }

    private fun assertNoSamplingFieldsInGenerationConfig(body: JsonObject) {
        val gen = body["generationConfig"]!!.jsonObject
        assertNull(gen["temperature"])
        assertNull(gen["topP"])
        assertNull(gen["topK"])
        assertNull(gen["candidateCount"])
        assertNull(gen["presencePenalty"])
        assertNull(gen["frequencyPenalty"])
    }

    // ---- OpenAI ----

    @Test
    fun openAiIncludeUsageOnByDefault() {
        val body = OpenAiProvider(cfg(ProviderType.OPENAI)).buildBody(request(ProviderType.OPENAI))
        assertTrue(body["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.content == "true")
    }

    @Test
    fun openAiIncludeUsageOffPerProviderOmitsStreamOptions() {
        // Đợt 7: include_usage theo từng provider — tắt ở provider này thì
        // body không còn stream_options, provider khác không bị ảnh hưởng.
        val off = cfg(ProviderType.OPENAI).copy(includeUsage = false)
        val body = OpenAiProvider(off).buildBody(request(ProviderType.OPENAI))
        assertNull(body["stream_options"])
        val onBody = OpenAiProvider(cfg(ProviderType.OPENAI)).buildBody(request(ProviderType.OPENAI))
        assertTrue(onBody.containsKey("stream_options"))
    }

    @Test
    fun openAiUsesMaxCompletionTokensNotMaxTokens() {
        val body = OpenAiProvider(cfg(ProviderType.OPENAI)).buildBody(request(ProviderType.OPENAI))
        assertEquals(8192, body["max_completion_tokens"]?.jsonPrimitive?.content?.toInt())
        assertNull(body["max_tokens"])
        assertNoSamplingFields(body)
    }

    @Test
    fun openAiDefaultEffortStillSendsToolsWithEffortNone() {
        // Đợt fix 11/11/2026 (D3): effort đã lưu mặc định là medium, nhưng
        // OpenAI luôn gửi reasoning_effort "none" + đủ tool — app ép về
        // none để tool luôn dùng được.
        val body = OpenAiProvider(cfg(ProviderType.OPENAI)).buildBody(request(ProviderType.OPENAI))
        assertEquals("none", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals(1, body["tools"]?.jsonArray?.size)
        assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun openAiEffortNoneSendsToolsWithAutoChoice() {
        val body = OpenAiProvider(cfg(ProviderType.OPENAI)).buildBody(
            request(ProviderType.OPENAI, metadata = MetadataParams(effort = ReasoningEffort.NONE))
        )
        assertEquals("none", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals(1, body["tools"]?.jsonArray?.size)
        assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun openAiStopCappedAtFourAndNoJsonOrTierFields() {
        val body = OpenAiProvider(cfg(ProviderType.OPENAI)).buildBody(
            request(
                ProviderType.OPENAI,
                metadata = MetadataParams(
                    effort = ReasoningEffort.NONE,
                    stopSequences = listOf("a", "b", "c", "d", "e"),
                ),
            )
        )
        assertEquals(listOf("a", "b", "c", "d"), body["stop"]?.jsonArray?.map { it.jsonPrimitive.content })
        // Structured output và service tier đã bỏ khỏi Metadata (D4/D6).
        assertNull(body["response_format"])
        assertNull(body["service_tier"])
    }

    @Test
    fun openAiNeverSendsServiceTier() {
        val body = OpenAiProvider(cfg(ProviderType.OPENAI)).buildBody(request(ProviderType.OPENAI))
        assertNull(body["service_tier"])
    }

    // ---- Gemini ----

    @Test
    fun geminiDefaultThinkingLevelMedium() {
        val body = GeminiProvider(cfg(ProviderType.GEMINI)).buildBody(request(ProviderType.GEMINI))
        val gen = body["generationConfig"]!!.jsonObject
        assertEquals(8192, gen["maxOutputTokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("MEDIUM", gen["thinkingConfig"]?.jsonObject?.get("thinkingLevel")?.jsonPrimitive?.content)
        assertNoSamplingFieldsInGenerationConfig(body)
        assertNull(body["serviceTier"])
    }

    @Test
    fun geminiEffortNoneOmitsThinkingConfig() {
        val body = GeminiProvider(cfg(ProviderType.GEMINI)).buildBody(
            request(ProviderType.GEMINI, metadata = MetadataParams(effort = ReasoningEffort.NONE))
        )
        assertNull(body["generationConfig"]!!.jsonObject["thinkingConfig"])
    }

    @Test
    fun geminiStopsAndNoJsonField() {
        val body = GeminiProvider(cfg(ProviderType.GEMINI)).buildBody(
            request(
                ProviderType.GEMINI,
                metadata = MetadataParams(
                    stopSequences = listOf("END"),
                ),
            )
        )
        val gen = body["generationConfig"]!!.jsonObject
        assertEquals(listOf("END"), gen["stopSequences"]?.jsonArray?.map { it.jsonPrimitive.content })
        // Structured output đã bỏ khỏi Metadata (D4).
        assertNull(gen["responseMimeType"])
    }

    @Test
    fun geminiToolsStillSentWithReasoningOn() {
        // Gemini không có xung đột reasoning+tools như OpenAI.
        val body = GeminiProvider(cfg(ProviderType.GEMINI)).buildBody(request(ProviderType.GEMINI))
        assertEquals(1, body["tools"]?.jsonArray?.size)
    }

    @Test
    fun geminiTextThoughtSignatureSentWhenKept() {
        // Chữ ký của phần chữ được gửi lại khi công tắc bật; tắt thì không.
        // Chữ ký của lệnh gọi tool thì luôn gửi, không phụ thuộc công tắc.
        val assistant = AiMessage(
            Role.ASSISTANT,
            text = "It is 09:00.",
            thoughtSignature = "sig-text",
            toolCalls = listOf(AiToolCall("call-1", "time_now", "{}", signature = "sig-call")),
        )
        val req = AiRequest(
            model = "test-model", system = "Be helpful",
            messages = listOf(AiMessage(Role.USER, text = "Hi"), assistant),
            tools = toolDefs(), maxOutputTokens = 8192,
            metadata = MetadataParams(),
        )
        fun modelParts(body: JsonObject) = body["contents"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["role"]?.jsonPrimitive?.content == "model" }
            .flatMap { it["parts"]!!.jsonArray.map { p -> p.jsonObject } }
        val parts = modelParts(GeminiProvider(cfg(ProviderType.GEMINI)).buildBody(req))
        assertEquals("sig-text", parts.first { it.containsKey("text") }["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals("sig-call", parts.first { it.containsKey("functionCall") }["thoughtSignature"]?.jsonPrimitive?.content)

        val offParts = modelParts(
            GeminiProvider(cfg(ProviderType.GEMINI)).buildBody(
                req.copy(metadata = MetadataParams(keepThoughtSignature = false)),
            ),
        )
        assertNull(offParts.first { it.containsKey("text") }["thoughtSignature"])
        assertEquals("sig-call", offParts.first { it.containsKey("functionCall") }["thoughtSignature"]?.jsonPrimitive?.content)
    }

    // ---- Claude ----

    @Test
    fun claudeNeverSendsTemperatureTopPTopK() {
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(request(ProviderType.CLAUDE), useCache = false)
        assertEquals(8192, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("medium", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertNoSamplingFields(body)
        // Structured output không gửi cho Claude (cần schema).
        assertNull(body["output_config"]?.jsonObject?.get("format"))
        assertFalse(body.containsKey("temperature") || body.containsKey("top_p") || body.containsKey("top_k"))
    }

    @Test
    fun claudeEffortNoneOmitsOutputConfig() {
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(ProviderType.CLAUDE, metadata = MetadataParams(effort = ReasoningEffort.NONE)),
            useCache = false,
        )
        assertNull(body["output_config"])
    }

    @Test
    fun claudeStopsAndNoTierField() {
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(
                ProviderType.CLAUDE,
                metadata = MetadataParams(
                    stopSequences = listOf("END"),
                ),
            ),
            useCache = false,
        )
        // Service tier đã bỏ khỏi Metadata (D6).
        assertNull(body["service_tier"])
        assertEquals(listOf("END"), body["stop_sequences"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun claudeNeverSendsServiceTier() {
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(request(ProviderType.CLAUDE), useCache = false)
        assertNull(body["service_tier"])
    }
}
