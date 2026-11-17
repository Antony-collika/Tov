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
 * Tool-call adjacency (16/11/2026) + sub messages excluded from Main's
 * request (17/11/2026): a sub-agent writes its own messages into the chat
 * DURING the delegate tool call, so in the stored order they land between
 * Main's tool-call message and the tool result. They used to be moved
 * after the result (orderToolResults) — but a request ending with those
 * model messages is rejected by Gemini with a 400 ("Requests ending with
 * a model turn are not supported"), so they are now excluded from Main's
 * request entirely. Main already has the detailed report as the delegate
 * tool's result; the sub's short messages stay in the DB and on screen.
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
    fun subMessageBetweenToolCallAndResultIsExcludedFromMainRequest() {
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
            listOf(Role.USER, Role.ASSISTANT, Role.TOOL),
            msgs.map { it.role },
        )
        assertEquals(listOf("c1"), msgs[1].toolCalls.map { it.id })
        assertEquals("c1", msgs[2].toolCallId)
        assertTrue(msgs.none { it.text.contains("Chào bạn") })
    }

    @Test
    fun subMessagesAtTheEndDoNotEndTheRequestWithAModelTurn() {
        // The 400 case (17/11/2026): after the delegate result, Monica sent
        // two short messages to the user, so the request ended with model
        // turns and Gemini refused it. The request must end with the tool
        // result instead, and carry no "[Monica] ..." text.
        val calls = json.encodeToString(
            callsSer,
            listOf(AiToolCall("c1", "delegate_to_subagent", "{}", uid = "sub.delegate")),
        )
        val rows = listOf(
            row("u1", "t1", 1, "user", "phát nhạc phù hợp thời gian hiện tại đi"),
            row("a1", "t1", 2, "assistant", "", toolCallsJson = calls),
            row("r1", "t1", 3, "tool", "detailed report: played Morning Coffee", toolCallId = "c1", toolUid = "sub.delegate", toolName = "delegate_to_subagent"),
            row("s1", "t1", 4, "assistant", "Chào buổi sáng! Hôm nay trời đẹp.", sender = "monica"),
            row("s2", "t1", 5, "assistant", "Tôi đã phát bản nhạc Morning Coffee cho bạn.", sender = "monica"),
        )
        val plan = ContextBuilder.plan(rows, budget = 100_000, nameForUid = { null })
        val msgs = plan.messages
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT, Role.TOOL),
            msgs.map { it.role },
        )
        assertEquals(Role.TOOL, msgs.last().role)
        assertEquals("c1", msgs.last().toolCallId)
        assertTrue(msgs.none { it.text.contains("[Monica]") })
        assertTrue(msgs.none { it.text.contains("Morning Coffee cho bạn") })
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
