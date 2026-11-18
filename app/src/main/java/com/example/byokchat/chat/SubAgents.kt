package com.example.byokchat.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ============ Sub-agents (Phase 3: user-configurable team) ============
//
// A sub-agent is a specialist team member: Main delegates a task to it, it
// runs synchronously inside Main's turn (Main waits), and when it finishes
// it sends two things — a detailed report back to Main (the delegate tool's
// result) and a brief report to the user (via its send_message tool call).
// No sub-sub: flat, one level only — Main controls everything.
//
// No agent is hardcoded in code, and none is shipped: the team starts
// empty and the user adds agents herself (in the Agent settings screen).
// The team lives in agents.json (see SubAgentStore): the file is the
// source of truth, the Agent settings screen edits the file, and the
// delegate tool is built from the loaded team. There is no seed.

/** How much of Main's conversation a sub-agent may see. */
enum class HistoryVisibility {
    /** Only the brief Main gives it — the default (least context, least noise). */
    BRIEF_ONLY,
    /** The brief plus Main's recent conversation history. */
    SEES_HISTORY,
}

/** One member of the team: a specialist sub-agent. */
@Serializable
data class SubAgentConfig(
    /** Stable id, stored as the sender of the agent's messages. */
    val id: String,
    /** Display name (shown in the chat header and in history.md). */
    val name: String,
    /** Short role label, shown on the Members screen. */
    val role: String,
    /** Short description, for Main to read when picking an agent. */
    val description: String,
    /** System prompt the sub reads (distinct from Main's). */
    val systemPrompt: String,
    /** Uids of the tools this agent may use — fixed per agent, no overlap. */
    val toolUids: List<String>,
    /** The agent's own workspace folder inside the AI playground. */
    val workspaceFolder: String,
    /** Ceilings, set per agent in its settings (defaults from the design). */
    val roundCap: Int = DEFAULT_ROUND_CAP,
    val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    /** Off = Main cannot call this agent (it stays in the team, editable). */
    val enabled: Boolean = true,
    /** The agent's own model (optional): provider id + model name. Unset = follow Main's current model. */
    val modelProviderId: String? = null,
    val model: String? = null,
    /** What the agent may see beyond its brief. */
    val historyVisibility: HistoryVisibility = HistoryVisibility.BRIEF_ONLY,
    /**
     * Optional linked config file (relative playground path or content://
     * URI): the fields above are kept in sync with it — the Agent settings
     * screen re-reads it on open and writes it back on save. Null = the
     * fields live in agents.json only.
     */
    val configFile: String? = null,
) {
    companion object {
        const val DEFAULT_ROUND_CAP = 10
        const val DEFAULT_TOKEN_BUDGET = 32_000
        const val MIN_ROUND_CAP = 1
        const val MAX_ROUND_CAP = 50
        const val MIN_TOKEN_BUDGET = 8_000
        const val MAX_TOKEN_BUDGET = 200_000
    }

    /** True when this agent has its own model instead of following Main. */
    val hasOwnModel: Boolean get() = modelProviderId != null && model != null

    /** Clamp ceilings into their allowed ranges and trim text fields. Never throws. */
    fun sanitized(): SubAgentConfig = copy(
        id = id.trim(),
        name = name.trim(),
        role = role.trim(),
        description = description.trim(),
        systemPrompt = systemPrompt.trim(),
        toolUids = toolUids.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        workspaceFolder = workspaceFolder.trim(),
        roundCap = roundCap.coerceIn(MIN_ROUND_CAP, MAX_ROUND_CAP),
        tokenBudget = tokenBudget.coerceIn(MIN_TOKEN_BUDGET, MAX_TOKEN_BUDGET),
        modelProviderId = modelProviderId?.trim()?.takeIf { it.isNotBlank() },
        model = model?.trim()?.takeIf { it.isNotBlank() },
        configFile = configFile?.trim()?.takeIf { it.isNotBlank() },
    )

    /** Problems the settings screen shows instead of saving silently. Empty = OK. */
    fun validate(): List<String> = buildList {
        if (id.isBlank()) add("Agent id is empty.")
        if (name.isBlank()) add("Name is empty.")
        if (workspaceFolder.isBlank()) add("Workspace folder is empty.")
        if (systemPrompt.isBlank()) add("System prompt is empty.")
        if ((modelProviderId == null) != (model == null)) {
            add("Model needs both a provider and a model name, or neither (follow Main).")
        }
    }
}

