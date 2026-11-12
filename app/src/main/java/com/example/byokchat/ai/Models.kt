package com.example.byokchat.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class Role { USER, ASSISTANT, TOOL }

@Serializable
data class AiToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
    /** Chỉ Gemini dùng (chữ ký suy luận phải gửi lại nguyên vẹn). */
    val signature: String? = null,
    /** UID cố định của công cụ (do app đặt). Lịch sử lưu theo UID nên đổi tên công cụ không làm hỏng cuộc trò chuyện cũ. */
    val uid: String? = null,
)

data class AiMessage(
    val role: Role,
    val text: String = "",
    val imagePaths: List<String> = emptyList(),
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
    /**
     * Chữ ký suy luận của Gemini cho phần chữ của tin assistant này (Đợt fix
     * 12/11/2026) — gửi lại nguyên vẹn ở request sau khi công tắc
     * [MetadataParams.keepThoughtSignature] bật. Chỉ Gemini dùng.
     */
    val thoughtSignature: String? = null,
    /**
     * Suy luận đọc được của tin assistant này, để hiện trong khối "Suy luận"
     * dưới tin nhắn và trong Preview (Đợt provider 12/11/2026):
     * - OpenAI Responses: tóm tắt suy luận (reasoning summary);
     * - Custom (máy chủ tương thích, vd DeepSeek): reasoning_content
     *   (chuỗi suy luận dạng text).
     * Null = tin này không có suy luận (hoặc provider không trả về).
     */
    val reasoningText: String? = null,
    /**
     * Dữ liệu gửi lại nguyên vẹn ở request sau (Đợt provider 12/11/2026),
     * dạng JSON — hãng nào chỉ hãng đó đọc (xem [reasoningProvider]):
     * - OpenAI Responses: mảng output items (reasoning + message +
     *   function_call, kèm encrypted_content) đúng như API trả về;
     * - Custom: null — gửi lại dùng [reasoningText] làm reasoning_content.
     */
    val reasoningReplay: String? = null,
    /**
     * Hãng đã sinh ra suy luận của tin này ("openai" / "custom" /
     * "deepseek" — "deepseek" là dữ liệu cũ, nay DeepSeek dùng qua Custom),
     * để provider ở lượt sau biết [reasoningReplay] có dùng được không.
     * Null = không có suy luận cần gửi lại.
     */
    val reasoningProvider: String? = null,
)

data class ToolDef(
    val name: String,
    val description: String,
    val schema: JsonObject,
)

data class AiRequest(
    val model: String,
    val system: String,
    val messages: List<AiMessage>,
    val tools: List<ToolDef>,
    val maxOutputTokens: Int,
    /**
     * Metadata của thẻ Metadata (Đợt 6): effort, stop sequences. Model/max
     * tokens/stream không nằm ở đây: model là lựa chọn toàn cục, max tokens
     * theo từng provider (ProviderConfig.maxOutputTokens), stream luôn bật.
     * KHÔNG có temperature/top_p/top_k — xem Metadata.kt.
     */
    val metadata: MetadataParams = MetadataParams(),
    /**
     * Đợt fix 11/11/2026 (F1/F2): chat + số lượt của request này — provider
     * ghi vào request log để log theo từng chat. null = không biết (xem
     * trước — preview không ghi log — hoặc test).
     */
    val sessionId: String? = null,
    val turnNo: Int? = null,
)

sealed interface AiEvent {
    /**
     * Một đoạn chữ của câu trả lời. [signature] là chữ ký suy luận của Gemini
     * kèm theo phần chữ này (nếu có) — Đợt fix 12/11/2026, chỉ Gemini gửi.
     */
    data class Text(val delta: String, val signature: String? = null) : AiEvent
    /**
     * Một đoạn suy luận đọc được (Đợt provider 12/11/2026) — KHÔNG phải câu
     * trả lời, không hiện trong bong bóng chat: Responses thì là
     * reasoning_summary_text.delta, Custom (máy chủ tương thích) thì là
     * delta reasoning_content. ChatEngine tích lũy riêng rồi lưu vào
     * tin assistant (hiện ở khối "Suy luận").
     */
    data class Reasoning(val delta: String) : AiEvent
    /**
     * Dữ liệu gửi lại nguyên vẹn ở request sau (Đợt provider 12/11/2026),
     * phát MỘT lần cuối stream — provider nào cũng tự biết dạng JSON của
     * mình (Responses: mảng output items).
     * ChatEngine lưu vào tin assistant cùng hãng đã sinh ra nó.
     */
    data class Replay(val json: String) : AiEvent
    data class ToolCalls(val calls: List<AiToolCall>) : AiEvent
}

class AiException(message: String) : Exception(message)

interface AiProvider {
    fun stream(request: AiRequest): Flow<AiEvent>
    suspend fun listModels(): List<String>
}
