package com.example.byokchat.tools

import com.example.byokchat.chat.DiagnosticsLog
import com.example.byokchat.chat.SubAgentConfig
import com.example.byokchat.chat.SubAgentRunner
import com.example.byokchat.chat.SubRunStatus
import com.example.byokchat.chat.decodeSubRunToolCalls
import com.example.byokchat.data.SubRunEntity
import kotlinx.serialization.json.JsonObject

/**
 * Tool that lets Main delegate a task to a sub-agent (Phase 2: synchronous
 * — Main waits for the result). The sub runs inside this tool call: a
 * short receipt comes back as the tool's result (agent, task title, turn
 * number, status), and the full report is stored in the sub_runs table —
 * Main reads it back with get_subagent_result when it needs the details
 * (context manager, 17/11/2026). A brief report is shown to the user as
 * the sub-agent's own message (the sub sends it with its send_message
 * tool). Dismiss is implicit: when the tool returns, the run is over and
 * the sub leaves the member list (it stays in the team).
 *
 * Phase 3: the agent list is built from the loaded team, never hardcoded.
 * The tool's description stays generic on purpose — tools.md checks it
 * (origin_description), so naming agents there would break the file on
 * every team edit. The agent list lives in the `agent` parameter
 * (description + enum), which is code-owned schema: [delegateSchema]
 * rebuilds it from the team on every send (via [Tool.schemaProvider]),
 * so team edits take effect without an app restart.
 *
 * Risk is READ: the sub's own tools are read-only (web search, web fetch),
 * so delegating never writes a file or changes anything outside the chat.
 */
object SubAgentTools {
    fun build(
        subRunner: () -> SubAgentRunner?,
        teamProvider: () -> List<SubAgentConfig> = { emptyList() },
        /** Diagnostic log (16/11/2026): the handoff steps, for the Diagnostics screen. Null = no logging. */
        diagnosticsLog: DiagnosticsLog? = null,
    ): List<Tool> = listOf(
        Tool(
            uid = "sub.delegate",
            group = ToolGroup.AGENTS,
            risk = Risk.READ,
            defaultName = "delegate_to_subagent",
            defaultDescription = "Delegate a task to a sub-agent (a specialist team member). The sub-agent runs synchronously — you wait for it to finish. It returns a short receipt (agent, task title, turn number, status) as this tool's result; the full result is stored and can be read back with get_subagent_result. Give a one-line title for the task plus a clear brief with exactly what to find and what to include. The sub-agent greets the user itself when it starts. Do not delegate tasks the agent's tools cannot do.",
            schema = delegateSchema(teamProvider()),
            schemaProvider = { delegateSchema(teamProvider()) },
        ) { _, args ->
            val runner = subRunner() ?: throw ToolException("Sub-agents are not available right now.")
            val team = teamProvider()
            val agentId = args.reqStr("agent")
            val agent = team.firstOrNull { it.id == agentId }
                ?: throw ToolException(
                    "Unknown sub-agent '$agentId'. Available: " +
                        team.filter { it.enabled }.joinToString(", ") { it.id }.ifBlank { "(none — enable an agent in the agent settings)" } +
                        ".",
                )
            if (!agent.enabled) {
                throw ToolException("Sub-agent '${agent.name}' is turned off. Turn it on in the agent settings to call it.")
            }
            val title = args.reqStr("title")
            val brief = args.reqStr("brief")
            val conversationId = currentConversationId() ?: throw ToolException("No conversation is open.")
            val turnId = currentTurnId() ?: throw ToolException("No turn is running.")
            val handoffStartedAt = System.currentTimeMillis()
            val result = runner.run(agent, title, brief, conversationId, turnId)
            // Handoff step (16/11/2026): the run returned — the next step
            // is the engine recording this result in the chat history.
            // Logged before the FAILED check, so a failed run is traced too.
            diagnosticsLog?.handoff(
                "delegate.handler.return", conversationId, turnId,
                "agent=${agent.id} status=${result.status} took=${System.currentTimeMillis() - handoffStartedAt}ms",
            )
            if (result.status == SubRunStatus.FAILED) throw ToolException(result.detailed)
            result.receipt
        },
        Tool(
            uid = "sub.recall",
            group = ToolGroup.AGENTS,
            risk = Risk.READ,
            defaultName = "get_subagent_result",
            defaultDescription = "Read back the full stored result of a past sub-agent run, by agent and turn number. Use it when you need the details of work you delegated earlier — the delegate tool only returns a short receipt.",
            schema = recallSchema(teamProvider()),
            schemaProvider = { recallSchema(teamProvider()) },
        ) { env, args ->
            val conversationId = currentConversationId() ?: throw ToolException("No conversation is open.")
            val agentId = args.reqStr("agent")
            val turn = args.int("turn") ?: throw ToolException("Missing parameter 'turn'.")
            recallSubRun(
                lookup = { cid, agent, turnNo -> env.db.subRuns().get(cid, agent, turnNo) },
                conversationId = conversationId,
                agentId = agentId,
                turnNo = turn,
            )
        },
    )

