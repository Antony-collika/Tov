package com.example.byokchat.chat

import com.example.byokchat.ai.AiMessage
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.Role
import com.example.byokchat.ai.ToolDef
import com.example.byokchat.data.CardKind
import com.example.byokchat.data.FreeRole
import com.example.byokchat.data.HistoryPolicy
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.RequestCard
import com.example.byokchat.data.RequestLayout
import com.example.byokchat.tools.DailyTools
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity test cho Đợt A: cùng dữ liệu vào, RequestAssembler phải cho ra đúng
 * thứ mà code cũ (ChatEngine.run / computeTokenStats) từng gửi đi — thứ tự
 * tin, lượt bị cắt, đổi tên công cụ theo UID, lượt lỗi bị bỏ.
 */
class RequestAssemblerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    private fun row(
        id: String,
        turnId: String,
        seq: Long,
        role: String,
        text: String,
        status: String = "done",
        toolCallsJson: String? = null,
        toolCallId: String? = null,
        toolUid: String? = null,
        toolName: String? = null,
        turnNo: Int = 0,
    ) = MessageEntity(
        id = id,
        conversationId = "c1",
        turnId = turnId,
        turnNo = turnNo,
        seq = seq,
        role = role,
        text = text,
        toolCallsJson = toolCallsJson,
        toolCallId = toolCallId,
        toolUid = toolUid,
        toolName = toolName,
        status = status,
        createdAt = seq,
    )

    private fun noRename(uid: String): String? = null

    @Test
    fun systemIsTheSavedPromptPlusTimeLine() {
        // Đợt 2: bố cục mặc định có thẻ Time BẬT nên system gồm prompt đã lưu
        // + dòng giờ ở cuối, gắn nhãn APP_ADDED.
        val out = RequestAssembler.assemble(
            systemPrompt = "Be helpful",
            rows = emptyList(),
            toolDefs = emptyList(),
            budget = 10_000,
            nameForUid = ::noRename,
        )
        assertTrue(out.system.startsWith("Be helpful\n\nCurrent time: "))
        assertEquals(2, out.systemParts.size)
        assertEquals(SystemPart("Be helpful", SystemSource.USER_PROMPT), out.systemParts[0])
        assertEquals(SystemSource.APP_ADDED, out.systemParts[1].source)
        assertTrue(out.systemParts[1].text.matches(Regex("Current time: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
        assertEquals(emptyList<AiMessage>(), out.sentMessages)
        assertEquals(emptyList<String>(), out.droppedTurnIds)
        assertEquals(0, out.totalTurns)
        assertEquals(TokenEstimator.text(out.system), out.estimatedTokens)
    }

    @Test
    fun blankSystemHasOnlyTimePart() {
        // Đợt 2: system trống nhưng thẻ Time BẬT nên vẫn có một phần là dòng giờ.
        val out = RequestAssembler.assemble("", emptyList(), emptyList(), 10_000, ::noRename)
        assertEquals(1, out.systemParts.size)
        assertEquals(SystemSource.APP_ADDED, out.systemParts[0].source)
        assertTrue(out.systemParts[0].text.matches(Regex("Current time: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
        assertEquals(out.systemParts[0].text, out.system)
    }

    @Test
    fun keepsOrderAndRenamesToolsByUid() {
        val calls = json.encodeToString(callsSer, listOf(AiToolCall("c1", "old_name", "{}", uid = "calc")))
        val rows = listOf(
            row("u1", "t1", 1, "user", "hello"),
            row("a1", "t1", 2, "assistant", "calling", toolCallsJson = calls),
            row("r1", "t1", 3, "tool", "42", toolCallId = "c1", toolUid = "calc", toolName = "old_name"),
            row("u2", "t2", 4, "user", "thanks"),
        )
        val out = RequestAssembler.assemble(
            systemPrompt = "Be helpful",
            rows = rows,
            toolDefs = emptyList(),
            budget = 10_000,
            nameForUid = { uid -> if (uid == "calc") "calculator" else null },
        )
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER), out.sentMessages.map { it.role })
        assertEquals(listOf("hello", "calling", "42", "thanks"), out.sentMessages.map { it.text })
        assertEquals(listOf("calculator"), out.sentMessages[1].toolCalls.map { it.name })
        assertEquals("calculator", out.sentMessages[2].toolName)
        assertEquals(2, out.totalTurns)
        assertEquals(emptyList<String>(), out.droppedTurnIds)
        assertTrue(out.messages.all { it.sent })
    }

    @Test
    fun dropsOldestTurnOverBudgetAndListsIt() {
        val rows = listOf(
            row("u1", "t1", 1, "user", "a".repeat(500)),
            row("u2", "t2", 2, "user", "hi"),
            row("a2", "t2", 3, "assistant", "ok"),
        )
        val out = RequestAssembler.assemble(
            systemPrompt = "",
            rows = rows,
            toolDefs = emptyList(),
            budget = 100,
            nameForUid = ::noRename,
        )
        assertEquals(listOf("t1"), out.droppedTurnIds)
        assertEquals(2, out.totalTurns)
        assertEquals(3, out.messages.size)
        assertFalse(out.messages[0].sent)
        assertEquals("over budget", out.messages[0].dropReason)
        assertEquals(listOf("hi", "ok"), out.sentMessages.map { it.text })
        assertEquals(14, out.historyTokens)
    }

    @Test
    fun errorAssistantTurnIsExcluded() {
        val rows = listOf(
            row("u1", "t1", 1, "user", "q"),
            row("a1", "t1", 2, "assistant", "boom", status = "error"),
            row("u2", "t2", 3, "user", "next"),
        )
        val out = RequestAssembler.assemble(
            systemPrompt = "",
            rows = rows,
            toolDefs = emptyList(),
            budget = 10_000,
            nameForUid = ::noRename,
        )
        assertEquals(listOf("q", "next"), out.sentMessages.map { it.text })
        assertTrue(out.messages.none { it.message.text == "boom" })
        assertEquals(2, out.totalTurns)
    }

    @Test
    fun toolDefsPassThroughAndTokensAddUp() {
        val defs = listOf(ToolDef("current_time", "Get the current date and time", buildJsonObject {}))
        val out = RequestAssembler.assemble(
            systemPrompt = "Be helpful",
            rows = emptyList(),
            toolDefs = defs,
            budget = 10_000,
            nameForUid = ::noRename,
        )
        assertEquals(defs, out.toolDefs)
        val toolTokens = TokenEstimator.text("current_time" + "Get the current date and time" + "{}")
        assertEquals(TokenEstimator.text(out.system) + toolTokens, out.estimatedTokens)
    }

    @Test
    fun timeNowToolExistsAndReturnsDateTimeOnly() {
        val tool = DailyTools.build().firstOrNull { it.uid == "time.now" }
        assertNotNull(tool)
        assertEquals("current_time", tool!!.defaultName)
        assertTrue(tool.defaultDescription.contains("date and time only"))
        assertEquals(RequestLayout.TIME_NOW_UID, tool.uid)
    }

    // ---------- Đợt C: bố cục ----------

    private fun layoutWith(vararg cards: RequestCard): RequestLayout = RequestLayout(cards.toList())

    private fun defaultLayout(prompt: String = "") = RequestLayout.defaultLayout(prompt)

    private fun assembleLayout(
        layout: RequestLayout,
        rows: List<MessageEntity>,
        budget: Int = 10_000,
    ) = RequestAssembler.assemble(
        layout = layout,
        rows = rows,
        toolDefs = emptyList(),
        budget = budget,
        nameForUid = ::noRename,
        vars = emptyMap(),
    )

    @Test
    fun layoutDefaultMatchesOldPath() {
        val rows = listOf(
            row("u1", "t1", 1, "user", "a".repeat(500)),
            row("u2", "t2", 2, "user", "hi"),
            row("a2", "t2", 3, "assistant", "ok"),
        )
        val old = RequestAssembler.assemble("", rows, emptyList(), 100, ::noRename)
        val viaLayout = assembleLayout(defaultLayout(""), rows, budget = 100)
        assertEquals(old.system, viaLayout.system)
        assertEquals(old.systemParts, viaLayout.systemParts)
        assertEquals(old.sentMessages.map { it.text }, viaLayout.sentMessages.map { it.text })
        assertEquals(old.droppedTurnIds, viaLayout.droppedTurnIds)
        assertEquals(old.historyTokens, viaLayout.historyTokens)
        assertEquals(old.estimatedTokens, viaLayout.estimatedTokens)
        assertEquals(old.totalTurns, viaLayout.totalTurns)
    }

    @Test
    fun freeUserCardInsertsMessageInCardOrder() {
        val layout = layoutWith(
            RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = ""),
            RequestCard("free-1", CardKind.FREE, text = "Remember this", role = FreeRole.USER),
            RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY),
            RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
        )
        val rows = listOf(
            row("u1", "t1", 1, "user", "first"),
            row("u2", "t2", 2, "user", "second"),
        )
        val out = assembleLayout(layout, rows)
        assertEquals(listOf("Remember this", "first", "second"), out.sentMessages.map { it.text })
    }

    @Test
    fun freeSystemCardBecomesSystemPart() {
        val layout = layoutWith(
            RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = "Base"),
            RequestCard("free-1", CardKind.FREE, text = "Extra rules", role = FreeRole.SYSTEM),
            RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY),
            RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
        )
        val out = assembleLayout(layout, emptyList())
        assertEquals("Base\n\nExtra rules", out.system)
        assertEquals(2, out.systemParts.size)
    }

    @Test
    fun timeCardOnAppendsTimeLineToSystem() {
        val out = RequestAssembler.assemble(
            layout = defaultLayout("Be helpful"),
            rows = emptyList(),
            toolDefs = emptyList(),
            budget = 10_000,
            nameForUid = ::noRename,
            vars = mapOf("date" to "2026-11-10", "time" to "14:32"),
        )
        assertEquals("Be helpful\n\nCurrent time: 2026-11-10 14:32", out.system)
        assertEquals(SystemPart("Current time: 2026-11-10 14:32", SystemSource.APP_ADDED), out.systemParts.last())
    }

    @Test
    fun timeCardOffAddsNoTimeLine() {
        val layout = defaultLayout("Be helpful").copy(
            cards = defaultLayout("Be helpful").cards.map {
                if (it.kind == CardKind.TIME) it.copy(enabled = false) else it
            }
        )
        val out = RequestAssembler.assemble(
            layout = layout,
            rows = emptyList(),
            toolDefs = emptyList(),
            budget = 10_000,
            nameForUid = ::noRename,
            vars = mapOf("date" to "2026-11-10", "time" to "14:32"),
        )
        assertEquals("Be helpful", out.system)
        assertEquals(listOf(SystemPart("Be helpful", SystemSource.USER_PROMPT)), out.systemParts)
    }

    @Test
    fun lastNTurnsPolicyKeepsOnlyNNewest() {
        val history = RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY, policy = HistoryPolicy.LAST_N, lastTurns = 2)
        val layout = layoutWith(
            RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = ""),
            history,
            RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
        )
        val rows = listOf(
            row("u1", "t1", 1, "user", "m1"),
            row("u2", "t2", 2, "user", "m2"),
            row("u3", "t3", 3, "user", "m3"),
            row("u4", "t4", 4, "user", "m4"),
        )
        val out = assembleLayout(layout, rows)
        assertEquals(listOf("t1"), out.droppedTurnIds)
        assertEquals(listOf("m2", "m3", "m4"), out.sentMessages.map { it.text })
        assertEquals("outside last 2 turns", out.messages.first { !it.sent }.dropReason)
    }

    @Test
    fun noCutPolicySendsEverythingEvenOverBudget() {
        val history = RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY, policy = HistoryPolicy.NO_CUT)
        val layout = layoutWith(
            RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = ""),
            history,
            RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
        )
        val rows = listOf(
            row("u1", "t1", 1, "user", "a".repeat(500)),
            row("u2", "t2", 2, "user", "hi"),
        )
        val out = assembleLayout(layout, rows, budget = 10)
        assertEquals(emptyList<String>(), out.droppedTurnIds)
        assertEquals(2, out.sentMessages.size)
    }

    @Test
    fun compactToolResultsShortensOldOnesOnly() {
        val history = RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY, compactToolResults = true)
        val layout = layoutWith(
            RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = ""),
            history,
            RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
        )
        val calls1 = json.encodeToString(callsSer, listOf(AiToolCall("c1", "calculator", "{}", uid = "calc")))
        val calls2 = json.encodeToString(callsSer, listOf(AiToolCall("c2", "calculator", "{}", uid = "calc")))
        val longResult = "x".repeat(600)
        val rows = listOf(
            row("u1", "t1", 1, "user", "calc this"),
            row("a1", "t1", 2, "assistant", "calling", toolCallsJson = calls1),
            row("r1", "t1", 3, "tool", longResult, toolCallId = "c1", toolUid = "calc", toolName = "calculator"),
            row("u2", "t2", 4, "user", "again"),
            row("a2", "t2", 5, "assistant", "calling", toolCallsJson = calls2),
            row("r2", "t2", 6, "tool", longResult, toolCallId = "c2", toolUid = "calc", toolName = "calculator"),
        )
        val out = assembleLayout(layout, rows)
        val toolTexts = out.sentMessages.filter { it.role == Role.TOOL }.map { it.text }
        assertEquals(2, toolTexts.size)
        assertEquals("[Shortened result: calculator, 600 characters, turn T1. Call the tool again for the full result.]", toolTexts[0])
        assertEquals(longResult, toolTexts[1])
    }

    @Test
    fun compactToolResultNoteUsesStoredTurnNumber() {
        // Đợt 4: ghi chú rút gọn ghi số lượt đã lưu (T3), không đếm theo vị trí.
        val history = RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY, compactToolResults = true)
        val layout = layoutWith(
            RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = ""),
            history,
            RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
        )
        val calls1 = json.encodeToString(callsSer, listOf(AiToolCall("c1", "calculator", "{}", uid = "calc")))
        val calls2 = json.encodeToString(callsSer, listOf(AiToolCall("c2", "calculator", "{}", uid = "calc")))
        val longResult = "x".repeat(600)
        val rows = listOf(
            row("u1", "t1", 1, "user", "calc this", turnNo = 3),
            row("a1", "t1", 2, "assistant", "calling", toolCallsJson = calls1),
            row("r1", "t1", 3, "tool", longResult, toolCallId = "c1", toolUid = "calc", toolName = "calculator"),
            row("u2", "t2", 4, "user", "again", turnNo = 7),
            row("a2", "t2", 5, "assistant", "calling", toolCallsJson = calls2),
            row("r2", "t2", 6, "tool", longResult, toolCallId = "c2", toolUid = "calc", toolName = "calculator"),
        )
        val out = assembleLayout(layout, rows)
        val toolTexts = out.sentMessages.filter { it.role == Role.TOOL }.map { it.text }
        assertEquals("[Shortened result: calculator, 600 characters, turn T3. Call the tool again for the full result.]", toolTexts[0])
        assertEquals(longResult, toolTexts[1])
    }

    @Test
    fun turnTagsPrefixUserMessagesInOrder() {
        val history = RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY, turnTags = true)
        val layout = layoutWith(
            RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = ""),
            history,
            RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
        )
        val rows = listOf(
            row("u1", "t1", 1, "user", "hello"),
            row("u2", "t2", 2, "user", "thanks"),
        )
        val out = assembleLayout(layout, rows)
        assertEquals(listOf("[T1] hello", "[T2] thanks"), out.sentMessages.map { it.text })
    }

    @Test
    fun turnTagsUseStoredTurnNumbers() {
        // Đợt 4: T1 đã xóa, chỉ còn T2 và T5 — thẻ gắn số đã lưu, không đếm lại.
        val history = RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY, turnTags = true)
        val layout = layoutWith(
            RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = ""),
            history,
            RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
        )
        val rows = listOf(
            row("u1", "t1", 1, "user", "hello", turnNo = 2),
            row("u2", "t2", 2, "user", "thanks", turnNo = 5),
        )
        val out = assembleLayout(layout, rows)
        assertEquals(listOf("[T2] hello", "[T5] thanks"), out.sentMessages.map { it.text })
        assertEquals(listOf(2, 5), out.turnNumbers)
    }

    @Test
    fun includeErrorTurnsSendsErrorText() {
        val history = RequestCard(RequestLayout.HISTORY_ID, CardKind.HISTORY, includeErrorTurns = true)
        val layout = layoutWith(
            RequestCard(RequestLayout.SYSTEM_ID, CardKind.SYSTEM, text = ""),
            history,
            RequestCard(RequestLayout.LAST_TURN_ID, CardKind.LAST_TURN),
        )
        val rows = listOf(
            row("u1", "t1", 1, "user", "q"),
            row("a1", "t1", 2, "assistant", "boom", status = "error"),
            row("u2", "t2", 3, "user", "next"),
        )
        val out = assembleLayout(layout, rows)
        assertEquals(listOf("q", "boom", "next"), out.sentMessages.map { it.text })
    }
}
