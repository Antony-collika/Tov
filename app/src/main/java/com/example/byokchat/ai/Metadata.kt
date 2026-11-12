package com.example.byokchat.ai

import com.example.byokchat.data.ProviderType
import kotlinx.serialization.Serializable

/**
 * Mức suy luận chung cho thẻ Metadata (Đợt 6), thay cho temperature/top_p/
 * top_k — các hãng đều đã bỏ/vô hiệu các núm lấy mẫu cổ điển trên model
 * suy luận hiện tại (báo cáo API 11/11/2026):
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
 * Mặc định effort là MEDIUM cho mọi hãng. App KHÔNG tự đoán model có suy
 * luận hay không: danh sách model là chuỗi do user nhập, không có metadata
 * về khả năng của model — user đổi effort khi dùng model không suy luận.
 *
 * Luật effort × tools — LÀM THEO TỪNG HÃNG (quyết định của Duy 12/11/2026,
 * thay thế luật ép "none" chung của đợt fix 11/11/2026, D3):
 * - OpenAI chính hãng: dùng Responses API — reasoning + tool dùng chung
 *   được ở mọi effort, nên KHÔNG ép "none" nữa; effort gửi là
 *   reasoning.effort. Ngoại lệ: GPT-6 Astra / GPT-6.1 Sol không chấp nhận
 *   "none" (tài liệu OpenAI) — user chọn NONE với model đó thì app báo rõ
 *   (xem [responsesEffortProblem]), không tự đổi lặng lẽ.
 * - DeepSeek (Chat Completions): thinking + tool dùng chung được; effort
 *   gửi là reasoning_effort, kẹp về low/high/max (DeepSeek chỉ có 3 mức
 *   đó + none để tắt). Không ép "none".
 * - Custom (Chat Completions): effort của user gửi nguyên vẹn dạng
 *   reasoning_effort (none/minimal/low/medium/high/xhigh/max); hãng đích
 *   tự quyết — nếu hãng đó từ chối thì API báo lỗi rõ, app không tự đổi.
 * - Gemini: thinkingLevel + function calling dùng chung được.
 * Vì vậy KHÔNG còn khái niệm "tool bị tắt vì reasoning" và KHÔNG còn ép
 * effort chung cho mọi hãng: tool luôn được gửi, với mọi hãng.
 */