/** The whole team, as stored in agents.json. */
@Serializable
data class SubAgentTeam(
    val version: Int = 1,
    val agents: List<SubAgentConfig> = emptyList(),
) {
    fun byId(id: String): SubAgentConfig? = agents.firstOrNull { it.id == id }

    /** Find an agent by display name (used when importing history.md, where the heading carries the name). */
    fun byName(name: String): SubAgentConfig? = agents.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Display name for a sender stored in Room (an id, or a raw name from an edited file). */
    fun displayName(sender: String): String = byId(sender)?.name ?: byName(sender)?.name ?: sender

    /** Agents Main may call: enabled ones only. */
    fun enabledAgents(): List<SubAgentConfig> = agents.filter { it.enabled }

    /** Sanitize every agent and drop later agents whose id is already taken. Never throws. */
    fun sanitized(): SubAgentTeam {
        val seen = mutableSetOf<String>()
        return copy(
            agents = agents.map { it.sanitized() }.filter { it.id.isNotBlank() && seen.add(it.id) },
        )
    }

    /** Problems in the team (duplicate ids, per-agent problems). Empty = OK. */
    fun validate(): List<String> = buildList {
        val ids = agents.map { it.id }
        if (ids.size != ids.toSet().size) add("Two agents share the same id.")
        agents.forEach { addAll(it.validate().map { err -> "${it.id.ifBlank { it.name }}: $err" }) }
    }
}

/** The team starts empty: no agent is shipped in code. The user adds agents herself. */
fun emptyTeam(): SubAgentTeam = SubAgentTeam(agents = emptyList())

/** How a sub run ended. */
enum class SubRunStatus { DONE, FAILED, ROUND_CAP, BUDGET_EXCEEDED, INTERRUPTED }

/** The outcome of one sub run: the brief for the user, the detail for Main. */
data class SubRunResult(
    val agentId: String,
    val agentName: String,
    /** Brief report, shown to the user as the sub-agent's own message. */
    val brief: String,
    /** Detailed report, stored in the sub_runs table (and returned to Main as a tool error when the run failed). */
    val detailed: String,
    val status: SubRunStatus,
    val rounds: Int,
    /** Rough token estimate of the sub's largest request. */
    val tokensUsed: Int,
    /** Names of the tools the sub called, in order. */
    val toolCalls: List<String>,
    val startedAt: Long,
    val finishedAt: Long,
    val error: String? = null,
    /** One-line title Main gave the task when delegating. */
    val title: String,
    /** Stable turn number of Main's turn this run belongs to. */
    val turnNo: Int,
    /** Short receipt, returned to Main as the delegate tool's result. */
    val receipt: String,
)

/** One tool call in a stored run: name + arguments (JSON string). Results
 * stay in the turn's message rows; the full report carries the outcome. */
@Serializable
data class SubRunToolCall(val name: String, val args: String)

private val subRunToolCallsSer = ListSerializer(SubRunToolCall.serializer())
private val subRunStringsSer = ListSerializer(String.serializer())

/** Encode a run's tool calls (with arguments) for the sub_runs table. */
fun encodeSubRunToolCalls(calls: List<SubRunToolCall>): String =
    Json.encodeToString(subRunToolCallsSer, calls)

/** Decode a run's tool calls; bad data reads back as an empty list. */
fun decodeSubRunToolCalls(json: String): List<SubRunToolCall> =
    runCatching { Json.decodeFromString(subRunToolCallsSer, json) }.getOrDefault(emptyList())

/** Encode a run's messages to Main for the sub_runs table. */
fun encodeSubRunStrings(values: List<String>): String =
    Json.encodeToString(subRunStringsSer, values)

/** Decode a run's messages to Main; bad data reads back as an empty list. */
fun decodeSubRunStrings(json: String): List<String> =
    runCatching { Json.decodeFromString(subRunStringsSer, json) }.getOrDefault(emptyList())

/**
 * The short receipt Main keeps in its context after a delegate (context
 * manager, 17/11/2026): who ran, what the task was called, which turn it
 * was, and how to read the full result back. The full report stays in
 * the stored run record, out of Main's context until it asks for it.
 * Pure, so it can be unit-tested without Android.
 */
