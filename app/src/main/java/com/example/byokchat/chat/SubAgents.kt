package com.example.byokchat.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ============ Sub-agents (Phase 2: synchronous) ============
//
// A sub-agent is a specialist team member: Main delegates a task to it, it
// runs synchronously inside Main's turn (Main waits), and when it finishes
// it sends two things — a detailed report back to Main (the delegate tool's
// result) and a brief report to the user (its own message in the chat).
// No sub-sub: flat, one level only — Main controls everything.
//
// Phase 2 fixes the team in code (one demo agent, Research). The
// team-creation UI and per-agent settings screen arrive in Phase 4.

/** One member of the team: a specialist sub-agent. */
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
    /** Ceilings, set per agent at team creation (defaults from the design). */
    val roundCap: Int = DEFAULT_ROUND_CAP,
    val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
) {
    companion object {
        const val DEFAULT_ROUND_CAP = 10
        const val DEFAULT_TOKEN_BUDGET = 32_000
    }
}

/** The team. Phase 2 has one demo agent; more agents join with the team UI in Phase 4. */
object SubAgents {
    val RESEARCH = SubAgentConfig(
        id = "research",
        name = "Research",
        role = "Research agent",
        description = "Researches a topic on the web and reports back with sources.",
        systemPrompt = """
            You are Research, a sub-agent of the Tov chat app. You work for Main (the main agent), who gave you one task in the brief below. You only know what is in the brief — you cannot see the rest of the conversation, and you do not know the user.
            Your workspace folder is "Research" inside the AI playground.
            Use your tools (web_search, fetch_url) to complete the task. Prefer fresh sources; say which sources you used.
            Write your final answer in the same language as the brief. End it with a short summary for the user after a line that contains exactly ---BRIEF---. The summary is one or two short sentences: what you did, or what blocked you (a missing tool, a blocked page, not enough data). Example ending:
            ---BRIEF---
            Done — I found 5 sources about ...
            If you cannot finish, say so in the report and in the summary — never invent results.
        """.trimIndent(),
        toolUids = listOf("web.search", "web.fetch"),
        workspaceFolder = "Research",
        defaultGreeting = "Hi, I'm Research — I'll look into this right away.",
    )

    val all: List<SubAgentConfig> = listOf(RESEARCH)

    fun byId(id: String): SubAgentConfig? = all.firstOrNull { it.id == id }

    /** Find an agent by display name (used when importing history.md, where the heading carries the name). */
    fun byName(name: String): SubAgentConfig? = all.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Display name for a sender stored in Room (an id, or a raw name from an edited file). */
    fun displayName(sender: String): String = byId(sender)?.name ?: byName(sender)?.name ?: sender
}

/** How a sub run ended. */
enum class SubRunStatus { DONE, FAILED, ROUND_CAP, BUDGET_EXCEEDED }

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
)

/** First line of sub_runs.meta.json (JSONL). */
@Serializable
data class SubRunsHeader(
    val version: Int = 1,
    val session: String,
)

/**
 * Split a sub-agent's final answer into (detail, brief): the brief is the
 * text after a line that is exactly ---BRIEF---; without the marker, the
 * brief is the first paragraph (up to 300 chars) and the detail is the whole
 * answer. Pure, so it can be unit-tested without Android.
 */
fun splitBriefDetail(answer: String): Pair<String, String> {
    val lines = answer.lines()
    val idx = lines.indexOfFirst { it.trim() == "---BRIEF---" }
    if (idx >= 0) {
        val detail = lines.subList(0, idx).joinToString("\n").trim()
        val brief = lines.subList(idx + 1, lines.size).joinToString("\n").trim()
        val d = detail.ifBlank { brief }
        val b = brief.ifBlank { d.take(300) }
        return d to b
    }
    val trimmed = answer.trim()
    val firstPara = trimmed.split(Regex("\n\\s*\n")).firstOrNull().orEmpty().trim()
    return trimmed to firstPara.take(300).ifBlank { trimmed.take(300) }
}

/** Encode one sub-run record as a JSONL line. */
fun encodeSubRunRecord(record: SubRunRecord): String =
    Json { encodeDefaults = true }.encodeToString(record)

/** Encode the sub_runs.meta.json header line. */
fun encodeSubRunsHeader(header: SubRunsHeader): String =
    Json { encodeDefaults = true }.encodeToString(header)