    /**
     * Read one stored sub run back (context manager, 17/11/2026): the
     * recall tool's work, split out so it can be unit-tested with a fake
     * lookup — the Tool lambda only wires the real database. The agent is
     * NOT checked against the team: a run stays readable even after its
     * agent was turned off or removed. Throws a clear ToolException when
     * nothing is stored for that agent and turn — never invents a result.
     */
    suspend fun recallSubRun(
        lookup: SubRunLookup,
        conversationId: String,
        agentId: String,
        turnNo: Int,
    ): String {
        val record = lookup(conversationId, agentId, turnNo)
            ?: throw ToolException("No stored result for sub-agent '$agentId' turn $turnNo in this conversation.")
        return formatSubRunRecord(record)
    }

    /**
     * The recall result text for one stored run: the title header, the tool
     * calls with their arguments, the readable reasoning where the provider
     * returned any, then the full report verbatim (status, task, rounds,
     * messages to Main, final answer). Pure, so it can be unit-tested
     * without Android.
     */
    fun formatSubRunRecord(record: SubRunEntity): String = buildString {
        appendLine("Sub-agent ${record.agentName}, turn ${record.turnNo}: \"${record.title}\"")
        val calls = decodeSubRunToolCalls(record.toolCallsJson)
        if (calls.isNotEmpty()) {
            appendLine("Tools called:")
            calls.forEach { appendLine("- ${it.name}: ${it.args.ifBlank { "(no arguments)" }}") }
        }
        if (record.reasoning.isNotBlank()) {
            appendLine("Reasoning:")
            appendLine(record.reasoning)
        }
        appendLine("Full report:")
        append(record.fullReport)
    }.trim()

    /**
     * Schema for the recall tool, built from the loaded team like the
     * delegate's: the `agent` enum is the enabled agents' ids, as a hint
     * for Main. The handler does NOT reject other ids — a past run stays
     * readable after its agent was turned off or removed. Pure, so it can
     * be unit-tested without Android.
     */
    fun recallSchema(agents: List<SubAgentConfig>): JsonObject {
        val enabled = agents.filter { it.enabled }
        val agentDesc = if (enabled.isEmpty()) {
            "Which sub-agent ran the task. No sub-agents are enabled right now."
        } else {
            "Which sub-agent ran the task. Available: " + enabled.joinToString("; ") { "${it.id} — ${it.description}" }
        }
        return schema {
            str("agent", agentDesc, required = true, enum = enabled.map { it.id }.takeIf { it.isNotEmpty() })
            int("turn", "The turn number from the delegate receipt, e.g. 3.", required = true)
        }
    }

    /**
     * Schema for the delegate tool, built from the loaded team: the `agent`
     * enum is the enabled agents' ids, and its description lists them
     * ("id — description") so Main can pick. No enabled agent → no enum
     * and a description that says so (the handler still refuses the call
     * with a clear error). Pure, so it can be unit-tested without Android.
     */
    fun delegateSchema(agents: List<SubAgentConfig>): JsonObject {
        val enabled = agents.filter { it.enabled }
        val agentDesc = if (enabled.isEmpty()) {
            "Which sub-agent to call. No sub-agents are enabled right now — enable one in the agent settings."
        } else {
            "Which sub-agent to call. Available: " + enabled.joinToString("; ") { "${it.id} — ${it.description}" }
        }
        return schema {
            str("agent", agentDesc, required = true, enum = enabled.map { it.id }.takeIf { it.isNotEmpty() })
            str("title", "One-line title for the task, e.g. \"Find cheerful morning music\". The title stays in your context as the receipt; the detailed instructions go in 'brief'.", required = true)
            str("brief", "The task: exactly what the sub-agent should do and what to include in its report.", required = true)
        }
    }
}

/** Lookup for one stored sub run, by conversation + agent + turn number. */
typealias SubRunLookup = suspend (conversationId: String, agentId: String, turnNo: Int) -> SubRunEntity?
