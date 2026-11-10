package com.example.byokchat.chat

import com.example.byokchat.ai.AiMessage
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.Role
import com.example.byokchat.ai.ToolDef
import com.example.byokchat.data.MessageEntity
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
    ) = MessageEntity(
        id = id,
        conversationId = "c1",
        turnId = turnId,
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
    fun systemIsJustTheSavedPrompt() {
        val out = RequestAssembler.assemble(
            systemPrompt = "Be helpful",
            rows = emptyList(),
            toolDefs = emptyList(),
            budget = 10_000,
            nameForUid = ::noRename,
        )
        assertEquals("Be helpful", out.system)
        assertEquals(listOf(SystemPart("Be helpful", SystemSource.USER_PROMPT)), out.systemParts)
        assertEquals(emptyList<AiMessage>(), out.sentMessages)
        assertEquals(emptyList<String>(), out.droppedTurnIds)
        assertEquals(0, out.totalTurns)
        assertEquals(TokenEstimator.text("Be helpful"), out.estimatedTokens)
    }

    @Test
    fun blankSystemHasNoParts() {
        val out = RequestAssembler.assemble("", emptyList(), emptyList(), 10_000, ::noRename)
        assertEquals("", out.system)
        assertEquals(emptyList<SystemPart>(), out.systemParts)
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
        assertEquals(TokenEstimator.text("Be helpful") + toolTokens, out.estimatedTokens)
    }

    @Test
    fun timeNowToolExistsAndReturnsDateTimeOnly() {
        val tool = DailyTools.build().firstOrNull { it.uid == "time.now" }
        assertNotNull(tool)
        assertEquals("current_time", tool!!.defaultName)
        assertTrue(tool.defaultDescription.contains("date and time only"))
    }
}
