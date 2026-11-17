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
 * A row contributes when it holds tool calls or readable reasoning. Rows
 * that contribute and follow each other with the same (turnId, sender)
 * merge into one segment; any other row — a user message, a plain text
 * message (a greeting, a final answer), a tool-result row — breaks the
 * stretch. A text-only message therefore never becomes a step, and
 * `send_message` never becomes one either: its messages already are.
 * Within one row the reasoning step comes first (the model thinks, then
 * calls), then that round's calls in order. Tool results attach from
 * [results] by call id; a call with no result yet is still a step, so the
 * pill can stream it as running.
 */
fun groupWorkSegments(messages: List<MessageEntity>, results: ToolResults): List<WorkSegment> {
    val segments = mutableListOf<WorkSegment>()
    var currentRows: MutableList<MessageEntity>? = null
    var currentTurn: String? = null
    var currentSender: String? = null
    fun flush() {
        val rows = currentRows ?: return
        currentRows = null
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
        if (steps.isNotEmpty()) {
            // Every row in the stretch shares one (turnId, sender) by the
            // merge rule above, so the first row's values describe it.
            segments += WorkSegment(
                turnId = rows.first().turnId,
                sender = rows.first().sender,
                anchorId = rows.first().id,
                steps = steps,
            )
        }
    }
    for (m in messages) {
        if (m.role != "assistant") {
            flush()
            continue
        }
        val calls = parseCalls(m.toolCallsJson)
        val hasReasoning = !m.reasoningText.isNullOrBlank()
        if (calls.isEmpty() && !hasReasoning) {
            flush()
            continue
        }
        if (currentRows == null || m.turnId != currentTurn || m.sender != currentSender) {
            flush()
            currentRows = mutableListOf()
            currentTurn = m.turnId
            currentSender = m.sender
        }
        currentRows!!.add(m)
    }
    flush()
    return segments
}
