package com.example.byokchat.chat

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.MessageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho Đợt D: xuất history.md. */
class HistoryExporterTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    private fun msg(id: String, turnId: String, seq: Long, role: String, text: String, extra: MessageEntity.() -> MessageEntity = { this }) =
        MessageEntity(
            id = id, conversationId = "conv-abcdefgh", turnId = turnId, seq = seq,
            role = role, text = text, createdAt = 1_700_000_000_000,
        ).extra()

    @Test
    fun rendersUserAssistantAndToolRound() {
        val conv = ConversationEntity("conv-abcdefgh", "Test chat", 1_700_000_000_000, 1_700_000_000_000)
        val callsJson = json.encodeToString(callsSer, listOf(AiToolCall("call-1", "current_time", "{}")))
        val rows = listOf(
            msg("m1", "t1", 1, "user", "What time is it?"),
            msg("m2", "t1", 2, "assistant", "", { copy(toolCallsJson = callsJson) }),
            msg("m3", "t1", 3, "tool", "2026-11-10 09:00", { copy(toolCallId = "call-1", toolUid = "time.now", toolName = "current_time") }),
            msg("m4", "t1", 4, "assistant", "It is 09:00."),
            msg("m5", "t2", 5, "user", "Thanks"),
            msg("m6", "t2", 6, "assistant", "You are welcome."),
        )
        val out = HistoryExporter.renderHistory(conv, rows)

        assertTrue(out.contains("session: conv-abcdefgh"))
        assertTrue(out.contains("title: Test chat"))
        assertTrue(out.contains("next_turn: 3"))
        assertTrue(out.contains("## T1 user\nWhat time is it?"))
        assertTrue(out.contains("## T1 round 1"))
        assertTrue(out.contains("### call call-1 current_time"))
        assertTrue(out.contains("### result call-1\n2026-11-10 09:00"))
        assertTrue(out.contains("## T1 assistant\nIt is 09:00."))
        assertTrue(out.contains("## T2 user\nThanks"))
        assertTrue(out.contains("## T2 assistant\nYou are welcome."))
        // Lượt 1 đứng trước lượt 2.
        assertTrue(out.indexOf("## T1") < out.indexOf("## T2"))
    }

    @Test
    fun errorAssistantAndStreamingAreHandled() {
        val conv = ConversationEntity("conv-abcdefgh", "Chat", 1_700_000_000_000, 1_700_000_000_000)
        val rows = listOf(
            msg("m1", "t1", 1, "user", "Hi"),
            msg("m2", "t1", 2, "assistant", "", { copy(status = "streaming") }),
            msg("m3", "t1", 3, "assistant", "", { copy(status = "error", error = "Network failed") }),
        )
        val out = HistoryExporter.renderHistory(conv, rows)
        assertTrue(out.contains("## T1 assistant"))
        assertTrue(out.contains("(error: Network failed)"))
    }

    @Test
    fun historyPathUsesDateAndShortId() {
        val conv = ConversationEntity("conv-abcdefgh", "Chat", 1_700_000_000_000, 1_700_000_000_000)
        val date = java.time.Instant.ofEpochMilli(1_700_000_000_000)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        assertEquals("Tov/chats/${date}_conv-abc/history.md", HistoryExporter.historyPath(conv))
    }
}
