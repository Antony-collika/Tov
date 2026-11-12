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
        model: String = "test-model",
    ) = AiRequest(
        model = model,
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

    // ---- Custom (Chat Completions — OpenAI chính hãng giờ dùng ResponsesProvider) ----

    @Test
    fun customIncludeUsageOnByDefault() {
        val body = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(request(ProviderType.CUSTOM))
        assertTrue(body["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.content == "true")
    }

    @Test
    fun customIncludeUsageOffPerProviderOmitsStreamOptions() {
        // Đợt 7: include_usage theo từng provider — tắt ở provider này thì
        // body không còn stream_options, provider khác không bị ảnh hưởng.
        val off = cfg(ProviderType.CUSTOM).copy(includeUsage = false)
        val body = OpenAiProvider(off).buildBody(request(ProviderType.CUSTOM))
        assertNull(body["stream_options"])
        val onBody = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(request(ProviderType.CUSTOM))
        assertTrue(onBody.containsKey("stream_options"))
    }

    @Test
    fun customUsesMaxCompletionTokensNotMaxTokens() {
        val body = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(request(ProviderType.CUSTOM))
        assertEquals(8192, body["max_completion_tokens"]?.jsonPrimitive?.content?.toInt())
        assertNull(body["max_tokens"])
        assertNoSamplingFields(body)
    }

    @Test
    fun customDefaultEffortSendsToolsWithEffortMedium() {
        // Đợt provider 12/11/2026: không ép "none" nữa — effort đã lưu mặc
        // định là medium, Custom gửi nguyên "medium" + đủ tool.
        val body = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(request(ProviderType.CUSTOM))
        assertEquals("medium", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals(1, body["tools"]?.jsonArray?.size)
        assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun customEffortNoneSendsToolsWithAutoChoice() {
        val body = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(
            request(ProviderType.CUSTOM, metadata = MetadataParams(effort = ReasoningEffort.NONE))
        )
        assertEquals("none", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals(1, body["tools"]?.jsonArray?.size)
        assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun customStopCappedAtFourAndNoJsonOrTierFields() {
        val body = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(
            request(
                ProviderType.CUSTOM,
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
    fun customNeverSendsServiceTier() {
        val body = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(request(ProviderType.CUSTOM))
        assertNull(body["service_tier"])
    }

    // ---- OpenAI Responses ----

    @Test
    fun responsesBodyHasCorrectShape() {
        val body = ResponsesProvider(cfg(ProviderType.OPENAI)).buildBody(request(ProviderType.OPENAI, model = "gpt-5"))
        assertEquals("gpt-5", body["model"]?.jsonPrimitive?.content)
        assertEquals(8192, body["max_output_tokens"]?.jsonPrimitive?.content?.toInt())
        assertNull(body["max_completion_tokens"])
        assertEquals("Be helpful", body["instructions"]?.jsonPrimitive?.content)
        assertEquals("true", body["stream"]?.jsonPrimitive?.content)
        assertEquals("false", body["store"]?.jsonPrimitive?.content)
        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("medium", reasoning["effort"]?.jsonPrimitive?.content)
        assertEquals("auto", reasoning["summary"]?.jsonPrimitive?.content)
        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("function", tools[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("time_now", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
        // Responses không có param stop, không có stream_options, không có sampling.
        assertNull(body["stop"])
        assertNull(body["stream_options"])
        assertNoSamplingFields(body)
    }

    @Test
    fun responsesEffortNoneOmitsSummary() {
        val body = ResponsesProvider(cfg(ProviderType.OPENAI)).buildBody(
            request(ProviderType.OPENAI, metadata = MetadataParams(effort = ReasoningEffort.NONE), model = "gpt-5")
        )
        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("none", reasoning["effort"]?.jsonPrimitive?.content)
        assertNull(reasoning["summary"])
    }

    @Test
    fun responsesInputReplaysItemsVerbatim() {
        val replay = """[{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"abc"},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"It is 09:00."}]},{"id":"fc_1","type":"function_call","call_id":"call-1","name":"time_now","arguments":"{}"}]"""
        val msgs = listOf(
            AiMessage(Role.USER, text = "What time is it?"),
            AiMessage(Role.ASSISTANT, text = "It is 09:00.", reasoningReplay = replay, reasoningProvider = "openai"),
            AiMessage(Role.TOOL, text = "09:00", toolCallId = "call-1", toolName = "time_now"),
        )
        val input = ResponsesProvider(cfg(ProviderType.OPENAI)).buildInput(msgs)
        assertEquals(5, input.size)
        assertEquals("user", input[0].jsonObject["role"]?.jsonPrimitive?.content)
        // Reasoning item gửi lại nguyên vẹn, kể cả encrypted_content.
        assertEquals("rs_1", input[1].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("reasoning", input[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("abc", input[1].jsonObject["encrypted_content"]?.jsonPrimitive?.content)
        assertEquals("message", input[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("function_call", input[3].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call-1", input[3].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("function_call_output", input[4].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call-1", input[4].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("09:00", input[4].jsonObject["output"]?.jsonPrimitive?.content)
    }

    @Test
    fun responsesInputDropsOrphanFunctionCall() {
        // Function_call không có kết quả tool đi kèm thì bỏ (API từ chối lệnh gọi lẻ).
        val replay = """[{"id":"fc_9","type":"function_call","call_id":"call-orphan","name":"time_now","arguments":"{}"}]"""
        val msgs = listOf(
            AiMessage(Role.USER, text = "Hi"),
            AiMessage(Role.ASSISTANT, text = "", reasoningReplay = replay, reasoningProvider = "openai"),
        )
        val input = ResponsesProvider(cfg(ProviderType.OPENAI)).buildInput(msgs)
        assertEquals(1, input.size)
        assertEquals("user", input[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun responsesInputFallsBackForLegacyMessages() {
        // Tin cũ / nhập từ file (không có response items): dựng lại từ chữ + tool calls.
        val msgs = listOf(
            AiMessage(Role.USER, text = "Hi"),
            AiMessage(
                Role.ASSISTANT, text = "Hello",
                toolCalls = listOf(AiToolCall("call-1", "time_now", "{}")),
            ),
            AiMessage(Role.TOOL, text = "09:00", toolCallId = "call-1", toolName = "time_now"),
        )
        val input = ResponsesProvider(cfg(ProviderType.OPENAI)).buildInput(msgs)
        assertEquals(4, input.size)
        assertEquals("assistant", input[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Hello", input[1].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("function_call", input[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call-1", input[2].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("call-1", input[2].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("function_call_output", input[3].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun responsesReplayReasoningOffSkipsReasoningOnPlainTurns() {
        // Công tắc "Resend saved reasoning" tắt + lượt thường (không tool
        // call trong lịch sử — tools vẫn được offer): reasoning item bị bỏ,
        // tin message vẫn gửi.
        val replay = """[{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"abc"},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"It is 09:00."}]}]"""
        val req = request(
            ProviderType.OPENAI,
            metadata = MetadataParams(replayReasoning = false),
            model = "gpt-5",
        ).copy(
            messages = listOf(
                AiMessage(Role.USER, text = "What time is it?"),
                AiMessage(Role.ASSISTANT, text = "It is 09:00.", reasoningReplay = replay, reasoningProvider = "openai"),
            ),
        )
        val input = ResponsesProvider(cfg(ProviderType.OPENAI)).buildBody(req)["input"]!!.jsonArray
        assertEquals(2, input.size)
        assertEquals("user", input[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("message", input[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun responsesReplayReasoningOffStillSendsReasoningForToolCallTurns() {
        // Cùng công tắc tắt, nhưng lượt có tool call: reasoning vẫn gửi
        // (OpenAI bắt buộc reasoning đi kèm kết quả tool call).
        val replay = """[{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"abc"},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"It is 09:00."}]},{"id":"fc_1","type":"function_call","call_id":"call-1","name":"time_now","arguments":"{}"}]"""
        val req = request(
            ProviderType.OPENAI,
            metadata = MetadataParams(replayReasoning = false),
            model = "gpt-5",
        ).copy(
            messages = listOf(
                AiMessage(Role.USER, text = "What time is it?"),
                AiMessage(Role.ASSISTANT, text = "It is 09:00.", reasoningReplay = replay, reasoningProvider = "openai"),
                AiMessage(Role.TOOL, text = "09:00", toolCallId = "call-1", toolName = "time_now"),
            ),
        )
        val input = ResponsesProvider(cfg(ProviderType.OPENAI)).buildBody(req)["input"]!!.jsonArray
        assertEquals(5, input.size)
        assertEquals("reasoning", input[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("rs_1", input[1].jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun responsesReplayReasoningOnByDefaultSendsReasoningOnPlainTurns() {
        // Mặc định BẬT: lượt thường vẫn gửi reasoning như trước.
        val replay = """[{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"abc"},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"It is 09:00."}]}]"""
        val req = request(ProviderType.OPENAI, model = "gpt-5").copy(
            messages = listOf(
                AiMessage(Role.USER, text = "What time is it?"),
                AiMessage(Role.ASSISTANT, text = "It is 09:00.", reasoningReplay = replay, reasoningProvider = "openai"),
            ),
        )
        val input = ResponsesProvider(cfg(ProviderType.OPENAI)).buildBody(req)["input"]!!.jsonArray
        assertEquals(3, input.size)
        assertEquals("reasoning", input[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("message", input[2].jsonObject["type"]?.jsonPrimitive?.content)
    }

    // ---- Custom (kể cả DeepSeek qua Custom) ----

    @Test
    fun customSendsReasoningContentWhenToolsAreSent() {
        val req = AiRequest(
            model = "deepseek-chat", system = "Be helpful",
            messages = listOf(
                AiMessage(Role.USER, text = "Hi"),
                AiMessage(Role.ASSISTANT, text = "Hello", reasoningText = "thinking step by step", reasoningProvider = "custom"),
            ),
            tools = toolDefs(), maxOutputTokens = 8192, metadata = MetadataParams(),
        )
        val body = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(req)
        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertEquals("thinking step by step", assistant["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun customDoesNotSendReasoningContentWithoutTools() {
        // Không có tools thì máy chủ bỏ qua reasoning_content nên không gửi.
        val req = AiRequest(
            model = "deepseek-chat", system = "Be helpful",
            messages = listOf(
                AiMessage(Role.USER, text = "Hi"),
                AiMessage(Role.ASSISTANT, text = "Hello", reasoningText = "thinking step by step", reasoningProvider = "custom"),
            ),
            tools = emptyList(), maxOutputTokens = 8192, metadata = MetadataParams(),
        )
        val body = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(req)
        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertNull(assistant["reasoning_content"])
    }

    @Test
    fun customReplaysLegacyDeepSeekReasoning() {
        // Tin cũ của loại DeepSeek (reasoningProvider "deepseek") vẫn gửi
        // lại reasoning_content sau khi chuyển sang Custom.
        val req = AiRequest(
            model = "deepseek-chat", system = "Be helpful",
            messages = listOf(
                AiMessage(Role.USER, text = "Hi"),
                AiMessage(Role.ASSISTANT, text = "Hello", reasoningText = "thinking step by step", reasoningProvider = "deepseek"),
            ),
            tools = toolDefs(), maxOutputTokens = 8192, metadata = MetadataParams(),
        )
        val body = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(req)
        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertEquals("thinking step by step", assistant["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun customDoesNotReplayOtherProvidersReasoning() {
        // Suy luận của hãng khác (vd OpenAI) không phải reasoning_content.
        val req = AiRequest(
            model = "test-model", system = "Be helpful",
            messages = listOf(
                AiMessage(Role.USER, text = "Hi"),
                AiMessage(Role.ASSISTANT, text = "Hello", reasoningText = "thinking step by step", reasoningProvider = "openai"),
            ),
            tools = toolDefs(), maxOutputTokens = 8192, metadata = MetadataParams(),
        )
        val body = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(req)
        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertNull(assistant["reasoning_content"])
    }

    @Test
    fun customEffortVerbatimExceptDeepSeekServer() {
        // Máy chủ tương thích khác gửi nguyên vẹn tên mức; riêng DeepSeek
        // (api.deepseek.com) thì kẹp về low/high/max như loại cũ.
        val medium = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(request(ProviderType.CUSTOM, model = "some-model"))
        assertEquals("medium", medium["reasoning_effort"]?.jsonPrimitive?.content)
        val deepSeekCfg = cfg(ProviderType.CUSTOM).copy(baseUrl = "https://api.deepseek.com")
        val clamped = OpenAiProvider(deepSeekCfg).buildBody(request(ProviderType.CUSTOM, model = "deepseek-chat"))
        assertEquals("high", clamped["reasoning_effort"]?.jsonPrimitive?.content)
        val maxClamped = OpenAiProvider(deepSeekCfg).buildBody(
            request(ProviderType.CUSTOM, metadata = MetadataParams(effort = ReasoningEffort.MAX), model = "deepseek-chat")
        )
        assertEquals("max", maxClamped["reasoning_effort"]?.jsonPrimitive?.content)
        assertTrue(OpenAiProvider(deepSeekCfg).isDeepSeekServer())
        assertFalse(OpenAiProvider(cfg(ProviderType.CUSTOM)).isDeepSeekServer())
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
}