fun buildSubRunReceipt(agentName: String, agentId: String, title: String, turnNo: Int, status: SubRunStatus): String =
    "Sub-agent $agentName finished: ${status.name.lowercase().replace('_', ' ')}. " +
        "Task: \"$title\". Turn: $turnNo. " +
        "To read the full result, call get_subagent_result with agent \"$agentId\" and turn $turnNo."

/**
 * The receipt Main gets when a run STARTS (Phase 4 async): the run is
 * still going, so there is no status yet — just the agent, the task
 * title and the turn number, so Main can read the full result back with
 * get_subagent_result once the sub-agent's message arrives.
 */
fun buildSubRunStartedReceipt(agentName: String, agentId: String, title: String, turnNo: Int): String =
    "Sub-agent $agentName started: \"$title\". Turn: $turnNo. " +
        "The run is still going — its result arrives as the sub-agent's own message in the chat. " +
        "To read the full result, call get_subagent_result with agent \"$agentId\" and turn $turnNo."

/**
 * Whether a finished run wakes Main at once (wake, GĐ4 batch): a run
 * that failed, ran out of rounds or ran out of tokens is an error worth
 * waking for. A run Main or the user stopped (INTERRUPTED) is not — it
 * just counts as finished for the all-done wake.
 */
fun isWakeError(status: SubRunStatus): Boolean =
    status == SubRunStatus.FAILED || status == SubRunStatus.ROUND_CAP || status == SubRunStatus.BUDGET_EXCEEDED

/**
 * The text Main reads in its wake turn (wake, GĐ4 batch): a short header
 * plus one block per finished run — agent, task title, turn, status, and
 * the run's detailed report verbatim (provider error text stays as the
 * provider wrote it). Pure, so it can be unit-tested without Android.
 */
fun buildWakeText(outcomes: List<SubRunResult>): String = buildString {
    appendLine("Sub-agent results:")
    outcomes.forEach { r ->
        appendLine()
        appendLine("${r.agentName} — \"${r.title}\" (turn ${r.turnNo}): ${r.status.name.lowercase().replace('_', ' ')}")
        appendLine(r.detailed)
    }
}.trim()

/** One line of sub_runs.meta.json (JSONL): one entry per sub run in a session. */
@Serializable
data class SubRunRecord(
    val version: Int = 1,
    val agent: String,
    val agentName: String,
    /** The brief Main gave the agent. */
    val brief: String,
    val status: String,
    val rounds: Int,
    val tokens: Int,
    val toolCalls: List<String> = emptyList(),
    val startedAt: Long,
    val finishedAt: Long,
    /** First part of the detailed report, so the file stays readable. */
    val resultSummary: String = "",
    val error: String? = null,
    /** Messages the sub sent to the user (count) and to Main (texts, for the journal). */
    val sentToUser: Int = 0,
    val messagesToMain: List<String> = emptyList(),
)

/** First line of sub_runs.meta.json (JSONL). */
@Serializable
data class SubRunsHeader(
    val version: Int = 1,
    val session: String,
)

/**
 * The brief the user sees for a run: the last message the sub sent to the
 * user with the send_message tool, or — when it never sent one — the first
 * paragraph of its final answer (up to 300 chars). Pure.
 *
 * (Phase 2 had splitBriefDetail, which split on a ---BRIEF--- marker in the
 * sub's answer. Phase 3 removed the marker: the sub sends its brief with
 * the send_message tool instead. The marker never reached stored data —
 * it only ever existed in the transient final-answer text — so no old
 * history needs the split, and the function is gone.)
 */
fun fallbackBrief(finalText: String, sentToUser: List<String>): String {
    val sent = sentToUser.lastOrNull { it.isNotBlank() }
    if (sent != null) return sent
    val trimmed = finalText.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.split(Regex("\n\\s*\n")).firstOrNull().orEmpty().trim().take(300)
        .ifBlank { trimmed.take(300) }
}

/** Encode one sub-run record as a JSONL line. */
fun encodeSubRunRecord(record: SubRunRecord): String =
    Json { encodeDefaults = true }.encodeToString(record)

/** Encode the sub_runs.meta.json header line. */
fun encodeSubRunsHeader(header: SubRunsHeader): String =
    Json { encodeDefaults = true }.encodeToString(header)
