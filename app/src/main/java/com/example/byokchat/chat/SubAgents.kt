package com.example.byokchat.chat

import kotlinx.serialization.Serializable
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
// No agent is hardcoded in code. The team lives in agents.json (see
// SubAgentStore): the file is the source of truth, the Agent settings
// screen edits the file, and the delegate tool is built from the loaded
// team. Research below only seeds that file on first launch — change the
// file (or the settings screen) and the team changes; the code does not
// keep a copy.

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
    /** Greeting Main scripts for the agent when it joins the room (used when Main does not pass one). */
    val defaultGreeting: String,
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
        defaultGreeting = defaultGreeting.trim(),
        roundCap = roundCap.coerceIn(MIN_ROUND_CAP, MAX_ROUND_CAP),
        tokenBudget = tokenBudget.coerceIn(MIN_TOKEN_BUDGET, MAX_TOKEN_BUDGET),
        modelProviderId = modelProviderId?.trim()?.takeIf { it.isNotBlank() },
        model = model?.trim()?.takeIf { it.isNotBlank() },
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

/** The seed team written to agents.json on first launch: Research only. */
fun defaultResearchAgent(): SubAgentConfig = SubAgentConfig(
    id = "research",
    name = "Research",
    role = "Research agent",
    description = "Researches a topic on the web and reports back with sources.",
    systemPrompt = """
        You are Research, a sub-agent of the Tov chat app. You work for Main (the main agent), who gave you one task in the brief below. You only know what is in the brief — you cannot see the rest of the conversation, and you do not know the user.
        Your workspace folder is "Research" inside the AI playground.
        Use your tools (web_search, fetch_url) to complete the task. Prefer fresh sources; say which sources you used.
        Write your final answer in the same language as the brief. Your final answer goes to Main only — the user never sees it.
        To say something to the user, call the send_message tool with receiver "user": use it once at the end for a short summary (one or two sentences: what you did, or what blocked you — a missing tool, a blocked page, not enough data). To pass a note to Main before you finish, call send_message with receiver "main".
        If you cannot finish, say so in the report and in the summary — never invent results.
    """.trimIndent(),
    toolUids = listOf("web.search", "web.fetch"),
    workspaceFolder = "Research",
    defaultGreeting = "Hi, I'm Research — I'll look into this right away.",
)

fun defaultTeam(): SubAgentTeam = SubAgentTeam(agents = listOf(defaultResearchAgent()))

/** How a sub run ended. */
enum class SubRunStatus { DONE, FAILED, ROUND_CAP, BUDGET_EXCEEDED, INTERRUPTED }

/** The outcome of one sub run: the brief for the user, the detail for Main. */
data class SubRunResult(
    val agentId: String,
    val agentName: String,
    /** Brief report, shown to the user as the sub-agent's own message. */
    val brief: String,
    /** Detailed report, returned to Main as the delegate tool's result. */
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
)

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
