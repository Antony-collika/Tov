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

    // ---- DeepSeek ----

    @Test
    fun deepSeekEffortClampedInBody() {
        val body = OpenAiProvider(cfg(ProviderType.DEEPSEEK)).buildBody(request(ProviderType.DEEPSEEK, model = "deepseek-chat"))
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.content)
        val maxBody = OpenAiProvider(cfg(ProviderType.DEEPSEEK)).buildBody(
            request(ProviderType.DEEPSEEK, metadata = MetadataParams(effort = ReasoningEffort.MAX), model = "deepseek-chat")
        )
        assertEquals("max", maxBody["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun deepSeekReasoningContentSentBack() {
        val req = AiRequest(
            model = "deepseek-chat", system = "Be helpful",
            messages = listOf(
                AiMessage(Role.USER, text = "Hi"),
                AiMessage(Role.ASSISTANT, text = "Hello", reasoningText = "thinking step by step", reasoningProvider = "deepseek"),
            ),
            tools = toolDefs(), maxOutputTokens = 8192, metadata = MetadataParams(),
        )
        val body = OpenAiProvider(cfg(ProviderType.DEEPSEEK)).buildBody(req)
        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertEquals("thinking step by step", assistant["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun customDoesNotSendReasoningContent() {
        val req = AiRequest(
            model = "test-model", system = "Be helpful",
            messages = listOf(
                AiMessage(Role.USER, text = "Hi"),
                AiMessage(Role.ASSISTANT, text = "Hello", reasoningText = "thinking step by step", reasoningProvider = "deepseek"),
            ),
            tools = toolDefs(), maxOutputTokens = 8192, metadata = MetadataParams(),
        )
        val body = OpenAiProvider(cfg(ProviderType.CUSTOM)).buildBody(req)
        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertNull(assistant["reasoning_content"])
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
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(ProviderType.CLAUDE, model = "claude-opus-5"),
            useCache = false,
        )
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

    // ---- Claude extended thinking ----

    @Test
    fun claudeThinkingModeForClassifiesModels() {
        assertEquals(ClaudeThinkingMode.MANUAL, claudeThinkingModeFor("claude-opus-4-5"))
        assertEquals(ClaudeThinkingMode.MANUAL, claudeThinkingModeFor("claude-3-5-sonnet"))
        assertEquals(ClaudeThinkingMode.ADAPTIVE, claudeThinkingModeFor("claude-sonnet-4-6"))
        assertEquals(ClaudeThinkingMode.ADAPTIVE, claudeThinkingModeFor("claude-opus-4-8"))
        assertEquals(ClaudeThinkingMode.ADAPTIVE_ALWAYS_ON, claudeThinkingModeFor("claude-opus-5"))
        assertEquals(ClaudeThinkingMode.ADAPTIVE_ALWAYS_ON, claudeThinkingModeFor("claude-sonnet-5-5"))
        assertEquals(ClaudeThinkingMode.ADAPTIVE, claudeThinkingModeFor("unknown-model"))
    }

    // ---- Claude effort theo model (Đợt provider 12/11/2026, Q6) ----

    @Test
    fun claudeEffortSupportForClassifiesModels() {
        assertEquals(ClaudeEffortSupport.FULL, claudeEffortSupportFor("claude-opus-5"))
        assertEquals(ClaudeEffortSupport.FULL, claudeEffortSupportFor("claude-sonnet-5-5"))
        assertEquals(ClaudeEffortSupport.FULL, claudeEffortSupportFor("claude-haiku-5-5"))
        assertEquals(ClaudeEffortSupport.FULL, claudeEffortSupportFor("claude-opus-4-8"))
        assertEquals(ClaudeEffortSupport.NO_XHIGH, claudeEffortSupportFor("claude-opus-4-6"))
        assertEquals(ClaudeEffortSupport.NO_XHIGH, claudeEffortSupportFor("claude-sonnet-4-6"))
        assertEquals(ClaudeEffortSupport.NO_XHIGH, claudeEffortSupportFor("claude-mythos-preview"))
        assertEquals(ClaudeEffortSupport.LOW_MED_HIGH, claudeEffortSupportFor("claude-opus-4-5"))
        assertEquals(ClaudeEffortSupport.UNSUPPORTED, claudeEffortSupportFor("claude-sonnet-4-5"))
        assertEquals(ClaudeEffortSupport.UNSUPPORTED, claudeEffortSupportFor("claude-haiku-4-5"))
        assertEquals(ClaudeEffortSupport.UNSUPPORTED, claudeEffortSupportFor("claude-3-5-sonnet"))
        assertEquals(ClaudeEffortSupport.FULL, claudeEffortSupportFor("unknown-model"))
    }

    @Test
    fun claudeEffortNotSentForUnsupportedModels() {
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(ProviderType.CLAUDE, model = "claude-sonnet-4-5"),
            useCache = false,
        )
        assertNull(body["output_config"])
    }

    @Test
    fun claudeEffortClampedForOpus45AndOpus46() {
        val max45 = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(
                ProviderType.CLAUDE,
                metadata = MetadataParams(effort = ReasoningEffort.MAX),
                model = "claude-opus-4-5",
            ),
            useCache = false,
        )
        assertEquals("high", max45["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        val xhigh46 = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(
                ProviderType.CLAUDE,
                metadata = MetadataParams(effort = ReasoningEffort.XHIGH),
                model = "claude-opus-4-6",
            ),
            useCache = false,
        )
        assertEquals("high", xhigh46["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        val max46 = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(
                ProviderType.CLAUDE,
                metadata = MetadataParams(effort = ReasoningEffort.MAX),
                model = "claude-sonnet-4-6",
            ),
            useCache = false,
        )
        assertEquals("max", max46["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun claudeEffortFullLadderForOpus5() {
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(
                ProviderType.CLAUDE,
                metadata = MetadataParams(effort = ReasoningEffort.XHIGH),
                model = "claude-opus-5",
            ),
            useCache = false,
        )
        assertEquals("xhigh", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun claudeManualThinkingSendsBudget() {
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(
                ProviderType.CLAUDE,
                metadata = MetadataParams(thinkingEnabled = true, thinkingBudget = 4096),
                model = "claude-opus-4-5",
            ),
            useCache = false,
        )
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]?.jsonPrimitive?.content)
        assertEquals(4096, thinking["budget_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("summarized", thinking["display"]?.jsonPrimitive?.content)
    }

    @Test
    fun claudeAdaptiveThinkingOmitsBudget() {
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(
                ProviderType.CLAUDE,
                metadata = MetadataParams(thinkingEnabled = true),
                model = "claude-sonnet-4-6",
            ),
            useCache = false,
        )
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("adaptive", thinking["type"]?.jsonPrimitive?.content)
        assertEquals("summarized", thinking["display"]?.jsonPrimitive?.content)
        assertNull(thinking["budget_tokens"])
    }

    @Test
    fun claudeThinkingOffSendsNothing() {
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
            request(ProviderType.CLAUDE, model = "claude-opus-4-5"),
            useCache = false,
        )
        assertNull(body["thinking"])
    }

    @Test
    fun claudeBudgetTooSmallThrows() {
        try {
            ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
                request(
                    ProviderType.CLAUDE,
                    metadata = MetadataParams(thinkingEnabled = true, thinkingBudget = 512),
                    model = "claude-opus-4-5",
                ),
                useCache = false,
            )
            org.junit.Assert.fail("expected AiException for budget < 1024")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("1024"))
        }
    }

    @Test
    fun claudeBudgetAtLeastMaxTokensThrows() {
        try {
            ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(
                request(
                    ProviderType.CLAUDE,
                    metadata = MetadataParams(thinkingEnabled = true, thinkingBudget = 8192),
                    model = "claude-opus-4-5",
                ),
                useCache = false,
            )
            org.junit.Assert.fail("expected AiException for budget >= max_tokens")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("maximum reply length"))
        }
    }

    @Test
    fun claudeThinkingBlocksReplayedVerbatim() {
        val replay = """[{"type":"thinking","thinking":"let me think","signature":"sig1"},{"type":"redacted_thinking","data":"xyz"}]"""
        val req = AiRequest(
            model = "claude-opus-4-5", system = "Be helpful",
            messages = listOf(
                AiMessage(Role.USER, text = "Hi"),
                AiMessage(Role.ASSISTANT, text = "Answer", reasoningReplay = replay, reasoningProvider = "anthropic"),
            ),
            tools = toolDefs(), maxOutputTokens = 8192,
            metadata = MetadataParams(thinkingEnabled = true),
        )
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(req, useCache = false)
        val content = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(3, content.size)
        val thinking = content[0].jsonObject
        assertEquals("thinking", thinking["type"]?.jsonPrimitive?.content)
        assertEquals("let me think", thinking["thinking"]?.jsonPrimitive?.content)
        assertEquals("sig1", thinking["signature"]?.jsonPrimitive?.content)
        val redacted = content[1].jsonObject
        assertEquals("redacted_thinking", redacted["type"]?.jsonPrimitive?.content)
        assertEquals("xyz", redacted["data"]?.jsonPrimitive?.content)
        assertEquals("text", content[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("Answer", content[2].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun claudeThinkingNotReplayedWhenOff() {
        val replay = """[{"type":"thinking","thinking":"let me think","signature":"sig1"}]"""
        val req = AiRequest(
            model = "claude-opus-4-5", system = "Be helpful",
            messages = listOf(
                AiMessage(Role.USER, text = "Hi"),
                AiMessage(Role.ASSISTANT, text = "Answer", reasoningReplay = replay, reasoningProvider = "anthropic"),
            ),
            tools = toolDefs(), maxOutputTokens = 8192,
            metadata = MetadataParams(thinkingEnabled = false),
        )
        val body = ClaudeProvider(cfg(ProviderType.CLAUDE)).buildBody(req, useCache = false)
        val content = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
    }
}
