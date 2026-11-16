package com.example.byokchat.chat

import com.example.byokchat.ai.Role
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.data.MessageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool-call adjacency (16/11/2026): a sub-agent writes its own messages
 * into the chat DURING the delegate tool call, so in the stored order
 * they land between Main's tool-call message and the tool result.
 * OpenAI-compatible servers (and Gemini) require a tool-call message to
 * be followed immediately by its tool-result messages, so the assembled
 * request must pull each call's results forward to right after the call
 * — the intervening messages keep their relative order and move after
 * the results. Content is preserved, only the order changes.
 */
class ContextBuilderToolOrderTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    private fun row(
        id: String,
        turnId: String,
        seq: Long,
        role: String,
        text: String,
        status: String = "done",
        sender: String? = null,
        toolCallsJson: String? = null,
        toolCallId: String? = null,
        toolUid: String? = null,
        toolName: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = "c1",
        turnId = turnId,
        turnNo = 1,
        seq = seq,
        role = role,
        text = text,
        sender = sender,
        toolCallsJson = toolCallsJson,
        toolCallId = toolCallId,
        toolUid = toolUid,
        toolName = toolName,
        status = status,
        createdAt = seq,
    )

    @Test
    fun subMessageBetweenToolCallAndResultMovesAfterTheResult() {
        val calls = json.encodeToString(
            callsSer,
            listOf(AiToolCall("c1", "delegate_to_subagent", "{}", uid = "sub.delegate")),
        )
        val rows = listOf(
            row("u1", "t1", 1, "user", "bật nhạc vui vui đi"),
            row("a1", "t1", 2, "assistant", "", toolCallsJson = calls),
            row("s1", "t1", 3, "assistant", "Chào bạn! Đã bật nhạc rồi nhé.", sender = "monica"),
            row("r1", "t1", 4, "tool", "detailed report", toolCallId = "c1", toolUid = "sub.delegate", toolName = "delegate_to_subagent"),
        )
        val plan = ContextBuilder.plan(rows, budget = 100_000, nameForUid = { null })
        val msgs = plan.messages
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT),
            msgs.map { it.role },
        )
        assertEquals(listOf("c1"), msgs[1].toolCalls.map { it.id })
        assertEquals("c1", msgs[2].toolCallId)
        assertTrue(msgs[3].text.contains("Chào bạn"))
    }

    @Test
    fun orderWithoutInterveningMessagesIsUnchanged() {
        val calls = json.encodeToString(
            callsSer,
            listOf(AiToolCall("c1", "calculator", "{}", uid = "calc")),
        )
        val rows = listOf(
            row("u1", "t1", 1, "user", "tính 2+2"),
            row("a1", "t1", 2, "assistant", "", toolCallsJson = calls),
            row("r1", "t1", 3, "tool", "4", toolCallId = "c1", toolUid = "calc", toolName = "calculator"),
            row("a2", "t1", 4, "assistant", "Kết quả là 4."),
        )
        val plan = ContextBuilder.plan(rows, budget = 100_000, nameForUid = { null })
        val msgs = plan.messages
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT),
            msgs.map { it.role },
        )
        assertEquals("c1", msgs[2].toolCallId)
        assertEquals("Kết quả là 4.", msgs[3].text)
    }
}
