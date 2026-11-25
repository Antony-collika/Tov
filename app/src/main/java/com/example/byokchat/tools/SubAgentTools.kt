package com.example.byokchat.tools

import com.example.byokchat.chat.ActiveSubRun
import com.example.byokchat.chat.DiagnosticsLog
import com.example.byokchat.chat.SubAgentConfig
import com.example.byokchat.chat.SubAgentRunner
import com.example.byokchat.chat.SubAgentStore
import com.example.byokchat.chat.SubStopSource
import com.example.byokchat.chat.agentIdSlug
import com.example.byokchat.chat.buildSubRunStartedReceipt
import com.example.byokchat.chat.decodeSubRunToolCalls
import com.example.byokchat.data.SubRunEntity
import kotlinx.serialization.json.JsonObject

/**
 * Tool that lets Main delegate a task to a sub-agent (Phase 4: async —
 * Main never waits for the result). The sub runs in the background: a
 * short receipt comes back as this tool's result at once (agent, task
 * title, turn number), and the full report is stored in the sub_runs
 * table when the run finishes — Main reads it back with
 * get_subagent_result when it needs the details (context manager,
 * 17/11/2026). A brief report is shown to the user as the sub-agent's
 * own message (the sub sends it with its send_message tool). Dismiss is
 * implicit: when the run ends, the sub leaves the member list (it stays
 * in the team).
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
    /**
     * Uid of the recruit tool (GĐ4 batch). 19/11/2026: tools are one
     * shared pool — Main holds it too when the user ticks it in the
     * Tools picker, like any other tool; a creator agent
     * (BotFather-style) is one way to use it, not the only way.
     */
    const val RECRUIT_TOOL_UID = "sub.recruit"
    const val DELEGATE_TOOL_UID = "sub.delegate"
    const val STOP_TOOL_UID = "sub.stop"
    const val RECALL_TOOL_UID = "sub.recall"
    /** Uid of the wake tool (peer mode, 19/11/2026). */
    const val WAKE_TOOL_UID = "sub.wake"

    fun build(
        subRunner: () -> SubAgentRunner?,
        teamProvider: () -> List<SubAgentConfig> = { emptyList() },
        /** Diagnostic log (16/11/2026): the handoff steps, for the Diagnostics screen. Null = no logging. */
        diagnosticsLog: DiagnosticsLog? = null,
        /** The agent store (recruit_teammate writes agents.json). Null = no recruiting. */
        agentStore: () -> SubAgentStore? = { null },
        /** All tools in the registry, for resolving the recruit's tool names/uids. A lambda: it is evaluated only when the tool runs, after the registry finished building. */
        allTools: () -> List<Tool> = { emptyList() },
        /** Wake Main with a message (peer mode, 19/11/2026): the engine's text-wake entry, wired by the app container. Null = Main cannot be woken (tests, or a runner without an engine). A lambda: evaluated only when the tool runs. */
        requestMainWakeText: () -> ((String, String) -> Unit)? = { null },
    ): List<Tool> = listOf(
        Tool(
            uid = DELEGATE_TOOL_UID,
            group = ToolGroup.AGENTS,
            risk = Risk.READ,
            defaultName = "delegate_to_subagent",
            defaultDescription = "Delegate a task to a sub-agent (a specialist team member). The sub-agent runs in the background — you do not wait for it. This tool returns a short receipt (agent, task title, turn number) right away; the sub-agent's result arrives later as its own message in the chat, and the full result is stored for get_subagent_result. Give a one-line title for the task plus a clear brief with exactly what to find and what to include. The sub-agent greets the user itself when it starts. An agent runs one task at a time — delegating to a busy agent is refused. Do not delegate tasks the agent's tools cannot do.",
            schema = delegateSchema(teamProvider()),
            schemaProvider = { delegateSchema(teamProvider()) },
        ) { env, args ->
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
            // The turn number for the receipt: the turn's own rows carry
            // it (the user row is written before the tool runs); the
            // maxTurnNo fallback only covers a turn with no rows yet.
            val turnNo = env.db.messages().getTurn(conversationId, turnId).firstOrNull()?.turnNo
                ?: ((env.db.messages().maxTurnNo(conversationId) ?: 0) + 1)
            val handoffStartedAt = System.currentTimeMillis()
            // Who started this run (20/11/2026): Main when there is no
            // caller scope, else the peer sub-agent delegating — the
            // conversation screens show the brief under that name.
            val callerId = currentCallerAgentId()
            val callerName = callerId?.let { id -> team.firstOrNull { it.id == id }?.name ?: id } ?: "Main"
            // Phase 4: the run starts in the background and this tool
            // returns at once — Main never waits for the result.
            runner.startRun(agent, title, brief, conversationId, turnId, callerId ?: "main", callerName)
                ?: throw ToolException("Sub-agent '${agent.name}' is already running a task. Wait for it to finish, or stop it first with stop_subagent.")
            // Handoff step (16/11/2026): the run started — the next step
            // is the engine recording this receipt in the chat history.
            diagnosticsLog?.handoff(
                "delegate.handler.start", conversationId, turnId,
                "agent=${agent.id} took=${System.currentTimeMillis() - handoffStartedAt}ms",
            )
            buildSubRunStartedReceipt(agent.name, agent.id, title, turnNo)
        },
        Tool(
            uid = STOP_TOOL_UID,
            group = ToolGroup.AGENTS,
            risk = Risk.READ,
            defaultName = "stop_subagent",
            defaultDescription = "Stop a running sub-agent. The sub-agent stops immediately; this tool returns the report of the work it completed so far (its partial results).",
            schema = stopSchema(teamProvider()),
            schemaProvider = { stopSchema(teamProvider()) },
        ) { _, args ->
            val runner = subRunner() ?: throw ToolException("Sub-agents are not available right now.")
            val agentId = args.reqStr("agent")
            val agentName = teamProvider().firstOrNull { it.id == agentId }?.name ?: agentId
            val conversationId = currentConversationId() ?: throw ToolException("No conversation is open.")
            // One meaning only: stop immediately. To let a sub-agent
            // finish, simply do not call this tool.
            val result = runner.stopAgent(conversationId, agentId, SubStopSource.MAIN)
                ?: throw ToolException("Sub-agent '$agentName' is not running right now.")
            result.detailed
        },
        Tool(
            uid = RECALL_TOOL_UID,
            group = ToolGroup.AGENTS,
            risk = Risk.READ,
            defaultName = "get_subagent_result",
            defaultDescription = "Read back the full stored result of a finished sub-agent run, by agent and turn number. Use it when you need the details of work you delegated earlier — the delegate tool only returns a short receipt. Only use it for a run that has already finished: if the run is still working, this tool tells you to finish your turn and wait to be woken with the result instead of polling it.",
            schema = recallSchema(teamProvider()),
            schemaProvider = { recallSchema(teamProvider()) },
        ) { env, args ->
            val conversationId = currentConversationId() ?: throw ToolException("No conversation is open.")
            val agentId = args.reqStr("agent")
            val turn = args.int("turn") ?: throw ToolException("Missing parameter 'turn'.")
            // The caller's current turn number, for the same-turn check in
            // recallSubRun: a recall of a run delegated in this very turn
            // while it is still working is polling, not reading a past run.
            val callerTurnNo = currentTurnId()?.let { turnId ->
                env.db.messages().getTurn(conversationId, turnId).firstOrNull()?.turnNo
            }
            recallSubRun(
                lookup = { cid, agent, turnNo -> env.db.subRuns().get(cid, agent, turnNo) },
                conversationId = conversationId,
                agentId = agentId,
                turnNo = turn,
                activeRun = subRunner()?.activeRun(conversationId, agentId),
                callerTurnNo = callerTurnNo,
            )
        },
        Tool(
            uid = RECRUIT_TOOL_UID,
            group = ToolGroup.AGENTS,
            risk = Risk.WRITE,
            defaultName = "recruit_teammate",
            defaultDescription = "Create a new sub-agent in the team. Takes the agent's name, role, description, system prompt, workspace folder and tools; returns the new agent's id and name. The new agent can be edited in the agent settings.",
            schema = recruitSchema(),
        ) { _, args ->
            val store = agentStore() ?: throw ToolException("The agent store is not available.")
            val config = buildRecruitConfig(
                name = args.reqStr("name"),
                role = args.reqStr("role"),
                description = args.reqStr("description"),
                systemPrompt = args.reqStr("system_prompt"),
                workspaceFolder = args.reqStr("workspace_folder"),
                tools = args.strList("tools") ?: throw ToolException("Missing parameter 'tools'."),
                availableTools = allTools().map { it.uid to it.defaultName },
                existingIds = store.team.value.agents.map { it.id }.toSet(),
            )
            store.addAgent(config).onFailure { throw ToolException(it.message ?: "Could not save the new agent.") }
            "Created sub-agent '${config.name}' (id: ${config.id}) with tools: ${config.toolUids.joinToString(", ")}. It can be edited in the agent settings."
        },
        Tool(
            uid = WAKE_TOOL_UID,
            group = ToolGroup.AGENTS,
            risk = Risk.READ,
            defaultName = "wake_agent",
            defaultDescription = "Wake another agent with a message: \"main\" wakes Main, a sub-agent id wakes that sub-agent. The woken agent starts a new turn with your message.",
            schema = wakeSchema(teamProvider()),
            schemaProvider = { wakeSchema(teamProvider()) },
        ) { env, args ->
            val runner = subRunner() ?: throw ToolException("Sub-agents are not available right now.")
            val team = teamProvider()
            // The caller: a sub-agent's id from the runner (CallerScope);
            // Main never holds this tool — it talks to agents with
            // delegate_to_subagent and get_subagent_result instead.
            val callerId = currentCallerAgentId()
                ?: throw ToolException("wake_agent is for sub-agents — Main talks to agents with delegate_to_subagent and get_subagent_result.")
            val callerName = team.firstOrNull { it.id == callerId }?.name ?: callerId
            val targetId = args.reqStr("agent")
            val message = args.reqStr("message")
            val conversationId = currentConversationId() ?: throw ToolException("No conversation is open.")
            if (targetId == "main") {
                // Hand the result to Main: the engine wakes Main with the
                // message, and the runner is told so the automatic wake
                // later does not repeat this run's result.
                val wakeMain = requestMainWakeText()
                    ?: throw ToolException("Main cannot be woken right now.")
                runner.noteManualWakeToMain(conversationId, callerId)
                wakeMain(conversationId, wakeTextForMain(callerName, message))
                "Woke Main up."
            } else {
                val agent = team.firstOrNull { it.id == targetId }
                    ?: throw ToolException(
                        "Unknown sub-agent '$targetId'. Available: " +
                            team.filter { it.enabled }.joinToString(", ") { it.id }.ifBlank { "(none — enable an agent in the agent settings)" } +
                            ".",
                    )
                if (!agent.enabled) {
                    throw ToolException("Sub-agent '${agent.name}' is turned off. Turn it on in the agent settings to call it.")
                }
                val turnId = currentTurnId() ?: throw ToolException("No turn is running.")
                // A woken run continues the target's task, so its title is
                // the target's latest task title in this conversation.
                val title = wakeRunTitle(env.db.subRuns().latestTitle(conversationId, targetId), callerName)
                val brief = wakeBriefForSub(callerName, message)
                runner.startRun(agent, title, brief, conversationId, turnId, callerId, callerName)
                    ?: throw ToolException("Sub-agent '${agent.name}' is already running a task. Wait for it to finish, or stop it first with stop_subagent.")
                "Woke ${agent.name} up."
            }
        },
    )

    /**
     * Text of a manual wake to Main (peer mode, 19/11/2026): who woke Main
     * and what they said. Pure, so it can be unit-tested without Android.
     */
    fun wakeTextForMain(wakerName: String, message: String): String =
        "$wakerName woke you up:\n$message"

    /**
     * Brief of a woken sub run (peer mode, 19/11/2026): the waker's
     * message, labelled with who sent it — the run's first message to
     * the woken agent. Pure.
     */
    fun wakeBriefForSub(wakerName: String, message: String): String =
        "Message from $wakerName:\n$message"

    /**
     * Title of a woken sub run (peer mode, 19/11/2026): the target's
     * latest task title in this conversation — a wake continues that
     * task, it does not start a new one. Falls back to "Tin từ <waker>"
     * when the target never ran here. Pure.
     */
    fun wakeRunTitle(latestTitle: String?, wakerName: String): String =
        latestTitle?.takeIf { it.isNotBlank() } ?: "Tin từ $wakerName"

    /**
     * Schema for the wake tool, built from the loaded team like the
     * delegate's: the `agent` enum is "main" plus the enabled agents'
     * ids. Pure, so it can be unit-tested without Android.
     */
    fun wakeSchema(agents: List<SubAgentConfig>): JsonObject {
        val enabled = agents.filter { it.enabled }
        val agentDesc = if (enabled.isEmpty()) {
            "Who to wake: \"main\" (Main) or a sub-agent id. No sub-agents are enabled right now."
        } else {
            "Who to wake: \"main\" (Main) or a sub-agent id. Available sub-agents: " + enabled.joinToString("; ") { "${it.id} — ${it.description}" }
        }
        return schema {
            str("agent", agentDesc, required = true, enum = listOf("main") + enabled.map { it.id })
            str("message", "What to tell them: the result to hand over, or what you need.", required = true)
        }
    }

    /**
     * Read one stored sub run back (context manager, 17/11/2026): the
     * recall tool's work, split out so it can be unit-tested with a fake
     * lookup — the Tool lambda only wires the real database. The agent is
     * NOT checked against the team: a run stays readable even after its
     * agent was turned off or removed. Throws a clear ToolException when
     * nothing is stored for that agent and turn — never invents a result.
     *
     * Recall refusal (24/11/2026): the stored record is checked FIRST,
     * so a finished run always reads back, even in the turn it was
     * delegated in. Only when there is no stored record yet AND the
     * target run is still working in this very turn — the same agent,
     * the same turn number, and the caller is in that turn too — does
     * the tool return a refusal instead of "No stored result": the
     * caller delegated this run in this turn and is polling it before
     * it finished. The refusal is a normal result, not an error, so
     * the caller reads it as an instruction to finish its turn and
     * wait to be woken. The check is per run (agent + turn number),
     * not per agent: recalling a finished turn 1 while turn 2 is
     * still running still reads turn 1 back.
     */
    suspend fun recallSubRun(
        lookup: SubRunLookup,
        conversationId: String,
        agentId: String,
        turnNo: Int,
        activeRun: ActiveSubRun? = null,
        callerTurnNo: Int? = null,
    ): String {
        val record = lookup(conversationId, agentId, turnNo)
        if (record != null) return formatSubRunRecord(record)
        if (activeRun != null && activeRun.turnNo == turnNo &&
            (callerTurnNo == null || callerTurnNo == turnNo)
        ) {
            return buildRecallRunningRefusal(activeRun)
        }
        throw ToolException("No stored result for sub-agent '$agentId' turn $turnNo in this conversation.")
    }

    /**
     * The refusal for recalling a run that is still working in this
     * turn (24/11/2026): names the agent, the turn and the task title,
     * tells the caller to finish its turn and wait to be woken, and
     * tells it not to start the worker's job itself — the duplicate
     * work Bob did while Danny was still running (20/11/2026). Pure,
     * so it can be unit-tested without Android.
     */
    fun buildRecallRunningRefusal(activeRun: ActiveSubRun): String {
        val title = activeRun.title.takeIf { it.isNotBlank() }?.let { " — \"$it\"" }.orEmpty()
        return "${activeRun.name} is still working on turn ${activeRun.turnNo}$title. " +
            "Finish your turn now — you will be woken with the result when ${activeRun.name} finishes. " +
            "Do not call this tool while the sub-agent is working. " +
            "Do not start ${activeRun.name}'s work yourself."
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
     * Schema for the stop tool, built from the loaded team like the
     * delegate's: the `agent` enum is the enabled agents' ids, as a hint
     * for Main. The handler does NOT reject other ids — a run stays
     * stoppable after its agent was turned off or removed. Pure, so it
     * can be unit-tested without Android.
     */
    fun stopSchema(agents: List<SubAgentConfig>): JsonObject {
        val enabled = agents.filter { it.enabled }
        val agentDesc = if (enabled.isEmpty()) {
            "Which running sub-agent to stop. No sub-agents are enabled right now."
        } else {
            "Which running sub-agent to stop. Available: " + enabled.joinToString("; ") { "${it.id} — ${it.description}" }
        }
        return schema {
            str("agent", agentDesc, required = true, enum = enabled.map { it.id }.takeIf { it.isNotEmpty() })
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

    /**
     * Schema for the recruit tool: the five manual fields plus the tools.
     * The ceilings, model, history and on/off keep their defaults (10
     * rounds, 32000 tokens, follow Main's model, brief only, on) — the
     * user edits them in the agent settings after. Pure.
     */
    fun recruitSchema(): JsonObject = schema {
        str("name", "The new agent's display name, e.g. \"Music\".", required = true)
        str("role", "Short role label, shown on the Members screen, e.g. \"Music agent\".", required = true)
        str("description", "Short description — Main reads this when picking an agent, so say what it does and when to call it.", required = true)
        str("system_prompt", "The system prompt the new agent reads — its job, its rules and how it reports back.", required = true)
        str("workspace_folder", "The agent's workspace folder inside the AI playground, e.g. \"Music\".", required = true)
        strArray("tools", "Tools the new agent may use: tool names (e.g. \"web_search\") or uids (e.g. \"web.search\"). At least one.", required = true)
    }

    /**
     * Build the config for a recruited agent from the tool's parameters
     * (recruit, GĐ4 batch). Pure — the Tool lambda only wires the store
     * and the registry's tool list — so it can be unit-tested without
     * Android. Throws a clear ToolException on bad input; never invents a
     * value. The ceilings, model, history and on/off keep their defaults.
     */
    fun buildRecruitConfig(
        name: String,
        role: String,
        description: String,
        systemPrompt: String,
        workspaceFolder: String,
        tools: List<String>,
        /** (uid, model-facing name) of every tool the recruiter may assign. */
        availableTools: List<Pair<String, String>>,
        /** Ids already in the team — a recruited id must not collide with one. */
        existingIds: Set<String>,
    ): SubAgentConfig {
        if (name.isBlank()) throw ToolException("recruit_teammate: 'name' is empty.")
        if (role.isBlank()) throw ToolException("recruit_teammate: 'role' is empty.")
        if (description.isBlank()) throw ToolException("recruit_teammate: 'description' is empty.")
        if (systemPrompt.isBlank()) throw ToolException("recruit_teammate: 'system_prompt' is empty.")
        if (workspaceFolder.isBlank()) throw ToolException("recruit_teammate: 'workspace_folder' is empty.")
        if (tools.isEmpty()) throw ToolException("recruit_teammate: 'tools' is empty — pick at least one tool for the new agent.")
        val resolved = tools.map { entry ->
            availableTools.firstOrNull { (uid, toolName) -> uid == entry || toolName == entry }?.first
                ?: throw ToolException(
                    "recruit_teammate: unknown tool '$entry'. Available: " +
                        availableTools.joinToString(", ") { it.second }.ifBlank { "(none)" } + ".",
                )
        }
        val id = agentIdSlug(name)
        if (id in existingIds) throw ToolException("recruit_teammate: an agent with id '$id' already exists.")
        val config = SubAgentConfig(
            id = id,
            name = name.trim(),
            role = role.trim(),
            description = description.trim(),
            systemPrompt = systemPrompt.trim(),
            toolUids = resolved.distinct(),
            workspaceFolder = workspaceFolder.trim(),
        )
        val errors = config.validate()
        if (errors.isNotEmpty()) throw ToolException("recruit_teammate: ${errors.joinToString(" ")}")
        return config
    }
}

/** Lookup for one stored sub run, by conversation + agent + turn number. */
typealias SubRunLookup = suspend (conversationId: String, agentId: String, turnNo: Int) -> SubRunEntity?
