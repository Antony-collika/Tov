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

/** Tool choice của thẻ Metadata: auto = model được gọi tool đã offer; none = request này không gọi tool. */
@Serializable
enum class ToolChoiceMode { AUTO, NONE }

/**
 * Service tier chung. OpenAI: auto/default/flex/fast/priority. Claude:
 * auto/standard_only. Gemini KHÔNG có trường này (serviceTier của Gemini
 * chỉ là output) nên không bao giờ gửi tier cho Gemini.
 */
@Serializable
enum class ServiceTier { AUTO, DEFAULT, FLEX, FAST, PRIORITY, STANDARD_ONLY }

/**
 * Các giá trị Metadata mới của thẻ Metadata (Đợt 6), lưu TOÀN CỤC ở
 * SettingsRepository (trừ max output tokens vẫn theo từng provider như cũ,
 * ở ProviderConfig.maxOutputTokens; stream luôn bật nên không lưu).
 *
 * Mặc định effort = MEDIUM. App KHÔNG tự đoán model có suy luận hay không:
 * danh sách model là chuỗi do user nhập, không có metadata về khả năng của
 * model, nên không thể tự chuyển sang NONE cho model thường — user chọn
 * NONE khi dùng model không suy luận (hoặc khi muốn gọi tool với OpenAI,
 * xem [suppressesTools]).
 */
@Serializable
data class MetadataParams(
    val effort: ReasoningEffort = ReasoningEffort.MEDIUM,
    /** Chuỗi dừng, tối đa 4 (giới hạn chặt nhất đã biết — OpenAI stop ≤ 4). Rỗng = không gửi. */
    val stopSequences: List<String> = emptyList(),
    val toolChoice: ToolChoiceMode = ToolChoiceMode.AUTO,
    /** Ép trả JSON. Claude: chưa gửi được (cần schema, chưa có UI schema đợt này) — xem [supportsStructuredOutput]. */
    val structuredOutput: Boolean = false,
    val serviceTier: ServiceTier = ServiceTier.AUTO,
) {
    /**
     * OpenAI LUÔN gửi reasoning_effort (kể cả "none"): server mặc định là
     * medium với hầu hết model suy luận, nên bỏ qua trường này cũng đồng
     * nghĩa effort medium — và tool sẽ bị chặn (xem [suppressesTools]).
     * "minimal" chỉ còn trên model cũ; model GPT-6 không nhận (báo cáo API 11/11/2026).
     */
    fun openAiReasoningEffort(): String = when (effort) {
        ReasoningEffort.NONE -> "none"
        ReasoningEffort.MINIMAL -> "minimal"
        ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM -> "medium"
        ReasoningEffort.HIGH -> "high"
        ReasoningEffort.XHIGH -> "xhigh"
        ReasoningEffort.MAX -> "max"
    }

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

    /** OpenAI service_tier. AUTO = dùng cài đặt của project nên không gửi. */
    fun openAiServiceTier(): String? = when (serviceTier) {
        ServiceTier.AUTO -> null
        ServiceTier.DEFAULT -> "default"
        ServiceTier.FLEX -> "flex"
        ServiceTier.FAST -> "fast"
        ServiceTier.PRIORITY -> "priority"
        ServiceTier.STANDARD_ONLY -> null // chỉ của Claude, không gửi cho OpenAI
    }

    /** Claude service_tier. AUTO = mặc định nên không gửi; tier của OpenAI không gửi cho Claude. */
    fun claudeServiceTier(): String? = when (serviceTier) {
        ServiceTier.AUTO -> null
        ServiceTier.STANDARD_ONLY -> "standard_only"
        else -> null
    }

    /**
     * Request này có gửi tool không. Hai trường hợp KHÔNG gửi:
     * - toolChoice NONE (user tắt tool cho request này — tương đương tắt
     *   thẻ Tools cho request này, thẻ vẫn bật cho lần sau).
     * - Provider OpenAI(-compatible) mà effort != NONE: Chat Completions
     *   chỉ cho gọi tool khi reasoning_effort "none" (từ GPT-5.4); model
     *   Astra/6.1 Sol/6.1 Terra còn cần Responses API cho tool — Tov chỉ
     *   dùng Chat Completions nên không có đường nào khác (báo cáo API
     *   11/11/2026). Đây là hành vi MỚI, cần Duy xác nhận.
     * Gemini/Claude không có xung đột này: effort nào tool cũng gửi được.
     */
    fun suppressesTools(type: ProviderType): Boolean =
        toolChoice == ToolChoiceMode.NONE || toolsSuppressedByReasoning(type)

    /** True khi tool bị tắt RIÊNG vì reasoning đang bật (chỉ OpenAI-compatible). */
    fun toolsSuppressedByReasoning(type: ProviderType): Boolean =
        (type == ProviderType.OPENAI || type == ProviderType.CUSTOM) && effort != ReasoningEffort.NONE

    /** Danh sách tool thật sự gửi đi sau khi áp [suppressesTools]. */
    fun effectiveTools(type: ProviderType, tools: List<ToolDef>): List<ToolDef> =
        if (suppressesTools(type)) emptyList() else tools

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

    /** Các tier cho user chọn theo hãng; null = hãng không có trường này (Gemini) nên ẩn hẳn. */
    fun serviceTierOptionsFor(type: ProviderType?): List<ServiceTier>? = when (type) {
        ProviderType.OPENAI, ProviderType.CUSTOM ->
            listOf(ServiceTier.AUTO, ServiceTier.DEFAULT, ServiceTier.FLEX, ServiceTier.FAST, ServiceTier.PRIORITY)
        ProviderType.CLAUDE -> listOf(ServiceTier.AUTO, ServiceTier.STANDARD_ONLY)
        ProviderType.GEMINI -> null
        null -> listOf(ServiceTier.AUTO, ServiceTier.DEFAULT, ServiceTier.FLEX, ServiceTier.FAST, ServiceTier.PRIORITY, ServiceTier.STANDARD_ONLY)
    }

    /**
     * Structured output có gửi được cho hãng này không. Claude cần
     * output_config.format dạng json_schema KÈM schema — chưa có UI schema
     * đợt này nên KHÔNG gửi cho Claude (ẩn nút ở màn edit); OpenAI dùng
     * response_format json_object, Gemini dùng responseMimeType
     * application/json (không cần schema).
     */
    fun supportsStructuredOutput(type: ProviderType?): Boolean = type != ProviderType.CLAUDE

    /** Tên trường effort mà hãng này nhận, để Inspector hiện đúng (null = hãng không nhận trường effort nào khi NONE). */
    fun effortFieldName(type: ProviderType): String = when (type) {
        ProviderType.OPENAI, ProviderType.CUSTOM -> "reasoning_effort"
        ProviderType.GEMINI -> "thinkingLevel"
        ProviderType.CLAUDE -> "output_config.effort"
    }

    /** Giá trị effort đã map cho hãng này (null = không gửi trường effort). */
    fun effortValueFor(type: ProviderType): String? = when (type) {
        ProviderType.OPENAI, ProviderType.CUSTOM -> openAiReasoningEffort()
        ProviderType.GEMINI -> geminiThinkingLevel()
        ProviderType.CLAUDE -> claudeEffort()
    }

    /** Dòng tóm tắt cho thẻ Metadata trên Panel: "model · effort medium · max 8192 · stream on". */
    fun summary(model: String, maxOutputTokens: Int): String =
        "${model.ifBlank { "no model" }} · effort ${effort.name.lowercase()} · max $maxOutputTokens · stream on"
}
