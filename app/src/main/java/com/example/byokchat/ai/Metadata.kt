package com.example.byokchat.ai

import com.example.byokchat.data.ProviderType
import kotlinx.serialization.Serializable

/**
 * Mức suy luận chung cho thẻ Metadata (Đợt 6), thay cho temperature/top_p/
 * top_k — cả ba hãng đều đã bỏ/vô hiệu các núm lấy mẫu cổ điển trên model
 * suy luận hiện tại (báo cáo API 11/11/2026):
 * - Anthropic gỡ hẳn temperature/top_p/top_k từ API version 2026-10-21
 *   (gửi kèm là lỗi 400, kể cả giá trị mặc định) nên app KHÔNG BAO GIỜ
 *   gửi chúng.
 * - Gemini mới nhất không còn hỗ trợ temperature/topK/topP/penalty/
 *   candidateCount.
 * - OpenAI dùng max_completion_tokens thay max_tokens đã deprecated.
 *
 * Mỗi hãng map mức chung này sang trường riêng của mình (xem các hàm dưới);
 * giá trị nào hãng không có thì kẹp về giá trị gần nhất và ghi rõ ở đây,
 * không gửi bừa.
 */
@Serializable
enum class ReasoningEffort { NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }

/**
 * Các giá trị Metadata mới của thẻ Metadata (Đợt 6), lưu TOÀN CỤC ở
 * SettingsRepository (trừ max output tokens vẫn theo từng provider như cũ,
 * ở ProviderConfig.maxOutputTokens; stream luôn bật nên không lưu).
 *
 * Mặc định effort theo hãng (quyết định 15, phương án c — xem
 * [defaultFor]): NONE cho OpenAI(-compatible) vì luật tool của OpenAI,
 * MEDIUM cho Claude/Gemini. App KHÔNG tự đoán model có suy luận hay
 * không: danh sách model là chuỗi do user nhập, không có metadata về
 * khả năng của model — user đổi effort khi dùng model không suy luận.
 *
 * Luật effort × tools (đợt fix 11/11/2026, D3 — đã kiểm chứng lại với
 * tài liệu chính thức ngày 11/11/2026, xem [effectiveEffort]):
 * - OpenAI Chat Completions: model suy luận KHÔNG gọi được tool khi
 *   reasoning đang bật (kể cả không gửi reasoning_effort, vì server mặc
 *   định medium — lỗi 400 "Function tools with reasoning_effort are not
 *   supported ... use /v1/responses or set reasoning_effort to 'none'").
 *   Astra/6.1 Sol/6.1 Terra còn cần Responses API cho tool. Tov chỉ dùng
 *   Chat Completions nên với OpenAI(-compatible) effort LUÔN bị ép về
 *   NONE — tool luôn dùng được, user không phải đoán.
 * - Claude: thinking + tool dùng chung được (chỉ không ép tool khi thinking
 *   bật); Gemini: thinkingLevel + function calling dùng chung được.
 *   Hai hãng này hiện lựa chọn effort bình thường.
 * Vì vậy KHÔNG còn khái niệm "tool bị tắt vì reasoning": tool luôn được
 * gửi, với mọi hãng.
 */
