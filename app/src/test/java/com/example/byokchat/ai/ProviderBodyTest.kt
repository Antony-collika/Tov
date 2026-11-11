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
    fun openAiUsesMaxCompletionTokensNotMaxTokens() {
        val body = OpenAiProvider(cfg(ProviderType.OPENAI)).buildBody(request(ProviderType.OPENAI))
        assertEquals(8192, body["max_completion_tokens"]?.jsonPrimitive?.content?.toInt())
        assertNull(body["max_tokens"])
        assertNoSamplingFields(body)
    }

    @Test
    fun openAiDefaultEffortMediumSuppressesTools() {
        // Mặc định effort medium → Chat Completions không cho gọi tool.
        val body = OpenAiProvider(cfg(ProviderType.OPENAI)).buildBody(request(ProviderType.OPENAI))
        assertEquals("medium", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertNull(body["tools"])
        assertNull(body["tool_choice"])
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
    fun openAiStopCappedAtFourAndStructuredOutputIsJsonObject() {
        val body = OpenAiProvider(cfg(ProviderType.OPENAI)).buildBody(
            request(
                ProviderType.OPENAI,
                metadata = MetadataParams(
                    effort = ReasoningEffort.NONE,
                    stopSequences = listOf("a", "b", "c", "d", "e"),
                    structuredOutput = true,
                    serviceTier = ServiceTier.FLEX,
                ),
            )
        )
        assertEquals(listOf("a", "b", "c", "d"), body["stop"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("json_object", body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("flex", body["service_tier"]?.jsonPrimitive?.content)
    }

    @Test
    fun openAiServiceTierAutoIsNotSent() {
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
    fun geminiStructuredOutputAndStops() {
        val body = GeminiProvider(cfg(ProviderType.GEMINI)).buildBody(
            request(
                ProviderType.GEMINI,
                metadata = MetadataParams(
                    stopSequences = listOf("END"),
                    structuredOutput = true,
                ),
            )
        )
        val gen = body["generationConfig"]!!.jsonObject
        assertEquals(listOf("END"), gen["stopSequences"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("application/json", gen["responseMimeType"]?.jsonPrimitive?.content)
    }

    @Test
    fun geminiToolsStillSentWithReasoningOn() {
        // Gemini không có xung đột reasoning+tools như OpenAI.
        val body = GeminiProvider(cfg(ProviderType.GEMINI)).buildBody(request(ProviderType.GEMINI))
        assertEquals(1, body["tools"]?.jsonArray?.size)
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
    fun claudeServiceTierAndStops() {
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(
                ProviderType.CLAUDE,
                metadata = MetadataParams(
                    stopSequences = listOf("END"),
                    serviceTier = ServiceTier.STANDARD_ONLY,
                ),
            ),
            useCache = false,
        )
        assertEquals("standard_only", body["service_tier"]?.jsonPrimitive?.content)
        assertEquals(listOf("END"), body["stop_sequences"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun claudeServiceTierAutoIsNotSent() {
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(request(ProviderType.CLAUDE), useCache = false)
        assertNull(body["service_tier"])
    }
}
