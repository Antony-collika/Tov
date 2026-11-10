package com.example.byokchat.chat

import com.example.byokchat.ai.AiMessage
import com.example.byokchat.ai.ToolDef
import com.example.byokchat.data.MessageEntity

/** Nguồn của một phần trong system prompt, để Inspector (Đợt B) ghi nhãn. */
enum class SystemSource { USER_PROMPT, APP_ADDED }

/** Một phần của system prompt kèm nguồn gốc. */
data class SystemPart(val text: String, val source: SystemSource)

/** Một tin trong request kèm cờ gửi/bị cắt và lý do (nếu bị cắt). */
data class AssembledMessage(
    val message: AiMessage,
    val sent: Boolean,
    val dropReason: String? = null,
)

/** Kết quả ghép request: đúng thứ sẽ gửi cho AI, kèm nhãn để kiểm tra. */
data class AssembledRequest(
    val system: String,
    val systemParts: List<SystemPart>,
    /** Mọi tin đã chuyển đổi, theo thứ tự gửi: lượt bị cắt đứng trước, kèm cờ. */
    val messages: List<AssembledMessage>,
    val droppedTurnIds: List<String>,
    val totalTurns: Int,
    val toolDefs: List<ToolDef>,
    /** Token ước lượng của toàn bộ request (system + công cụ + lịch sử giữ lại). */
    val estimatedTokens: Int,
    /** Token ước lượng của phần lịch sử giữ lại. */
    val historyTokens: Int,
) {
    /** Chỉ các tin thật sự gửi đi, theo thứ tự gửi. */
    val sentMessages: List<AiMessage> get() = messages.filter { it.sent }.map { it.message }
}

/**
 * Ghép request từ đầu vào ĐÃ resolve sẵn — hàm thuần, không đọc DB, không
 * gọi mạng, không gọi registry.definitions() (hàm đó làm I/O qua
 * refreshIfStale). Cả hai nơi dùng request (ChatEngine.run và
 * ChatViewModel.computeTokenStats) đều qua đây, nên "xem trước", "gửi thật"
 * và "đếm token" không lệch nhau được nữa.
 */
object RequestAssembler {
    fun assemble(
        systemPrompt: String,
        rows: List<MessageEntity>,
        toolDefs: List<ToolDef>,
        budget: Int,
        nameForUid: (String) -> String?,
    ): AssembledRequest {
        val plan = ContextBuilder.plan(rows, budget, nameForUid)
        val systemParts = if (systemPrompt.isBlank()) emptyList()
        else listOf(SystemPart(systemPrompt, SystemSource.USER_PROMPT))
        val system = systemParts.joinToString("\n\n") { it.text }
        val dropped = plan.droppedMessages.map {
            AssembledMessage(it, sent = false, dropReason = "over budget")
        }
        val sent = plan.messages.map { AssembledMessage(it, sent = true) }
        val toolTokens = toolDefs.sumOf {
            TokenEstimator.text(it.name + it.description + it.schema.toString())
        }
        return AssembledRequest(
            system = system,
            systemParts = systemParts,
            messages = dropped + sent,
            droppedTurnIds = plan.droppedTurnIds,
            totalTurns = plan.totalTurns,
            toolDefs = toolDefs,
            estimatedTokens = TokenEstimator.text(system) + toolTokens + plan.usedTokens,
            historyTokens = plan.usedTokens,
        )
    }
}
