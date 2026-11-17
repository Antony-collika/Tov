package com.example.byokchat.ui

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.data.MessageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pill grouping (17/11/2026, approved mockup): consecutive tool/reasoning
 * rows by one speaker in one turn form one work segment; plain text
 * messages never become steps, and tool-result rows attach by call id.
 */
class WorkSegmentsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    // Call ids must stay unique across rows (as in the DB), or results
    // would attach to the wrong step.
    private var nextCall = 0
    private fun calls(vararg names: String): String =
        json.encodeToString(callsSer, names.map { n -> AiToolCall("c${nextCall++}", n, "{}") })

    private fun row(
        id: String,
        turnId: String,
        seq: Long,
        role: String = "assistant",
        text: String = "",
        sender: String? = null,
        toolCallsJson: String? = null,
        reasoningText: String? = null,
        toolCallId: String? = null,
        status: String = "done",
    ) = MessageEntity(
        id = id,
        conversationId = "c1",
        turnId = turnId,
        seq = seq,
        role = role,
        text = text,
        sender = sender,
        toolCallsJson = toolCallsJson,
        reasoningText = reasoningText,
        toolCallId = toolCallId,
        status = status,
        createdAt = seq,
    )

    private fun results(vararg toolRows: MessageEntity) =
        ToolResults(toolRows.filter { it.toolCallId != null }.associateBy { it.toolCallId!! })

    @Test fun mainTwoToolRoundsFormOneSegmentInOrder() {
        val rows = listOf(
            row("u1", "t1", 1, role = "user", text = "mở nhạc gì vui vui đi"),
            row("a1", "t1", 2, toolCallsJson = calls("read_note")),
            row("a2", "t1", 3, toolCallsJson = calls("delegate_to_subagent")),
            row("a3", "t1", 4, text = "Được thôi, để mình nhờ Monica…"),
        )
        val segments = groupWorkSegments(rows, results())
        assertEquals(1, segments.size)
        assertEquals("a1", segments[0].anchorId)
        assertEquals(null, segments[0].sender)
        assertEquals(
            listOf("read_note", "delegate_to_subagent"),
            segments[0].toolSteps.map { it.call.name },
        )
    }

    @Test fun reasoningComesBeforeCallsOfSameRound() {
        val rows = listOf(
            row("a1", "t1", 1, toolCallsJson = calls("web_search"), reasoningText = "Tôi nên tìm tin mới nhất."),
        )
        val segments = groupWorkSegments(rows, results())
        assertEquals(1, segments.size)
        assertEquals(2, segments[0].steps.size)
        assertTrue(segments[0].steps[0] is WorkStep.Reasoning)
        assertEquals("Tôi nên tìm tin mới nhất.", (segments[0].steps[0] as WorkStep.Reasoning).text)
        assertEquals("web_search", (segments[0].steps[1] as WorkStep.Tool).call.name)
    }

    @Test fun reasoningOnlyRowFormsSegment() {
        val rows = listOf(
            row("a1", "t1", 1, text = "Đây là đáp án.", reasoningText = "Tôi đã cân nhắc hai cách."),
        )
        val segments = groupWorkSegments(rows, results())
        assertEquals(1, segments.size)
        assertEquals(1, segments[0].steps.size)
        assertTrue(segments[0].steps[0] is WorkStep.Reasoning)
    }

    @Test fun textRowBreaksSegment() {
        val rows = listOf(
            row("a1", "t1", 1, toolCallsJson = calls("web_search")),
            row("a2", "t1", 2, text = "Tôi tìm thấy vài tin, để tôi đọc thêm."),
            row("a3", "t1", 3, toolCallsJson = calls("web_fetch")),
        )
        val segments = groupWorkSegments(rows, results())
        assertEquals(2, segments.size)
        assertEquals(listOf("a1", "a3"), segments.map { it.anchorId })
    }

    @Test fun subToolRowsGroupUnderSubSenderAndGreetingIsNotAStep() {
        val subCalls = calls("youtube_search", "youtube_media_play")
        val rows = listOf(
            row("u1", "t1", 1, role = "user", text = "mở nhạc gì vui vui đi"),
            row("a1", "t1", 2, toolCallsJson = calls("delegate_to_subagent")),
            row("s0", "t1", 3, text = "Chào bạn, mình sẽ tìm nhạc ngay đây!", sender = "monica"),
            row("st1", "t1", 4, sender = "monica", toolCallsJson = subCalls),
            row("rt1", "t1", 5, role = "tool", text = "Đang phát: Morning Coffee", toolCallId = "c0"),
            row("st2", "t1", 6, sender = "monica", toolCallsJson = calls("youtube_media_play")),
            row("s1", "t1", 7, text = "Mình đã phát nhạc rồi nhé!", sender = "monica"),
        )
        val segments = groupWorkSegments(rows, results(rows[4]))
        // Main's delegate row is its own segment; Monica's two tool rows
        // merge into one segment under her sender.
        assertEquals(2, segments.size)
        assertEquals(null, segments[0].sender)
        assertEquals("monica", segments[1].sender)
        assertEquals("st1", segments[1].anchorId)
        assertEquals(
            listOf("youtube_search", "youtube_media_play", "youtube_media_play"),
            segments[1].toolSteps.map { it.call.name },
        )
        // The first call's result attaches; the later calls have none yet.
        assertEquals("Đang phát: Morning Coffee", segments[1].toolSteps[0].result?.text)
        assertEquals(null, segments[1].toolSteps[1].result)
    }

    @Test fun toolResultRowsAreNotSteps() {
        val rows = listOf(
            row("a1", "t1", 1, toolCallsJson = calls("web_search")),
            row("r1", "t1", 2, role = "tool", text = "3 kết quả", toolCallId = "c0"),
        )
        val segments = groupWorkSegments(rows, results(rows[1]))
        assertEquals(1, segments.size)
        assertEquals(1, segments[0].steps.size)
    }

    @Test fun differentTurnsAndSendersSplit() {
        val rows = listOf(
            row("a1", "t1", 1, toolCallsJson = calls("web_search")),
            row("a2", "t2", 2, toolCallsJson = calls("web_search")),
            row("s1", "t2", 3, sender = "bob", toolCallsJson = calls("web_fetch")),
        )
        val segments = groupWorkSegments(rows, results())
        assertEquals(3, segments.size)
        assertEquals(listOf("a1", "a2", "s1"), segments.map { it.anchorId })
    }

    @Test fun unparseableToolCallsAndBlankRowsAreSkipped() {
        val rows = listOf(
            row("a1", "t1", 1, toolCallsJson = "not json"),
            row("a2", "t1", 2, text = ""),
            row("a3", "t1", 3, toolCallsJson = calls("web_search")),
        )
        val segments = groupWorkSegments(rows, results())
        assertEquals(1, segments.size)
        assertEquals("a3", segments[0].anchorId)
    }

    @Test fun streamingPlaceholderIsNotAStep() {
        val rows = listOf(
            row("a1", "t1", 1, status = "streaming"),
        )
        assertEquals(emptyList<WorkSegment>(), groupWorkSegments(rows, results()))
    }

    @Test fun emptyListGivesNoSegments() {
        assertEquals(emptyList<WorkSegment>(), groupWorkSegments(emptyList(), results()))
    }
}
