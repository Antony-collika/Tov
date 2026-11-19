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
 * One stretch of work by one speaker in one turn — what the pill groups
 * (17/11/2026, approved mockup): the pill streams the latest step while
 * the agent works, and expands into the table of all its steps.
 * [anchorId] is the first row of the stretch; the pill renders there, and
 * the other rows of the stretch render no pill of their own.
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
 * Groups an agent's work into segments. Pure, so it can be unit-tested
 * without Android.
 *
 * One segment per (turnId, sender): every tool/reasoning row of one
 * speaker in one turn belongs to one segment, wherever it sits in the
 * chat (19/11/2026: the old consecutive-only rule split one agent's
 * turn into one pill per round as soon as two subs interleaved or a
 * greeting landed in between — the pill is background work, not a
 * chat bubble, so one agent's one turn gets one pill). A user message
 * or a plain text message (a greeting, a final answer) contributes
 * nothing and never splits a segment. A tool-result row never
 * contributes a step either: it sits between the rounds in the DB
 * (call row, result row, next call row) and is the answer to the step
 * above it. A text-only message therefore never becomes a step, and
 * `send_message` never becomes one either: its messages already are.
 * Segments come out in first-appearance order; within one segment the
 * steps stay in chat order. Within one row the reasoning step comes
 * first (the model thinks, then calls), then that round's calls in
 * order. Tool results attach from [results] by call id; a call with
 * no result yet is still a step, so the pill can stream it as running.
 */
fun groupWorkSegments(messages: List<MessageEntity>, results: ToolResults): List<WorkSegment> {
    val byKey = linkedMapOf<Pair<String, String?>, MutableList<MessageEntity>>()
    for (m in messages) {
        // A tool-result row is the answer to the step above it; it
        // neither contributes a step nor splits a segment.
        if (m.role == "tool") continue
        if (m.role != "assistant") continue
        val calls = parseCalls(m.toolCallsJson)
        val hasReasoning = !m.reasoningText.isNullOrBlank()
        if (calls.isEmpty() && !hasReasoning) continue
        byKey.getOrPut(m.turnId to m.sender) { mutableListOf() }.add(m)
    }
    return byKey.values.mapNotNull { rows ->
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
        if (steps.isEmpty()) return@mapNotNull null
        // Every row in the segment shares one (turnId, sender) by the
        // grouping above, so the first row's values describe it.
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
        )
    }
}
