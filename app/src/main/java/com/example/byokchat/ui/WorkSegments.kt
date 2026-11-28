package com.example.byokchat.ui

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.data.MessageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val callsSer = ListSerializer(AiToolCall.serializer())

/**
 * One step in an agent's stretch of work: a tool call (with its result when
 * the tool has answered), or a readable reasoning passage the provider
 * returned. Reasoning is only a step when the provider actually returned
 * readable text — Gemini never writes `reasoningText` (it only keeps an
 * opaque thought signature), so with Gemini a segment holds tool calls
 * only; nothing here invents reasoning.
 */
sealed interface WorkStep {
    data class Tool(val call: AiToolCall, val result: MessageEntity?) : WorkStep
    data class Reasoning(val text: String) : WorkStep
}

/**
 * One burst of work by one speaker — what the work stream groups
 * (28/11/2026 revision, Duy): one burst = consecutive tool/reasoning
 * rows by one speaker, with no text row in between. The old 19/11 rule
 * (one segment per speaker per turn, wherever the rows sit) is GONE for
 * the new text UI: agents run async and interleave, and merging a whole
 * turn hid who was working where. A burst renders under its own
 * speaker's Avatar + Name line, at the place it happened.
 * [anchorId] is the first row of the burst; the stream renders there
 * (or on the text message it is attached to — see ChatScreen), and the
 * other rows of the burst render no stream of their own.
 */
data class WorkSegment(
    val turnId: String,
    /** Null = Main; otherwise the sub-agent id (sender stored in Room). */
    val sender: String?,
    val anchorId: String,
    val steps: List<WorkStep>,
    /** When the stretch's first row was written (the work started). */
    val startedAt: Long,
    /** When the stretch's last row (call or result) was written. */
    val lastActivityAt: Long,
) {
    val toolSteps: List<WorkStep.Tool> get() = steps.filterIsInstance<WorkStep.Tool>()
    val hasTools: Boolean get() = toolSteps.isNotEmpty()
}

private fun parseCalls(toolCallsJson: String?): List<AiToolCall> =
    runCatching { Json.decodeFromString(callsSer, toolCallsJson ?: "[]") }.getOrDefault(emptyList())

/**
 * Groups an agent's work into bursts. Pure, so it can be unit-tested
 * without Android.
 *
 * One burst = consecutive tool/reasoning rows by one speaker in one
 * turn (28/11/2026 revision, Duy — supersedes the 19/11/2026 one-pill-
 * per-speaker-per-turn rule). A burst ends as soon as any other row
 * appears in between: a text message (any sender — a greeting, an
 * answer), a work row by another speaker, a user message, or a new
 * turn. A tool-result row never contributes a step and never breaks a
 * burst: it sits between the rounds in the DB (call row, result row,
 * next call row) and is the answer to the step above it. A text-only
 * message therefore never becomes a step, and `send_message` never
 * becomes one either: its messages already are. A work row that also
 * carries text (a reasoning row whose text is the answer) ends its
 * burst right after itself, so later work starts a new burst.
 * Bursts come out in first-appearance order; within one burst the
 * steps stay in chat order. Within one row the reasoning step comes
 * first (the model thinks, then calls), then that round's calls in
 * order. Tool results attach from [results] by call id; a call with
 * no result yet is still a step, so the stream can show it as running.
 */
fun groupWorkSegments(messages: List<MessageEntity>, results: ToolResults): List<WorkSegment> {
    val segments = mutableListOf<WorkSegment>()
    var current: MutableList<MessageEntity>? = null
    fun flush() {
        val rows = current ?: return
        current = null
        val steps = rows.flatMap { row ->
            val reasoning = if (!row.reasoningText.isNullOrBlank()) {
                listOf(WorkStep.Reasoning(row.reasoningText!!))
            } else {
                emptyList()
            }
            val tools = parseCalls(row.toolCallsJson).map { call ->
                WorkStep.Tool(call, results.byCallId[call.id])
            }
            reasoning + tools
        }
        if (steps.isEmpty()) return
        segments.add(
            WorkSegment(
                turnId = rows.first().turnId,
                sender = rows.first().sender,
                anchorId = rows.first().id,
                steps = steps,
                startedAt = rows.first().createdAt,
                lastActivityAt = maxOf(
                    rows.maxOf { it.createdAt },
                    steps.filterIsInstance<WorkStep.Tool>().mapNotNull { it.result?.createdAt }.maxOrNull() ?: 0L,
                ),
            ),
        )
    }
    for (m in messages) {
        // A tool-result row is the answer to the step above it; it
        // neither contributes a step nor breaks a burst.
        if (m.role == "tool") continue
        if (m.role != "assistant") {
            flush()
            continue
        }
        val calls = parseCalls(m.toolCallsJson)
        val hasReasoning = !m.reasoningText.isNullOrBlank()
        if (calls.isEmpty() && !hasReasoning) {
            // A text row breaks the burst (any sender — see doc above).
            flush()
            continue
        }
        // A work row: start a new burst unless the current one is the
        // same speaker in the same turn (i.e. directly consecutive).
        val cur = current
        if (cur == null || cur.first().turnId != m.turnId || cur.first().sender != m.sender) {
            flush()
            current = mutableListOf()
        }
        current!!.add(m)
        // A work row that also carries text ends its burst here: the
        // text was already spoken, so later work is a new burst.
        if (m.text.isNotBlank()) flush()
    }
    flush()
    return segments
}

/**
 * Where each burst renders (28/11/2026 revision, Duy): the next text
 * message from the same speaker in the same turn, so one block reads
 * Avatar + Name → work → text. Returns anchorId -> host message id,
 * or null when the burst has no such text and stays standalone at its
 * anchor (rendered there with its own header). Pure, so it can be
 * unit-tested without Android. [messages] must be in chat order.
 */
fun segmentHosts(segments: List<WorkSegment>, messages: List<MessageEntity>): Map<String, String?> {
    val anchorIds = segments.map { it.anchorId }.toSet()
    val indexById = messages.mapIndexed { i, m -> m.id to i }.toMap()
    return segments.associate { seg ->
        val anchorIdx = indexById[seg.anchorId]
        var host: String? = null
        if (anchorIdx != null) {
            val anchorRow = messages[anchorIdx]
            if (anchorRow.text.isNotBlank()) {
                host = seg.anchorId
            } else {
                for (i in anchorIdx + 1 until messages.size) {
                    val m = messages[i]
                    if (m.role == "user" || m.turnId != seg.turnId) break
                    if (m.role != "assistant" || m.sender != seg.sender) continue
                    if (m.text.isNotBlank()) {
                        host = m.id
                        break
                    }
                    // Another work burst by the same speaker: this
                    // burst has no host of its own.
                    if (m.id in anchorIds) break
                }
            }
        }
        seg.anchorId to host
    }
}