@Serializable
data class MetadataParams(
    val effort: ReasoningEffort = ReasoningEffort.MEDIUM,
    /** Chuỗi dừng, tối đa 4 (giới hạn chặt nhất đã biết — OpenAI stop ≤ 4). Rỗng = không gửi. */
    val stopSequences: List<String> = emptyList(),
) {
    /**
     * Effort thật sự áp cho hãng này. OpenAI(-compatible): LUÔN NONE —
     * Chat Completions chỉ gọi được tool khi reasoning_effort "none", và
     * tool luôn được ưu tiên hơn thinking (quyết định của Duy, D3). Giá trị
     * user đã chọn vẫn giữ trong [effort] để khi đổi sang Claude/Gemini
     * thì dùng lại, không mất.
     */
    fun effectiveEffort(type: ProviderType): ReasoningEffort =
        if (type == ProviderType.OPENAI || type == ProviderType.CUSTOM) ReasoningEffort.NONE else effort

    /**
     * Gemini thinkingLevel. NONE = không gửi trường này (model cũ dùng mặc
     * định của hãng; Gemini 4 không tắt được thinking nên server dùng mặc
     * định MEDIUM của nó). XHIGH/MAX không có ở Gemini nên kẹp về HIGH.
     * Bắt buộc với Gemini 4 — mặc định MEDIUM nên request thường đã có sẵn.
     */
    fun geminiThinkingLevel(): String? = when (effort) {
        ReasoningEffort.NONE -> null
        ReasoningEffort.MINIMAL -> "MINIMAL"
        ReasoningEffort.LOW -> "LOW"
        ReasoningEffort.MEDIUM -> "MEDIUM"
        ReasoningEffort.HIGH -> "HIGH"
        ReasoningEffort.XHIGH -> "HIGH"
        ReasoningEffort.MAX -> "HIGH"
    }

    /**
     * Claude output_config.effort — tín hiệu mềm, max_tokens vẫn là trần
     * cứng. NONE = không gửi (dùng mặc định của hãng). MINIMAL không có ở
     * Claude nên kẹp về "low".
     */
    fun claudeEffort(): String? = when (effort) {
        ReasoningEffort.NONE -> null
        ReasoningEffort.MINIMAL -> "low"
        ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM -> "medium"
        ReasoningEffort.HIGH -> "high"
        ReasoningEffort.XHIGH -> "xhigh"
        ReasoningEffort.MAX -> "max"
    }

    /** Các mức effort cho user chọn theo hãng đang dùng (giá trị hãng không có thì không hiện). */
    fun effortOptionsFor(type: ProviderType?): List<ReasoningEffort> = when (type) {
        ProviderType.OPENAI, ProviderType.CUSTOM ->
            listOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX)
        ProviderType.GEMINI ->
            listOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
        ProviderType.CLAUDE ->
            listOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX)
        null -> ReasoningEffort.entries.toList()
    }

    /** Tên trường effort mà hãng này nhận, để Inspector hiện đúng (null = hãng không nhận trường effort nào khi NONE). */
    fun effortFieldName(type: ProviderType): String = when (type) {
        ProviderType.OPENAI, ProviderType.CUSTOM -> "reasoning_effort"
        ProviderType.GEMINI -> "thinkingLevel"
        ProviderType.CLAUDE -> "output_config.effort"
    }

    /**
     * Giá trị effort đã map cho hãng này (null = không gửi trường effort).
     * OpenAI(-compatible): LUÔN "none" — ép về NONE để tool luôn dùng
     * được (xem [effectiveEffort]); server mặc định là medium nên bỏ qua
     * trường này cũng đồng nghĩa effort medium.
     */
    fun effortValueFor(type: ProviderType): String? = when (type) {
        ProviderType.OPENAI, ProviderType.CUSTOM -> "none"
        ProviderType.GEMINI -> geminiThinkingLevel()
        ProviderType.CLAUDE -> claudeEffort()
    }

    /** Dòng tóm tắt cho thẻ Metadata trên Panel: "model · effort medium · max 8192". */
    fun summary(model: String, maxOutputTokens: Int, type: ProviderType? = null): String {
        // Chưa biết hãng thì hiện effort đã lưu; đã biết hãng thì hiện
        // effort thật sự áp cho hãng đó (OpenAI luôn "none" — xem effectiveEffort).
        val shown = if (type == null) effort else effectiveEffort(type)
        return "${model.ifBlank { "no model" }} · effort ${shown.name.lowercase()} · max $maxOutputTokens"
    }

    companion object {
        /**
         * Mặc định Metadata theo hãng đang dùng (quyết định 15, phương án
         * c): effort NONE cho OpenAI(-compatible) vì luật tool của OpenAI
         * (effort != NONE thì không gửi được tool — xem [effectiveEffort]),
         * MEDIUM cho Claude/Gemini (effort nào tool cũng gửi được) và khi
         * chưa biết hãng (giữ mặc định cũ).
         * Chỉ dùng khi user chưa từng lưu Metadata — giá trị user đã chọn
         * luôn được tôn trọng (xem SettingsRepository).
         */
        fun defaultFor(type: ProviderType?): MetadataParams = MetadataParams(
            effort = when (type) {
                ProviderType.OPENAI, ProviderType.CUSTOM -> ReasoningEffort.NONE
                else -> ReasoningEffort.MEDIUM
            },
        )
    }
}
