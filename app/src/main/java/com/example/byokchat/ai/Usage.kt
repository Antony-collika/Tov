package com.example.byokchat.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Số token thật mà nhà cung cấp báo trong phản hồi (không phải ước lượng).
 * Mọi trường đều nullable: hãng nào không báo thì để trống, Inspector và
 * Request log hiện "not reported" thay vì đoán.
 */
@Serializable
data class Usage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    /** Số token đầu vào đọc từ bộ nhớ đệm (OpenAI Responses: input_tokens_details.cached_tokens). */
    val cachedTokens: Int? = null,
    /** Số token đầu vào ghi mới vào bộ nhớ đệm (OpenAI Responses: input_tokens_details.cache_write_tokens). */
    val cacheCreationTokens: Int? = null,
    /**
     * Số token suy luận trong số output (Đợt provider 12/11/2026):
     * Responses: usage.output_tokens_details.reasoning_tokens;
     * Chat Completions: completion_tokens_details.reasoning_tokens.
     */
    val reasoningTokens: Int? = null,
) {
    /** Một dòng tóm tắt, kiểu FPilot: "[usage] prompt 1234 · cached 1000 · completion 56". */
    fun summaryLine(): String {
        val parts = mutableListOf<String>()
        inputTokens?.let { parts += "prompt $it" }
        cachedTokens?.let { parts += "cached $it" }
        cacheCreationTokens?.let { parts += "cache write $it" }
        outputTokens?.let { parts += "completion $it" }
        reasoningTokens?.let { parts += "reasoning $it" }
        return if (parts.isEmpty()) "[usage] not reported" else "[usage] " + parts.joinToString(" · ")
    }

    companion object {
        /** OpenAI: `usage` trong chunk cuối khi stream_options.include_usage bật. */
        fun fromOpenAi(usage: JsonObject): Usage = Usage(
            inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull,
            outputTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull,
            cachedTokens = usage["prompt_tokens_details"]?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull,
            reasoningTokens = usage["completion_tokens_details"]?.jsonObject
                ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull,
        )

        /**
         * OpenAI Responses: `usage` trong event response.completed (Đợt
         * provider 12/11/2026). Khác Chat Completions: input_tokens /
         * output_tokens (không phải prompt/completion_tokens), và
         * output_tokens_details.reasoning_tokens.
         */
        fun fromResponses(usage: JsonObject): Usage = Usage(
            inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull,
            outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull,
            cachedTokens = usage["input_tokens_details"]?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull,
            cacheCreationTokens = usage["input_tokens_details"]?.jsonObject
                ?.get("cache_write_tokens")?.jsonPrimitive?.intOrNull,
            reasoningTokens = usage["output_tokens_details"]?.jsonObject
                ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull,
        )

        /** Gemini: `usageMetadata` trong mỗi chunk SSE. */
        fun fromGemini(meta: JsonObject): Usage = Usage(
            inputTokens = meta["promptTokenCount"]?.jsonPrimitive?.intOrNull,
            outputTokens = meta["candidatesTokenCount"]?.jsonPrimitive?.intOrNull,
            cachedTokens = meta["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull,
        )

        /**
         * Gộp hai lần đọc: giá trị mới khác null sẽ thắng (mỗi hãng gửi usage
         * theo từng phần — Gemini gửi số cộng dồn ở mỗi chunk nên lần cuối là số đúng).
         */
        fun merge(first: Usage?, second: Usage?): Usage? {
            if (first == null) return second
            if (second == null) return first
            return Usage(
                inputTokens = second.inputTokens ?: first.inputTokens,
                outputTokens = second.outputTokens ?: first.outputTokens,
                cachedTokens = second.cachedTokens ?: first.cachedTokens,
                cacheCreationTokens = second.cacheCreationTokens ?: first.cacheCreationTokens,
                reasoningTokens = second.reasoningTokens ?: first.reasoningTokens,
            )
        }
    }
}
