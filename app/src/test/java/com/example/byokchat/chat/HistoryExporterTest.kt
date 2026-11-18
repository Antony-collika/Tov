package com.example.byokchat.chat

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.MessageEntity
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /** Name-resolution tests need a known agent in the store snapshot; there is no seed anymore. */
    private fun installReviewAgent() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tov-agents-${System.nanoTime()}")
        dir.mkdirs()
        val store = SubAgentStore(File(dir, "agents.json"))
        assertTrue(store.save(SubAgentTeam(agents = listOf(testAgent()))).isSuccess)
    }

    @Test
    fun rendersUserAssistantAndToolRound() {
        val conv = ConversationEntity("conv-abcdefgh", "Test chat", 1_700_000_000_000, 1_700_000_000_000)
        val callsJson = json.encodeToString(callsSer, listOf(AiToolCall("call-1", "current_time", "{}")))
        val rows = listOf(
            msg("m1", "t1", 1, "user", "What time is it?"),
            msg("m2", "t1", 2, "assistant", "", { copy(toolCallsJson = callsJson) }),
            msg("m3", "t1", 3, "tool", "2026-11-10 09:00", { copy(toolCallId = "call-1", toolUid = "time.now", toolName = "current_time") }),
            msg("m4", "t1", 4, "assistant", "It is 09:00.", { copy(thoughtSignature = "sig-abc") }),
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
        // extra_content: chỉ tin assistant có thoughtSignature mới có
        // {"google": {"thought_signature": ...}}; các tin khác null.
        assertTrue(parsed.messages.getValue("m1").extra_content == null)
        assertTrue(parsed.messages.getValue("m2").extra_content == null)
        val extra = parsed.messages.getValue("m4").extra_content
        assertTrue(extra != null)
        assertEquals(
            "sig-abc",
            extra!!.jsonObject["google"]?.jsonObject?.get("thought_signature")?.jsonPrimitive?.content,
        )
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

    @Test
    fun subAgentMessageHeadingCarriesSenderName() {
        installReviewAgent()
        val conv = ConversationEntity("conv-abcdefgh", "Test chat", 1_700_000_000_000, 1_700_000_000_000)
        val rows = listOf(
            msg("m1", "t1", 1, "user", "Find something about X."),
            msg("m2", "t1", 2, "assistant", "Hi, I'm Review.", { copy(sender = "review") }),
            msg("m3", "t1", 3, "assistant", "Done — found 5 sources.", { copy(sender = "review") }),
            msg("m4", "t1", 4, "assistant", "Here is the summary."),
        )
        val out = HistoryExporter.renderHistory(conv, rows)

        assertTrue(out.contains("## T1 assistant (Review)\n{\"id\":\"m2\"}\nHi, I'm Review."))
        assertTrue(out.contains("## T1 assistant (Review)\n{\"id\":\"m3\"}\nDone — found 5 sources."))
        assertTrue(out.contains("## T1 assistant\n{\"id\":\"m4\"}\nHere is the summary."))
        // Round-trip: the parser reads the sender name back.
        val parsed = HistoryMdParser.parseMd(out)
        assertEquals(null, parsed.messages[0].sender)
        assertEquals("Review", parsed.messages[1].sender)
        assertEquals("Review", parsed.messages[2].sender)
        assertEquals(null, parsed.messages[3].sender)
    }

    @Test
    fun lateSubMessageRendersAnchorLineAndRoundTrips() {
        installReviewAgent()
        val conv = ConversationEntity("conv-abcdefgh", "Test chat", 1_700_000_000_000, 1_700_000_000_000)
        val rows = listOf(
            msg("m1", "t1", 1, "user", "Find cheerful music."),
            msg("m2", "t1", 2, "assistant", "Hi, I'm Monica.", { copy(sender = "review") }),
            msg("m3", "t2", 3, "assistant", "Found three cheerful songs.", { copy(sender = "review", taskTitle = "tìm nhạc xưa") }),
        )
        val out = HistoryExporter.renderHistory(conv, rows)

        assertTrue(out.contains("## T2 assistant (Review)\n{\"id\":\"m3\"}\n↩ tìm nhạc xưa\nFound three cheerful songs."))
        // Round-trip: the parser reads the anchor back, and the greeting
        // (no anchor) keeps a null taskTitle.
        val parsed = HistoryMdParser.parseMd(out)
        assertEquals("tìm nhạc xưa", parsed.messages[2].taskTitle)
        assertEquals("Found three cheerful songs.", parsed.messages[2].text)
        assertEquals(null, parsed.messages[1].taskTitle)
    }

    @Test
    fun historyFileNames() {
        // Fork khi regenerate (15/11/2026): nhánh 0 = tên gốc, N ≥ 1 = _(NN).
        assertEquals("history.md", HistoryExporter.historyFileName(0))
        assertEquals("history.meta.json", HistoryExporter.metaFileName(0))
        assertEquals("history_(01).md", HistoryExporter.historyFileName(1))
        assertEquals("history.meta_(01).json", HistoryExporter.metaFileName(1))
        assertEquals("history_(12).md", HistoryExporter.historyFileName(12))
        assertEquals("history.meta_(12).json", HistoryExporter.metaFileName(12))
        // Nhánh âm (dữ liệu lạ) thì về tên gốc, không tạo tên file kỳ quặc.
        assertEquals("history.md", HistoryExporter.historyFileName(-1))
        assertEquals("history.meta.json", HistoryExporter.metaFileName(-1))
    }

    @Test
    fun historyPathsFollowBranch() {
        val conv = ConversationEntity("conv-abcdefgh", "Test chat", 1_700_000_000_000, 1_700_000_000_000)
        // Chỉ kiểm tra tên file ở cuối đường dẫn (thư mục session chứa ngày
        // theo múi giờ máy chạy test nên không hardcode cả đường dẫn).
        assertTrue(HistoryExporter.historyPath(conv).endsWith("/history.md"))
        assertTrue(HistoryExporter.metaPath(conv).endsWith("/history.meta.json"))
        val forked = conv.copy(historyBranch = 1)
        assertTrue(HistoryExporter.historyPath(forked).endsWith("/history_(01).md"))
        assertTrue(HistoryExporter.metaPath(forked).endsWith("/history.meta_(01).json"))
        // Cùng thư mục session, chỉ khác tên file — không parse nhầm sang
        // thư mục khác.
        assertEquals(
            HistoryExporter.sessionDir(conv),
            HistoryExporter.sessionDir(forked),
        )
    }

    @Test
    fun forkRendersPrefixOnly() {
        // Mô phỏng Room sau prepareRegenerateHere cho lượt 2: lượt 1 đã xong,
        // lượt 2 chỉ còn tin user (đáp án cũ đã xóa). File fork dựng từ Room
        // phải chỉ chứa lượt 1 — lượt 2 chưa xong nên không có trong file;
        // lượt mới xong sẽ append vào đó.
        val conv = ConversationEntity("conv-abcdefgh", "Test chat", 1_700_000_000_000, 1_700_000_000_000)
        val rows = listOf(
            msg("m1", "t1", 1, "user", "First question", { copy(turnNo = 1) }),
            msg("m2", "t1", 2, "assistant", "Answer one.", { copy(turnNo = 1) }),
            msg("m3", "t2", 3, "user", "Second question", { copy(turnNo = 2) }),
        )
        val out = HistoryExporter.renderHistory(conv, rows)
        assertTrue(out.contains("## T1 user\n{\"id\":\"m1\"}\nFirst question"))
        assertTrue(out.contains("## T1 assistant\n{\"id\":\"m2\"}\nAnswer one."))
        assertTrue(!out.contains("## T2"))
        assertEquals(2, HistoryMdParser.parseMd(out).nextTurn)
        val meta = HistoryMdParser.parseMeta(HistoryExporter.renderMeta(conv, rows))
        assertTrue(meta.messages.containsKey("m1"))
        assertTrue(meta.messages.containsKey("m2"))
        assertTrue(!meta.messages.containsKey("m3"))
    }
}
