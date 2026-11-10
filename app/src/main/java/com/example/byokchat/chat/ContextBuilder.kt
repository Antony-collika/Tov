package com.example.byokchat.chat

import com.example.byokchat.ai.AiMessage
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.Role
import com.example.byokchat.data.ContextLimits
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.images
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class ContextPlan(
    val messages: List<AiMessage>,
    val usedTokens: Int,
    val totalTurns: Int,
    val droppedTurns: Int,
    /** ID các lượt bị cắt (cũ nhất trước), để Đợt B hiện lượt nào bị cắt chứ không chỉ đếm số lượt. */
    val droppedTurnIds: List<String> = emptyList(),
    /** Tin của các lượt bị cắt (cũ nhất trước), để Inspector hiện cả phần bị cắt. */
    val droppedMessages: List<AiMessage> = emptyList(),
    /** Lý do các lượt bị cắt (vd "over budget", "outside last 10 turns"). */
    val dropReason: String = "over budget",
)

/**
 * Biến các dòng trong cơ sở dữ liệu thành tin nhắn gửi cho AI:
 * - luôn dùng nội dung hiện tại (đã sửa tay nếu có)
 * - bỏ các lượt lỗi / đang dở
 * - tên công cụ được ánh xạ lại theo UID (đổi tên công cụ không làm hỏng lịch sử)
 * - cắt các lượt cũ nhất khi vượt giới hạn ngữ cảnh (cắt nguyên lượt, luôn giữ lượt mới nhất)
 */
object ContextBuilder {
    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    /** Kết quả tool trong lịch sử dài hơn ngần này ký tự thì rút gọn thành một dòng (thẻ History). */
    const val COMPACT_TOOL_RESULT_CHARS = 500

    fun budget(cfg: ProviderConfig, model: String, system: String, toolTokens: Int): Int {
        val limit = ContextLimits.forModel(cfg, model)
        val reserve = cfg.maxOutputTokens + TokenEstimator.text(system) + toolTokens + 400
        return maxOf(limit - reserve, limit / 4)
    }

    fun plan(
        rows: List<MessageEntity>,
        budget: Int,
        nameForUid: (String) -> String?,
        /** Rút gọn kết quả tool dài trong các lượt nhận được (thẻ History, Đợt C). */
        compactToolResults: Boolean = false,
        /** Có gửi lượt lỗi (assistant status "error") hay không (thẻ History). */
        includeErrorTurns: Boolean = false,
        /** Chỉ giữ N lượt gần nhất (chính sách LAST_N của thẻ History). */
        maxTurns: Int? = null,
        /** Lý do ghi cho các lượt bị cắt. */
        dropReason: String = "over budget",
    ): ContextPlan {
        val turns = LinkedHashMap<String, MutableList<MessageEntity>>()
        rows.forEach { turns.getOrPut(it.turnId) { mutableListOf() }.add(it) }
        val converted = turns.entries
            .map { (turnId, rs) -> turnId to sanitize(rs.mapNotNull { r -> toAi(r, nameForUid, includeErrorTurns) }) }
            .filter { it.second.isNotEmpty() }
        val compacted = if (!compactToolResults) converted else converted.mapIndexed { index, (turnId, msgs) ->
            turnId to msgs.map { m ->
                if (m.role == Role.TOOL && m.text.length > COMPACT_TOOL_RESULT_CHARS) {
                    m.copy(text = ModelFacingText.compactedToolResult(m.toolName ?: "tool", m.text.length, index + 1))
                } else m
            }
        }

        val keep = ArrayDeque<Pair<String, List<AiMessage>>>()
        var used = 0
        for ((turnId, turn) in compacted.asReversed()) {
            val cost = turn.sumOf { TokenEstimator.message(it) }
            if (keep.isNotEmpty() && used + cost > budget) break
            keep.addFirst(turnId to turn)
            used += cost
        }
        if (maxTurns != null) {
            while (keep.size > maxTurns) {
                val removed = keep.removeFirst()
                used -= removed.second.sumOf { TokenEstimator.message(it) }
            }
        }
        val keptIds = keep.map { it.first }.toSet()
        val dropped = compacted.filter { (turnId, _) -> turnId !in keptIds }
        return ContextPlan(
            messages = keep.flatMap { it.second },
            usedTokens = used,
            totalTurns = compacted.size,
            droppedTurns = compacted.size - keep.size,
            droppedTurnIds = dropped.map { it.first },
            droppedMessages = dropped.flatMap { it.second },
            dropReason = dropReason,
        )
    }

    private fun toAi(m: MessageEntity, nameForUid: (String) -> String?, includeErrorTurns: Boolean = false): AiMessage? = when (m.role) {
        "user" -> AiMessage(Role.USER, text = m.text, imagePaths = m.images())
        "assistant" -> {
            if (m.status == "error" && includeErrorTurns) {
                AiMessage(Role.ASSISTANT, text = m.text.ifBlank { m.error ?: "" })
            } else if (m.status != "done") null
            else {
                val calls = m.toolCallsJson
                    ?.let { runCatching { json.decodeFromString(callsSer, it) }.getOrNull() }
                    .orEmpty()
                    .map { c -> c.uid?.takeIf { it.isNotEmpty() }?.let(nameForUid)?.let { n -> c.copy(name = n) } ?: c }
                AiMessage(Role.ASSISTANT, text = m.text, toolCalls = calls)
            }
        }
        "tool" -> AiMessage(
            Role.TOOL,
            text = m.text,
            toolCallId = m.toolCallId,
            toolName = m.toolUid?.takeIf { it.isNotEmpty() }?.let(nameForUid) ?: m.toolName,
            isError = m.toolIsError,
        )
        else -> null
    }

    /** Gọi công cụ mà không có kết quả (hoặc ngược lại) sẽ làm API báo lỗi, nên loại bỏ phần lẻ. */
    private fun sanitize(msgs: List<AiMessage>): List<AiMessage> {
        val resultIds = msgs.filter { it.role == Role.TOOL }.mapNotNull { it.toolCallId }.toSet()
        val withCalls = msgs.mapNotNull { m ->
            if (m.role != Role.ASSISTANT) m
            else {
                val c = m.toolCalls.filter { it.id in resultIds }
                val fixed = m.copy(toolCalls = c)
                if (fixed.text.isBlank() && c.isEmpty()) null else fixed
            }
        }
        val callIds = withCalls.flatMap { it.toolCalls }.map { it.id }.toSet()
        return withCalls.filter { it.role != Role.TOOL || it.toolCallId in callIds }
    }
}
