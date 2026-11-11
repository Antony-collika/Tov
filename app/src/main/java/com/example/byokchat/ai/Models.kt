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
     * Metadata của thẻ Metadata (Đợt 6): effort, stop sequences, tool
     * choice, structured output, service tier. Model/max tokens/stream
     * không nằm ở đây: model là lựa chọn toàn cục, max tokens theo từng
     * provider (ProviderConfig.maxOutputTokens), stream luôn bật.
     * KHÔNG có temperature/top_p/top_k — xem Metadata.kt.
     */
    val metadata: MetadataParams = MetadataParams(),
    /**
     * True khi tool đã bị lược khỏi [tools] RIÊNG vì reasoning đang bật
     * với OpenAI(-compatible) — để Inspector báo cho user biết, thay vì
     * lặng lẽ gửi request không có tool (xem MetadataParams).
     */
    val toolsSuppressedByReasoning: Boolean = false,
)

sealed interface AiEvent {
    data class Text(val delta: String) : AiEvent
    data class ToolCalls(val calls: List<AiToolCall>) : AiEvent
}

class AiException(message: String) : Exception(message)

interface AiProvider {
    fun stream(request: AiRequest): Flow<AiEvent>
    suspend fun listModels(): List<String>
}
