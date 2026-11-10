package com.example.byokchat.chat

import com.example.byokchat.ai.AiMessage
import com.example.byokchat.ai.Role
import com.example.byokchat.ai.ToolDef
import com.example.byokchat.data.CardKind
import com.example.byokchat.data.FreeRole
import com.example.byokchat.data.HistoryPolicy
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.RequestCard
import com.example.byokchat.data.RequestLayout

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
 *
 * Đợt C: đi từng thẻ trong [RequestLayout] từ trên xuống. Bố cục mặc định
 * cho ra đúng request như trước Đợt C (xem RequestLayout.defaultLayout).
 */
object RequestAssembler {
    /**
     * Đường cũ (trước Đợt C): một system prompt + toàn bộ lịch sử cắt theo
     * ngân sách. Giữ lại để test parity và để nơi chưa có layout dùng được;
     * bên trong nó chỉ là bố cục mặc định.
     */
    fun assemble(
        systemPrompt: String,
        rows: List<MessageEntity>,
        toolDefs: List<ToolDef>,
        budget: Int,
        nameForUid: (String) -> String?,
    ): AssembledRequest = assemble(
        layout = RequestLayout.defaultLayout(systemPrompt),
        rows = rows,
        toolDefs = toolDefs,
        budget = budget,
        nameForUid = nameForUid,
        vars = emptyMap(),
    )

    fun assemble(
        layout: RequestLayout,
        rows: List<MessageEntity>,
        toolDefs: List<ToolDef>,
        budget: Int,
        nameForUid: (String) -> String?,
        vars: Map<String, String>,
    ): AssembledRequest {
        val historyCard = layout.historyCard()
            ?: RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY)
        val lastTurnCard = layout.lastTurnCard()
            ?: RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN)

        // Tách lượt mới nhất (Last turn) khỏi lịch sử: lượt mới nhất luôn được
        // giữ, ngân sách còn lại dành cho lịch sử — đúng như một lần gọi
        // plan() trên toàn bộ trước đây (lượt mới nhất luôn được thêm trước).
        val turnIds = rows.map { it.turnId }.distinct()
        val lastTurnId = turnIds.lastOrNull()
        val historyRows = if (lastTurnId == null) emptyList() else rows.filter { it.turnId != lastTurnId }
        val lastTurnRows = if (lastTurnId == null) emptyList() else rows.filter { it.turnId == lastTurnId }

        val lastPlan = if (lastTurnCard.enabled) {
            ContextBuilder.plan(
                lastTurnRows, Int.MAX_VALUE, nameForUid,
                includeErrorTurns = historyCard.includeErrorTurns,
            )
        } else {
            ContextBuilder.plan(emptyList(), 0, nameForUid)
        }
        val historyBudget = maxOf(0, budget - lastPlan.usedTokens)
        val historyPlan = if (!historyCard.enabled) {
            ContextBuilder.plan(emptyList(), 0, nameForUid)
        } else when (historyCard.policy) {
            HistoryPolicy.LAST_N -> ContextBuilder.plan(
                historyRows, Int.MAX_VALUE, nameForUid,
                compactToolResults = historyCard.compactToolResults,
                includeErrorTurns = historyCard.includeErrorTurns,
                maxTurns = historyCard.lastTurns.coerceAtLeast(1),
                dropReason = "outside last ${historyCard.lastTurns.coerceAtLeast(1)} turns",
            )
            HistoryPolicy.NO_CUT -> ContextBuilder.plan(
                historyRows, Int.MAX_VALUE, nameForUid,
                compactToolResults = historyCard.compactToolResults,
                includeErrorTurns = historyCard.includeErrorTurns,
            )
            HistoryPolicy.BUDGET -> ContextBuilder.plan(
                historyRows, historyBudget, nameForUid,
                compactToolResults = historyCard.compactToolResults,
                includeErrorTurns = historyCard.includeErrorTurns,
            )
        }

        val systemParts = layout.systemParts(vars)
        val system = systemParts.joinToString("\n\n") { it.text }

        // Đi từng thẻ từ trên xuống; thẻ History đóng góp lượt bị cắt (đứng
        // trước) rồi lượt gửi; thẻ Free vai user chèn một tin vào đúng vị trí.
        val messages = mutableListOf<AssembledMessage>()
        for (card in layout.cards) {
            if (!card.enabled) continue
            when (card.kind) {
                CardKind.HISTORY -> {
                    historyPlan.droppedMessages.forEach {
                        messages += AssembledMessage(it, sent = false, dropReason = historyPlan.dropReason)
                    }
                    historyPlan.messages.forEach { messages += AssembledMessage(it, sent = true) }
                }
                CardKind.LAST_TURN -> {
                    lastPlan.droppedMessages.forEach {
                        messages += AssembledMessage(it, sent = false, dropReason = lastPlan.dropReason)
                    }
                    lastPlan.messages.forEach { messages += AssembledMessage(it, sent = true) }
                }
                CardKind.FREE -> {
                    if (card.role == FreeRole.USER && card.text.isNotBlank()) {
                        messages += AssembledMessage(
                            AiMessage(Role.USER, text = layout.substitute(card.text, vars)),
                            sent = true,
                        )
                    }
                }
                else -> { /* SYSTEM/TIME/TOOLS không phải tin nhắn */ }
            }
        }

        // Gắn thẻ lượt [T7]: đếm tin user theo thứ tự trong danh sách cuối
        // (kể cả lượt bị cắt), đúng cách Inspector đánh số, nhưng chỉ gắn
        // vào tin thật sự gửi đi.
        val tagged = if (historyCard.turnTags) {
            var turn = 0
            messages.map { am ->
                if (am.message.role == Role.USER) {
                    turn++
                    if (am.sent) am.copy(message = am.message.copy(text = "[T$turn] ${am.message.text}")) else am
                } else am
            }
        } else messages

        val toolTokens = toolDefs.sumOf {
            TokenEstimator.text(it.name + it.description + it.schema.toString())
        }
        val historyTokens = historyPlan.usedTokens + lastPlan.usedTokens
        return AssembledRequest(
            system = system,
            systemParts = systemParts,
            messages = tagged,
            droppedTurnIds = historyPlan.droppedTurnIds + lastPlan.droppedTurnIds,
            totalTurns = historyPlan.totalTurns + lastPlan.totalTurns,
            toolDefs = toolDefs,
            estimatedTokens = TokenEstimator.text(system) + toolTokens + historyTokens,
            historyTokens = historyTokens,
        )
    }
}
