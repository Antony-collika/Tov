package com.example.byokchat.ui

import com.example.byokchat.chat.decodeSubRunStrings
import com.example.byokchat.data.SubRunEntity

/** One message in the Main↔sub conversation of a session. */
data class SubConversationItem(
    val key: String,
    /** True = Main's side, false = the sub's side. */
    val fromMain: Boolean,
    val text: String,
)

/**
 * The stored full report's machine header ("Sub-agent X finished: …",
 * "Task: …", "Rounds: …") is scaffolding the runner adds, not the sub's
 * own words — strip it so the conversation reads like the exchange it
 * is. The "Messages from X to Main:" section is stripped too: those
 * lines already appear as their own bubbles just above. Returns the
 * report body, or "" when nothing but scaffolding is left.
 */
internal fun stripReportHeader(fullReport: String, agentName: String): String {
    val lines = fullReport.lines()
    var i = 0
    // The runner always writes a "Rounds: …" line at the end of the
    // header (the brief above it may span several lines, so the header
    // cannot be stripped line by line from the top). Everything after
    // the blank line following it is the report body.
    val roundsAt = lines.indexOfFirst { it.startsWith("Rounds: ") }
    if (roundsAt >= 0) {
        i = roundsAt + 1
        while (i < lines.size && lines[i].startsWith("Note: ")) i++
    } else {
        // Old or unexpected shape: drop leading header-looking lines.
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("Sub-agent ") && line.contains(" finished: ")) { i++; continue }
            if (line.startsWith("Task: ") || line.startsWith("Tools called: ") ||
                line.startsWith("Rounds: ") || line.startsWith("Note: ")
            ) { i++; continue }
            break
        }
    }
    while (i < lines.size && lines[i].isBlank()) i++
    // The outbox section: its lines are already separate bubbles above.
    if (i < lines.size && lines[i].trim() == "Messages from $agentName to Main:") {
        i++
        while (i < lines.size && lines[i].startsWith("- ")) i++
        while (i < lines.size && lines[i].isBlank()) i++
    }
    return lines.drop(i).joinToString("\n").trim()
}

/**
 * Builds the full Main↔sub conversation of one session — Main on one
 * side, the sub on the other, in time order across the whole session,
 * not per turn (17/11/2026, mockup v2). Tapping the sub's avatar in ANY
 * turn opens this same conversation.
 *
 * - Main's side: each run's brief (the task Main gave), at the run's start.
 * - Sub's side: the messages it sent to Main ([SubRunEntity.messagesToMainJson],
 *   in order) and its final report ([SubRunEntity.fullReport], header
 *   stripped) at the run's end. The messages the sub sent to the USER
 *   are NOT here: they already live on the main chat screen, and
 *   showing them again here made the two screens duplicate each other
 *   (18/11/2026). This screen is only the Main↔sub exchange.
 * - Runs from before the sub_runs table existed have no record: they
 *   simply contribute nothing — the screen is empty for them, no
 *   backfill, no special case.
 * Pure, so it can be unit-tested without Android.
 */
fun buildSubConversation(
    runs: List<SubRunEntity>,
): List<SubConversationItem> {
    data class Timed(val at: Long, val order: Int, val item: SubConversationItem)
    val timed = mutableListOf<Timed>()
    var order = 0
    for (run in runs) {
        val brief = run.brief.ifBlank { run.title }
        if (brief.isNotBlank()) {
            timed += Timed(
                run.startedAt, order++,
                SubConversationItem("run-${run.id}-brief", fromMain = true, text = brief),
            )
        }
        decodeSubRunStrings(run.messagesToMainJson).forEachIndexed { idx, text ->
            if (text.isNotBlank()) {
                timed += Timed(
                    run.finishedAt, order++,
                    SubConversationItem("run-${run.id}-main-$idx", fromMain = false, text = text),
                )
            }
        }
        val body = stripReportHeader(run.fullReport, run.agentName)
        if (body.isNotBlank() && body != "(no report text)") {
            timed += Timed(
                run.finishedAt, order++,
                SubConversationItem("run-${run.id}-report", fromMain = false, text = body),
            )
        }
    }
    // Stable sort by time; ties (a run's outbox and report share
    // finishedAt) keep insertion order: brief, outbox, report.
    return timed.sortedWith(compareBy({ it.at }, { it.order })).map { it.item }
}