@Serializable
data class MetadataParams(
    val effort: ReasoningEffort = ReasoningEffort.MEDIUM,
    /** Chuỗi dừng, tối đa 4 (giới hạn chặt nhất đã biết — OpenAI stop ≤ 4). Rỗng = không gửi. */
    val stopSequences: List<String> = emptyList(),
    /**
     * Giữ chữ ký suy luận của Gemini cho phần chữ trả lời (Đợt fix 12/11/2026):
     * BẬT = gửi lại chữ ký ở request sau (mạch suy luận liền mạch hơn);
     * TẮT = không gửi chữ ký của phần chữ. Chữ ký của LỆNH GỌI tool thì LUÔN
     * gửi lại (Gemini bắt buộc, thiếu là lỗi 400) — công tắc này không ảnh
     * hưởng tới chúng. Mặc định BẬT.
     */
    val keepThoughtSignature: Boolean = true,
) {
    /**
     * Effort thật sự áp cho hãng này. Từ đợt provider 12/11/2026: KHÔNG ép
     * về NONE cho hãng nào nữa — mỗi hãng nhận đúng lựa chọn của user, map
     * sang trường riêng của mình (xem [effortValueFor]). Giá trị user đã
     * chọn giữ nguyên trong [effort].
     */
    fun effectiveEffort(type: ProviderType): ReasoningEffort = effort

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

    /** Các mức effort cho user chọn theo hãng đang dùng (giá trị hãng không có thì không hiện). */
    fun effortOptionsFor(type: ProviderType?): List<ReasoningEffort> = when (type) {
        ProviderType.OPENAI, ProviderType.CUSTOM ->
            listOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX)
        ProviderType.DEEPSEEK ->
            // DeepSeek chỉ có low/high/max (+ none để tắt): medium kẹp về high.
            listOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX)
        ProviderType.GEMINI ->
            listOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
        null -> ReasoningEffort.entries.toList()
    }

    /** Tên trường effort mà hãng này nhận, để Inspector hiện đúng (null = hãng không nhận trường effort nào khi NONE). */
    fun effortFieldName(type: ProviderType): String = when (type) {
        ProviderType.OPENAI -> "reasoning.effort"
        ProviderType.CUSTOM, ProviderType.DEEPSEEK -> "reasoning_effort"
        ProviderType.GEMINI -> "thinkingLevel"
    }

    /**
     * Giá trị effort đã map cho hãng này (null = không gửi trường effort).
     * OpenAI (Responses): reasoning.effort theo đúng lựa chọn của user —
     * không ép "none" nữa (Responses cho reasoning + tool dùng chung).
     * Custom/DeepSeek (Chat Completions): reasoning_effort theo lựa chọn
     * của user; DeepSeek kẹp về low/high/max.
     */
    fun effortValueFor(type: ProviderType, model: String = ""): String? = when (type) {
        ProviderType.OPENAI -> responsesEffort()
        ProviderType.CUSTOM -> chatEffort()
        ProviderType.DEEPSEEK -> deepSeekEffort()
        ProviderType.GEMINI -> geminiThinkingLevel()
    }

    /**
     * OpenAI Responses: reasoning.effort. NONE gửi "none" (model chấp nhận
     * — trừ Astra/Sol, xem [responsesEffortProblem]).
     */
    fun responsesEffort(): String = when (effort) {
        ReasoningEffort.NONE -> "none"
        ReasoningEffort.MINIMAL -> "minimal"
        ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM -> "medium"
        ReasoningEffort.HIGH -> "high"
        ReasoningEffort.XHIGH -> "xhigh"
        ReasoningEffort.MAX -> "max"
    }

    /** Chat Completions chung (Custom): tên mức của OpenAI, gửi nguyên vẹn. */
    fun chatEffort(): String = responsesEffort()

    /**
     * DeepSeek: reasoning_effort chỉ có low/high/max (+ none để tắt suy
     * luận). MINIMAL kẹp về low; MEDIUM/XHIGH kẹp về high (theo tài liệu
     * DeepSeek: minimal/low → low, medium/high/xhigh → high, max → max).
     */
    fun deepSeekEffort(): String = when (effort) {
        ReasoningEffort.NONE -> "none"
        ReasoningEffort.MINIMAL -> "low"
        ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM -> "high"
        ReasoningEffort.HIGH -> "high"
        ReasoningEffort.XHIGH -> "high"
        ReasoningEffort.MAX -> "max"
    }

    /**
     * Model OpenAI nào không chấp nhận effort "none" thì trả về câu giải
     * thích để báo user (đợt provider 12/11/2026, theo tài liệu OpenAI):
     * GPT-6 Astra không chấp nhận "none" (400); GPT-6.1 Sol cũng không
     * chấp nhận "none". Trả null khi không có vấn đề (effort khác NONE,
     * hoặc model không thuộc hai dòng đó).
     */
    fun responsesEffortProblem(model: String): String? {
        if (effort != ReasoningEffort.NONE) return null
        val m = model.lowercase()
        val rejectsNone = "astra" in m || Regex("""\bsol\b""").containsMatchIn(m)
        if (!rejectsNone) return null
        return "This model ($model) does not accept reasoning effort \"none\". " +
            "Choose a higher effort, or another model."
    }

    /** Dòng tóm tắt cho thẻ Metadata trên Panel: "model · effort medium · max 8192". */
    fun summary(model: String, maxOutputTokens: Int, type: ProviderType? = null): String {
        // Chưa biết hãng thì hiện effort đã lưu; đã biết hãng thì hiện
        // effort thật sự áp cho hãng đó (từ đợt provider 12/11/2026: đúng
        // lựa chọn của user cho mọi hãng, không ép "none" nữa).
        val shown = if (type == null) effort else effectiveEffort(type)
        return "${model.ifBlank { "no model" }} · effort ${shown.name.lowercase()} · max $maxOutputTokens"
    }

    companion object {
        /**
         * Mặc định Metadata theo hãng đang dùng: MEDIUM cho mọi hãng (đợt
         * provider 12/11/2026 — luật ép NONE cho OpenAI(-compatible) đã bỏ
         * vì Responses/DeepSeek đều cho reasoning + tool dùng chung).
         * Chỉ dùng khi user chưa từng lưu Metadata — giá trị user đã chọn
         * luôn được tôn trọng (xem SettingsRepository).
         */
        fun defaultFor(type: ProviderType?): MetadataParams = MetadataParams(
            effort = ReasoningEffort.MEDIUM,
        )
    }
}
