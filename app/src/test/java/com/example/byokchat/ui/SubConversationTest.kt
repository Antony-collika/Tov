package com.example.byokchat.ui

import com.example.byokchat.chat.encodeSubRunStrings
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.SubRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session conversation (17/11/2026, mockup v2): tapping a sub's avatar
 * in ANY turn opens the same screen — the whole Main↔sub exchange of
 * the session, Main on one side, the sub on the other, in time order.
 * The brief and the report live inside the conversation as messages;
 * the two kinds of sub messages (to Main vs to the user) are told
 * apart only in the data, never labelled on screen.
 */
class SubConversationTest {

    private fun run(
        id: Long, turnNo: Int, startedAt: Long, finishedAt: Long,
        brief: String = "Find cheerful morning music.",
        title: String = "Find music",
        outbox: List<String> = emptyList(),
        report: String = "Sub-agent Monica finished: done.\nTask: Find cheerful morning music.\nRounds: 2/10, estimated tokens: ~100/32000.\n\nFound it: Mùa xuân đầu tiên.",
    ) = SubRunEntity(
        id = id, conversationId = "conv-1", turnNo = turnNo, agent = "monica", agentName = "Monica",
        title = title, brief = brief, status = "done", rounds = 2, tokens = 100,
        toolCallsJson = "[]", messagesToMainJson = encodeSubRunStrings(outbox),
        fullReport = report, reasoning = "", startedAt = startedAt, finishedAt = finishedAt,
    )

    private fun msg(id: String, seq: Long, text: String, sender: String? = "monica") = MessageEntity(
        id = id, conversationId = "conv-1", turnId = "t1", seq = seq, role = "assistant",
        text = text, sender = sender, status = "done", createdAt = seq,
    )

    @Test
    fun turnsOneAndTenShareOneTimelineInOrder() {
        // The avatar in turn 1 and the avatar in turn 10 open the same
        // screen: every run of the session is in it, in time order.
        val items = buildSubConversation(
            runs = listOf(
                run(id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000),
                run(id = 2, turnNo = 10, startedAt = 5000, finishedAt = 6000, brief = "Find a calm song."),
            ),
            subMessages = listOf(
                msg("m1", 1500, "Đang phát “Mùa xuân đầu tiên” cho cậu đây."),
                msg("m2", 7000, "Đang phát bài nhẹ nhàng cho cậu đây."),
            ),
        )
        assertEquals(
            listOf(
                "Find cheerful morning music." to true,
                "Đang phát “Mùa xuân đầu tiên” cho cậu đây." to false,
                "Found it: Mùa xuân đầu tiên." to false,
                "Find a calm song." to true,
                "Found it: Mùa xuân đầu tiên." to false,
                "Đang phát bài nhẹ nhàng cho cậu đây." to false,
            ),
            items.map { it.text to it.fromMain },
        )
    }

    @Test
    fun reportHeaderAndOutboxSectionAreNotRepeated() {
        val report = "Sub-agent Monica finished: done.\n" +
            "Task: Find cheerful morning music.\n" +
            "Tools called: youtube_search\n" +
            "Rounds: 2/10, estimated tokens: ~100/32000.\n" +
            "\n" +
            "Messages from Monica to Main:\n" +
            "- Rõ ạ, em tìm ngay đây.\n" +
            "- Báo cáo: đã tìm xong.\n" +
            "\n" +
            "Found it: Mùa xuân đầu tiên."
        val items = buildSubConversation(
            runs = listOf(run(id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000, outbox = listOf("Rõ ạ, em tìm ngay đây.", "Báo cáo: đã tìm xong."), report = report)),
            subMessages = emptyList(),
        )
        // Outbox lines appear once, as their own bubbles; the report
        // bubble carries only the report body, no header, no outbox list.
        assertEquals(
            listOf(
                "Find cheerful morning music." to true,
                "Rõ ạ, em tìm ngay đây." to false,
                "Báo cáo: đã tìm xong." to false,
                "Found it: Mùa xuân đầu tiên." to false,
            ),
            items.map { it.text to it.fromMain },
        )
        assertTrue(items.none { it.text.contains("finished:") || it.text.contains("Rounds:") })
    }

    @Test
    fun multilineBriefDoesNotLeakIntoTheReport() {
        val report = "Sub-agent Monica finished: done.\n" +
            "Task: Find music.\n" +
            "Line two of the brief.\n" +
            "Rounds: 2/10, estimated tokens: ~100/32000.\n" +
            "\n" +
            "Found it."
        assertEquals("Found it.", stripReportHeader(report, "Monica"))
    }

    @Test
    fun runsBeforeTheTableExistedShowOnlyMessageRows() {
        val items = buildSubConversation(
            runs = emptyList(),
            subMessages = listOf(msg("m1", 1500, "Đang phát nhạc cho cậu đây.")),
        )
        assertEquals(listOf("Đang phát nhạc cho cậu đây." to false), items.map { it.text to it.fromMain })
    }

    @Test
    fun blankReportAddsNoBubble() {
        val items = buildSubConversation(
            runs = listOf(
                run(
                    id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000,
                    report = "Sub-agent Monica finished: failed.\nTask: Find music.\nRounds: 1/10, estimated tokens: ~10/32000.\n\n(no report text)",
                ),
            ),
            subMessages = emptyList(),
        )
        assertEquals(listOf("Find cheerful morning music." to true), items.map { it.text to it.fromMain })
    }

    @Test
    fun blankBriefFallsBackToTitle() {
        val items = buildSubConversation(
            runs = listOf(run(id = 1, turnNo = 1, startedAt = 1000, finishedAt = 2000, brief = "", title = "Find music")),
            subMessages = emptyList(),
        )
        assertEquals("Find music", items.first().text)
        assertTrue(items.first().fromMain)
    }

    @Test
    fun blankSubMessagesAreSkipped() {
        val items = buildSubConversation(
            runs = emptyList(),
            subMessages = listOf(msg("m1", 1500, ""), msg("m2", 1600, "Có đây.")),
        )
        assertEquals(listOf("Có đây."), items.map { it.text })
    }
}
