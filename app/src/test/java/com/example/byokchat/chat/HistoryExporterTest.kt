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

        assertTrue(out.contains("session: conv-abc"))
        assertTrue(out.contains("title: \"Test chat\""))
        assertTrue(out.contains("created: 2023-11-14T22:13:20Z"))
        // Không còn dòng next_turn trong frontmatter (quyết định 12/11/2026):
        // số lượt tiếp theo suy ra từ các section khi parse.
        assertTrue(!out.contains("next_turn"))
        assertEquals(3, HistoryMdParser.parseMd(out).nextTurn)
        assertTrue(out.contains("## T1 user\n{\"id\":\"m1\"}\nWhat time is it?"))
        // Đợt E: vòng tool KHÔNG còn section riêng trong .md — tin assistant
        // có lệnh gọi vẫn có section của nó (kể cả khi text trống), vòng tool
        // nằm trong history.meta.json.
        assertTrue(out.contains("## T1 assistant\n{\"id\":\"m2\"}"))
        assertTrue(!out.contains("## T1 round"))
        assertTrue(!out.contains("### call"))
        assertTrue(!out.contains("### result"))
        assertTrue(out.contains("## T1 assistant\n{\"id\":\"m4\"}\nIt is 09:00."))
        assertTrue(out.contains("## T2 user\n{\"id\":\"m5\"}\nThanks"))
        assertTrue(out.contains("## T2 assistant\n{\"id\":\"m6\"}\nYou are welcome."))
        // Lượt 1 đứng trước lượt 2.
        assertTrue(out.indexOf("## T1") < out.indexOf("## T2"))

        val meta = HistoryExporter.renderMeta(conv, rows)
        val parsed = HistoryMdParser.parseMeta(meta)
        assertEquals(2, parsed.version)
        assertEquals("conv-abc", parsed.session)
        assertEquals(listOf("m1", "m2", "m4", "m5", "m6"), parsed.messages.keys.toList())
        val rounds = parsed.messages.getValue("m2").tool_rounds
        assertEquals(1, rounds.size)
        assertEquals("call-1", rounds[0].id)
        assertEquals("time.now", rounds[0].uid)
        assertEquals("current_time", rounds[0].name)
        assertEquals("2026-11-10 09:00", rounds[0].result)
        assertEquals(emptyList<HistoryToolRound>(), parsed.messages.getValue("m4").tool_rounds)
        // extra_content luôn null: Room không lưu trường này (Đợt E, không đổi DB).
        assertTrue(parsed.messages.values.all { it.extra_content == null })
    }

    @Test
    fun turnSectionAndMetaLinesMatchFullRender() {
        // Phần append của một lượt phải đúng y như render đủ viết cho lượt đó.
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
        val fullMd = HistoryExporter.renderHistory(conv, rows)
        val section = HistoryExporter.renderTurnSection(2, rows)
        assertTrue(section != null)
        assertTrue(fullMd.endsWith(section!!))
        val fullMeta = HistoryExporter.renderMeta(conv, rows)
        val lines = HistoryExporter.renderMetaLines(2, rows)
        assertTrue(lines != null)
        assertEquals(2, lines!!.size)
        assertTrue(fullMeta.endsWith(lines.joinToString("\n") + "\n"))
        // Lượt chưa xong thì không có gì để append.
        val unfinished = listOf(
            msg("m1", "t1", 1, "user", "Hi"),
            msg("m2", "t2", 2, "user", "And then?"),
        )
        assertEquals(null, HistoryExporter.renderTurnSection(2, unfinished))
        assertEquals(null, HistoryExporter.renderMetaLines(2, unfinished))
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
        assertTrue(out.contains("## T1 user\n{\"id\":\"m1\"}\nHi"))
        assertTrue(out.contains("## T1 assistant\n{\"id\":\"m3\"}"))
        assertTrue(out.contains("(error: Network failed)"))
    }

    @Test
    fun historyPathUsesSessionsAndShortId() {
        val conv = ConversationEntity("conv-abcdefgh", "Chat", 1_700_000_000_000, 1_700_000_000_000)
        val date = java.time.Instant.ofEpochMilli(1_700_000_000_000)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        assertEquals("sessions/${date}_conv-abc/history.md", HistoryExporter.historyPath(conv))
        assertEquals("sessions/${date}_conv-abc/history.meta.json", HistoryExporter.metaPath(conv))
    }

    @Test
    fun unfinishedTurnIsNotExported() {
        // Đợt E: lượt chỉ có tin user (chưa có assistant trả lời) không ghi vào file.
        val conv = ConversationEntity("conv-abcdefgh", "Chat", 1_700_000_000_000, 1_700_000_000_000)
        val rows = listOf(
            msg("m1", "t1", 1, "user", "Hi"),
            msg("m2", "t1", 2, "assistant", "Hello."),
            msg("m3", "t2", 3, "user", "And then?"),
        )
        val out = HistoryExporter.renderHistory(conv, rows)
        assertTrue(out.contains("## T1 user"))
        assertTrue(!out.contains("## T2 user"))
        // Không còn dòng next_turn; lượt chưa xong (T2) không có trong file
        // nên parser suy ra lượt tiếp theo là 2 từ section T1. Số lượt mới
        // vẫn tính từ Room khi gửi (TurnNumbers.nextTurnNo), không dùng file.
        assertTrue(!out.contains("next_turn"))
        assertEquals(2, HistoryMdParser.parseMd(out).nextTurn)
        val meta = HistoryExporter.renderMeta(conv, rows)
        val parsed = HistoryMdParser.parseMeta(meta)
        assertEquals(listOf("m1", "m2"), parsed.messages.keys.toList())
    }

    @Test
    fun stableTurnNumbersSurviveADeletedTurn() {
        // Đợt 4: T1 đã xóa, chỉ còn T2 và T5 — số không dồn lại, lượt tiếp
        // theo suy ra từ các section là 6.
        val conv = ConversationEntity("conv-abcdefgh", "Test chat", 1_700_000_000_000, 1_700_000_000_000)
        val rows = listOf(
            msg("m1", "t1", 1, "user", "Second turn", { copy(turnNo = 2) }),
            msg("m2", "t1", 2, "assistant", "Answer two."),
            msg("m3", "t2", 3, "user", "Fifth turn", { copy(turnNo = 5) }),
            msg("m4", "t2", 4, "assistant", "Answer five."),
        )
        val out = HistoryExporter.renderHistory(conv, rows)

        assertTrue(!out.contains("next_turn"))
        assertEquals(6, HistoryMdParser.parseMd(out).nextTurn)
        assertTrue(out.contains("## T2 user\n{\"id\":\"m1\"}\nSecond turn"))
        assertTrue(out.contains("## T5 user\n{\"id\":\"m3\"}\nFifth turn"))
        assertTrue(!out.contains("## T1 "))
    }
}
