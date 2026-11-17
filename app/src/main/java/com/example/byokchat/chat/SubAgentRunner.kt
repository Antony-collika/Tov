package com.example.byokchat.chat

import com.example.byokchat.ai.AiEvent
import com.example.byokchat.ai.AiMessage
import com.example.byokchat.ai.AiRequest
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.ProviderFactory
import com.example.byokchat.ai.RequestLog
import com.example.byokchat.ai.Role
import com.example.byokchat.ai.ToolDef
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.SubRunEntity
import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import com.example.byokchat.data.SettingsRepository
import com.example.byokchat.tools.ParsedTool
import com.example.byokchat.tools.WorkspaceFs
import com.example.byokchat.tools.schema
import com.example.byokchat.tools.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A sub-agent run currently in progress in a conversation (members list + typing indicator read this). Live progress (rounds, tokens) is updated every round, so the Members screen can show whether a run is moving or stuck. */
data class ActiveSubRun(
    val agentId: String,
    val name: String,
    val brief: String,
    val startedAt: Long,
    val rounds: Int = 0,
    val roundCap: Int = SubAgentConfig.DEFAULT_ROUND_CAP,
    val tokensUsed: Int = 0,
    val tokenBudget: Int = SubAgentConfig.DEFAULT_TOKEN_BUDGET,
)

/**
 * Runs one sub-agent synchronously inside Main's turn (Phase 2): Main waits
 * for the result. The sub gets its own system prompt, its own tools and its
 * own folder; by default it never sees the rest of the conversation, only
 * the brief (an agent may opt into seeing Main's recent history).
 *
 * Flow: loop tool rounds (own round cap + token budget) → on finish,
 * return a short receipt to Main (the delegate tool's result) and store
 * the full report in the sub_runs table, so Main can read it back later
 * with get_subagent_result instead of carrying it in its context
 * (context manager, 17/11/2026). The sub greets the user itself, with
 * its send_message tool at the start of its first round (taught in its
 * system prompt) — Main never scripts the greeting. Every run is also
 * recorded in sub_runs.meta.json in the session folder. Dismiss is
 * implicit here: when the run ends, the sub leaves the member list (it
 * stays in the team and can be called again).
 *
 * Talking to the user (Phase 3): the sub's plain text goes only to Main.
 * A message reaches the group chat ONLY when the sub calls its
 * send_message tool (receiver "user" → written to the chat at once;
 * receiver "main" → collected and included in the detailed report). There
 * is no marker and no JSON in the sub's answer.
 *
 * Stop: the run shares Main's turn, so stopping Main stops the sub too
 * (the cancellation propagates through the provider stream); the run is
 * still recorded as interrupted. Stopping ONE sub from the Members screen
 * ([stopSub]) is different: it cancels only that sub's job, the run ends
 * as INTERRUPTED, and the result returns to Main through the delegate
 * tool — Main's turn continues.
 */
class SubAgentRunner(
    private val settings: SettingsRepository,
    private val db: AppDatabase,
    private val tools: ToolRuntime,
    private val fs: WorkspaceFs,
    private val requestLog: RequestLog? = null,
    /** Diagnostic log (16/11/2026): the handoff steps, for the Diagnostics screen. Null = no logging. */
    private val diagnosticsLog: DiagnosticsLog? = null,
) {
    companion object {
        /** Uid + model-facing name of the sub's send tool (Phase 3). It is
         *  NOT in the registry: the runner offers it to the sub and answers
         *  it itself, so Main never sees it. */
        const val SEND_TOOL_UID = "sub.send"
        const val SEND_TOOL_NAME = "send_message"

        /** How much of Main's history a SEES_HISTORY sub may see. */
        const val HISTORY_MESSAGES = 20
        const val HISTORY_MESSAGE_CHARS = 1000
    }

    private val _activeSubs = MutableStateFlow<Map<String, ActiveSubRun>>(emptyMap())
    val activeSubs: StateFlow<Map<String, ActiveSubRun>> = _activeSubs.asStateFlow()

    /** Tokens used by finished sub runs, per conversation — the session token bar on the Members screen. */
    private val _sessionSubTokens = MutableStateFlow<Map<String, Int>>(emptyMap())
    val sessionSubTokens: StateFlow<Map<String, Int>> = _sessionSubTokens.asStateFlow()

    /** One job per running sub (Phase 3): a child of Main's turn job, so
     *  stopping Main still stops the sub, but [stopSub] can cancel the
     *  sub alone. */
    private val subJobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    /** Conversations whose sub was stopped from the Members screen (set by [stopSub], read by the run's catch). */
    private val stopRequested = ConcurrentHashMap.newKeySet<String>()

    /** Stop only this conversation's sub run. Main's turn keeps going: the
     *  run ends as INTERRUPTED and its result returns through the delegate
     *  tool. No-op when no sub is running here. */
    fun stopSub(conversationId: String) {
        stopRequested.add(conversationId)
        subJobs.value[conversationId]?.cancel()
    }

    /** Serializes sub_runs.meta.json writes (one file per session; runs are sequential per conversation, but two conversations could write at once). */
    private val metaMutex = Mutex()

    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    suspend fun run(
        agent: SubAgentConfig,
        title: String,
        brief: String,
        conversationId: String,
        turnId: String,
    ): SubRunResult {
        val startedAt = System.currentTimeMillis()
        val msgDao = db.messages()
        // The sub's messages belong to Main's turn (same turnId), so deleting
        // or regenerating the turn takes them along.
        val turnNo = msgDao.getTurn(conversationId, turnId).firstOrNull { it.role == "user" }?.turnNo ?: 0
        var rounds = 0
        var tokensUsed = 0
        val toolCalls = mutableListOf<String>()
        // Messages the sub sent with its send_message tool: to Main (kept
        // for the detailed report) and to the user (already in the chat).
        val mainOutbox = mutableListOf<String>()
        val sentToUser = mutableListOf<String>()
        // Readable reasoning across all rounds, including the final round
        // that answers without a tool call (its reasoning used to be
        // dropped: the loop only kept reasoning on rounds with tool
        // calls, on the round's tool-call row).
        val allReasoning = StringBuilder()
        // Work tool calls with their arguments, for the stored run record
        // (send_message is not work — its messages already are).
        val workCallRecords = mutableListOf<SubRunToolCall>()
        var status = SubRunStatus.DONE
        var error: String? = null
        var finalText = ""
        var cancelled = false
        _activeSubs.update {
            it + (conversationId to ActiveSubRun(agent.id, agent.name, brief, startedAt, roundCap = agent.roundCap, tokenBudget = agent.tokenBudget))
        }
        // The sub's own job: a child of Main's turn job (stopping Main
        // still stops the sub), cancelled alone by stopSub.
        val subJob = Job(parent = currentCoroutineContext()[Job])
        subJobs.update { it + (conversationId to subJob) }
        // A stop pressed while no sub was running leaves a stale flag; a
        // later Main stop must not be mistaken for a per-sub stop.
        stopRequested.remove(conversationId)
        try {
            // The sub greets the user itself, with its send_message tool
            // at the start of its first round (taught in its system
            // prompt) — Main never scripts the greeting.
            // The agent's own model when it has one, else Main's current
            // model (same provider). A removed/renamed provider is a clear
            // failure, not a silent fallback to Main's model.
            val cfg: ProviderConfig?
            val model: String
            if (agent.hasOwnModel) {
                cfg = settings.provider(agent.modelProviderId)
                model = agent.model.orEmpty()
                if (cfg == null) {
                    status = SubRunStatus.FAILED
                    error = "The agent's own model is not available: provider '${agent.modelProviderId}' was removed or renamed. Pick another model in the agent settings, or clear it to follow Main."
                }
            } else {
                cfg = settings.currentModelProvider()
                model = settings.currentModel.value.orEmpty()
            }
            if (status != SubRunStatus.FAILED && cfg == null) {
                status = SubRunStatus.FAILED
                error = "No AI provider is selected. Go to Settings → AI providers to add one."
            } else if (status != SubRunStatus.FAILED && model.isBlank()) {
                status = SubRunStatus.FAILED
                error = "No model is selected."
            } else if (status != SubRunStatus.FAILED && cfg != null) {
                val sendDef = sendToolDef()
                val defs = tools.definitionsForUids(agent.toolUids)
                // The send tool is added to every sub run, on top of the
                // agent's own tools — it is never part of toolUids.
                val exposed = defs.map { (uid, def) -> ParsedTool(uid, def.name, def.description) } +
                    ParsedTool(SEND_TOOL_UID, sendDef.name, sendDef.description)
                val toolDefs = defs.map { it.second } + sendDef
                val system = agent.systemPrompt
                val subMessages = mutableListOf<AiMessage>()
                if (agent.historyVisibility == HistoryVisibility.SEES_HISTORY) {
                    subMessages += historyForSub(conversationId, turnId)
                }
                subMessages += AiMessage(Role.USER, text = brief)
                val reasoningProvider = when (cfg.type) {
                    ProviderType.OPENAI -> "openai"
                    ProviderType.CUSTOM -> "custom"
                    else -> null
                }
                // The loop runs under the sub's own job, so stopSub can
                // cancel it without touching Main's turn. finish() below
                // runs outside it, in Main's context.
                withContext(subJob) {
                    while (true) {
                    // Token budget (rough estimate, like the main engine's
                    // budget): stop before a round that would pass it.
                    val estimate = TokenEstimator.text(system) +
                        subMessages.sumOf { TokenEstimator.message(it) } +
                        toolDefs.sumOf { TokenEstimator.text(it.name + it.description + it.schema.toString()) }
                    tokensUsed = maxOf(tokensUsed, estimate)
                    updateActive(conversationId, agent, rounds, tokensUsed)
                    if (estimate > agent.tokenBudget) {
                        status = SubRunStatus.BUDGET_EXCEEDED
                        error = "Stopped: the estimated request ($estimate tokens) passed this agent's token budget (${agent.tokenBudget})."
                        break
                    }
                    if (rounds >= agent.roundCap) {
                        status = SubRunStatus.ROUND_CAP
                        error = "Stopped: the agent used all ${agent.roundCap} tool rounds."
                        break
                    }
                    val request = AiRequest(
                        model, system, subMessages.toList(), toolDefs,
                        cfg.maxOutputTokens, settings.metadata.value,
                        sessionId = conversationId, turnNo = turnNo,
                    )
                    val text = StringBuilder()
                    var calls: List<AiToolCall> = emptyList()
                    // Thought signature / reasoning of this round (same
                    // handling as the main engine): kept on the assistant
                    // message so the next round stays valid for Gemini.
                    var textSignature: String? = null
                    val reasoning = StringBuilder()
                    var replayJson: String? = null
                    ProviderFactory.create(cfg, requestLog).stream(request).collect { ev ->
                        when (ev) {
                            is AiEvent.Text -> {
                                text.append(ev.delta)
                                ev.signature?.let { textSignature = it }
                            }
                            is AiEvent.Reasoning -> reasoning.append(ev.delta)
                            is AiEvent.Replay -> replayJson = ev.json
                            is AiEvent.ToolCalls -> calls = ev.calls
                        }
                    }
                    // Keep this round's reasoning even when it ends the
                    // run: the final round answers without tool calls, so
                    // nothing below would carry it into the stored record.
                    allReasoning.append(reasoning)
                    if (calls.isEmpty()) {
                        finalText = text.toString()
                        break
                    }
                    rounds++
                    updateActive(conversationId, agent, rounds, tokensUsed)
                    subMessages += AiMessage(
                        Role.ASSISTANT, text = text.toString(), toolCalls = calls,
                        thoughtSignature = textSignature,
                        reasoningText = reasoning.toString().takeIf { it.isNotBlank() },
                        reasoningReplay = replayJson,
                        reasoningProvider = if (reasoning.isNotBlank() || replayJson != null) reasoningProvider else null,
                    )
                    // The sub's real tool calls are shown in the chat as
                    // tool cards under the sub's header, like Main's
                    // (17/11/2026): one assistant row per round holds the
                    // round's work calls, one tool row per call holds its
                    // result. send_message is not a card — its messages
                    // already are. The rows carry sender = the agent id,
                    // so ContextBuilder drops them from Main's request
                    // (the 400 fix) and sanitize drops the orphan tool
                    // results; Preview shows exactly what is sent.
                    val workCalls = calls.filter { it.name != SEND_TOOL_NAME }
                        .map { it.copy(uid = tools.uidForName(it.name, exposed)) }
                    var toolRowWritten = false
                    for (call in calls) {
                        val outcome = if (call.name == SEND_TOOL_NAME) {
                            handleSend(call, agent, conversationId, turnId, turnNo, mainOutbox, sentToUser)
                        } else {
                            workCallRecords += SubRunToolCall(call.name, call.argsJson)
                            // Written lazily at the first work call, not
                            // before the loop, so a greeting sent in the
                            // same round stays before the cards. The round's
                            // reasoning rides on the same row (17/11/2026):
                            // providers that return readable reasoning
                            // (DeepSeek via Custom) show it as a step in
                            // the sub's pill; Gemini returns none, so the
                            // pill holds tool calls only — never invented.
                            if (!toolRowWritten) {
                                runCatching {
                                    writeSubToolCalls(
                                        conversationId, turnId, turnNo, agent, workCalls,
                                        reasoningText = reasoning.toString().takeIf { it.isNotBlank() },
                                    )
                                }
                                toolRowWritten = true
                            }
                            val result = tools.execute(call, turnId, exposed, conversationId)
                            runCatching {
                                writeSubToolResult(conversationId, turnId, turnNo, call, result)
                            }
                            result
                        }
                        toolCalls += call.name
                        subMessages += AiMessage(
                            Role.TOOL, text = outcome.resultText,
                            toolCallId = call.id, toolName = call.name, isError = outcome.isError,
                        )
                    }
                    }
                }
            }
        } catch (e: CancellationException) {
            if (stopRequested.remove(conversationId)) {
                // Per-sub stop from the Members screen: the run ends as
                // interrupted and its result returns to Main through the
                // delegate tool — Main's turn continues (cancelled stays
                // false, so nothing is rethrown below).
                status = SubRunStatus.INTERRUPTED
                error = "Stopped from the Members screen."
            } else {
                // Stop pressed mid-run: the run is recorded as interrupted,
                // then the cancellation propagates so Main's turn settles
                // as interrupted. It was FAILED before, so a stop looked
                // like a failure in the run record and the brief
                // ("couldn't finish") instead of a stop (17/11/2026).
                cancelled = true
                status = SubRunStatus.INTERRUPTED
                error = "Interrupted by the user."
            }
        } catch (e: Exception) {
            // A provider/network error must not leave the sub stuck as
            // "Working" with no report: the run ends as failed, with the
            // brief telling the user what happened. But if the job is no
            // longer active, the run was cancelled and the failure is not
            // a CancellationException (e.g. a stream read that raced the
            // Stop press): that is still an interruption, not a failure,
            // so Main is told the user stopped it.
            if (!currentCoroutineContext().isActive) {
                cancelled = true
                status = SubRunStatus.INTERRUPTED
                error = "Interrupted by the user."
            } else {
                status = SubRunStatus.FAILED
                error = e.message ?: e.toString()
            }
        } finally {
            // A bare child Job blocks its parent's completion (16/11/2026):
            // the delegate tool runs under withContext (ToolRegistry), and
            // subJob is a child of that Job. Without complete() here, the
            // withContext Job can never complete after a normal run — the
            // sub finished, but its Job stays ACTIVE forever, so execute()
            // never returns, no tool result is recorded and no round-2
            // request is ever sent (the turn stalls with "Main is typing"
            // and idle dispatchers). complete() on an already-cancelled
            // Job is a no-op, so the stopSub / Main-stop paths are
            // unaffected.
            subJob.complete()
            _activeSubs.update { it - conversationId }
            subJobs.update { it - conversationId }
        }
        // End the run: brief message + stored record + meta record. On
        // cancellation this must still land, so it runs NonCancellable; a
        // write failure never hides the original outcome (finishOrFallback
        // always returns a result).
        val result = if (cancelled) {
            withContext(NonCancellable) {
                finishOrFallback(agent, title, brief, conversationId, turnId, turnNo, startedAt, status, error, finalText, rounds, tokensUsed, toolCalls, mainOutbox, sentToUser, allReasoning.toString(), workCallRecords)
            }
        } else {
            finishOrFallback(agent, title, brief, conversationId, turnId, turnNo, startedAt, status, error, finalText, rounds, tokensUsed, toolCalls, mainOutbox, sentToUser, allReasoning.toString(), workCallRecords)
        }
        _sessionSubTokens.update { it + (conversationId to (it[conversationId] ?: 0) + result.tokensUsed) }
        // Handoff step (16/11/2026): the run returned — the next step is
        // the delegate tool returning, then the engine recording the result.
        diagnosticsLog?.handoff(
            "sub.run.return", conversationId, turnId,
            "agent=${agent.id} status=${result.status} rounds=${result.rounds} took=${System.currentTimeMillis() - startedAt}ms",
        )
        if (cancelled) throw CancellationException("Interrupted by the user.")
        return result
    }

    /** Refresh the live run shown on the Members screen (rounds, tokens). No-op once the run is gone. */
    private fun updateActive(
        conversationId: String,
        agent: SubAgentConfig,
        rounds: Int,
        tokensUsed: Int,
    ) {
        _activeSubs.update { current ->
            val active = current[conversationId] ?: return@update current
            current + (conversationId to active.copy(rounds = rounds, roundCap = agent.roundCap, tokensUsed = tokensUsed, tokenBudget = agent.tokenBudget))
        }
    }

    /** The send_message tool, offered to every sub: user → chat at once, main → collected for the detailed report. */
    private fun sendToolDef(): ToolDef = ToolDef(
        SEND_TOOL_NAME,
        "Send a message to the user or to Main.",
        schema {
            str(
                "receiver",
                "Who receives the message: \"user\" (shown in the chat) or \"main\" (included in your final report to Main).",
                required = true, enum = listOf("user", "main"),
            )
            str("text", "The message to send.", required = true)
        },
    )

    /** Answer one send_message call from the sub. Plain text the sub writes without this tool never reaches the user. */
    private suspend fun handleSend(
        call: AiToolCall,
        agent: SubAgentConfig,
        conversationId: String,
        turnId: String,
        turnNo: Int,
        mainOutbox: MutableList<String>,
        sentToUser: MutableList<String>,
    ): ToolOutcome {
        val args: JsonObject = try {
            if (call.argsJson.isBlank()) JsonObject(emptyMap())
            else Json.parseToJsonElement(call.argsJson) as? JsonObject
                ?: return ToolOutcome(SEND_TOOL_UID, "send_message needs an object with 'receiver' and 'text'.", true)
        } catch (e: Exception) {
            return ToolOutcome(SEND_TOOL_UID, "send_message: could not read its arguments (${e.message}).", true)
        }
        val text = args.str("text")?.trim().orEmpty()
        if (text.isEmpty()) {
            return ToolOutcome(SEND_TOOL_UID, "send_message: 'text' is empty — nothing was sent.", true)
        }
        return when (args.str("receiver")) {
            "user" -> {
                writeSubMessage(conversationId, turnId, turnNo, agent, text)
                sentToUser += text
                ToolOutcome(SEND_TOOL_UID, "Sent to the user.", false)
            }
            "main" -> {
                mainOutbox += text
                ToolOutcome(SEND_TOOL_UID, "Sent to Main — it will be included in your final report.", false)
            }
            else -> ToolOutcome(SEND_TOOL_UID, "send_message: 'receiver' must be \"user\" or \"main\".", true)
        }
    }

    /**
     * Main's recent conversation history for a SEES_HISTORY sub: the last
     * turns before this one, text only (no tool calls), each cut short.
     * The sub's own earlier messages carry their "[Name] " prefix, like in
     * the history Main sees.
     */
    private suspend fun historyForSub(conversationId: String, turnId: String): List<AiMessage> {
        val rows = db.messages().getAll(conversationId)
        return rows
            .filter { it.turnId != turnId && it.role in listOf("user", "assistant") && it.text.isNotBlank() }
            .takeLast(HISTORY_MESSAGES)
            .map { m ->
                val text = m.text.take(HISTORY_MESSAGE_CHARS)
                when (m.role) {
                    "user" -> AiMessage(Role.USER, text = text)
                    else -> {
                        val prefix = m.sender?.let { "[${SubAgentStore.snapshot.displayName(it)}] " }.orEmpty()
                        AiMessage(Role.ASSISTANT, text = prefix + text)
                    }
                }
            }
    }

    /**
     * End a run, best-effort: if writing the brief message or the meta
     * record fails, still return a result so the caller can report it.
     */
    private suspend fun finishOrFallback(
        agent: SubAgentConfig,
        title: String,
        brief: String,
        conversationId: String,
        turnId: String,
        turnNo: Int,
        startedAt: Long,
        status: SubRunStatus,
        error: String?,
        finalText: String,
        rounds: Int,
        tokensUsed: Int,
        toolCalls: List<String>,
        mainOutbox: List<String>,
        sentToUser: List<String>,
        reasoning: String,
        workCallRecords: List<SubRunToolCall>,
    ): SubRunResult {
        return try {
            finish(agent, title, brief, conversationId, turnId, turnNo, startedAt, status, error, finalText, rounds, tokensUsed, toolCalls, mainOutbox, sentToUser, reasoning, workCallRecords)
        } catch (e: Exception) {
            SubRunResult(
                agentId = agent.id, agentName = agent.name,
                brief = fallbackBrief(finalText, sentToUser).ifBlank {
                    "${agent.name} couldn't finish: ${error ?: "unknown error"}"
                },
                detailed = "Sub-agent ${agent.name} finished: ${status.name.lowercase()}.",
                status = status, rounds = rounds, tokensUsed = tokensUsed,
                toolCalls = toolCalls.toList(), startedAt = startedAt,
                finishedAt = System.currentTimeMillis(), error = error,
                title = title, turnNo = turnNo,
                receipt = buildSubRunReceipt(agent.name, agent.id, title, turnNo, status),
            )
        }
    }

    /**
     * End a run: the brief for the user (the last message the sub sent with
     * send_message, or a fallback from its final answer when it never sent
     * one — written as the sub's own message only then, since sent ones
     * are already in the chat), the detailed report (stored in the sub_runs
     * table for Main to read back, plus the meta file record), and the
     * short receipt Main keeps in its context.
     */
    private suspend fun finish(
        agent: SubAgentConfig,
        title: String,
        brief: String,
        conversationId: String,
        turnId: String,
        turnNo: Int,
        startedAt: Long,
        status: SubRunStatus,
        error: String?,
        finalText: String,
        rounds: Int,
        tokensUsed: Int,
        toolCalls: List<String>,
        mainOutbox: List<String>,
        sentToUser: List<String>,
        reasoning: String,
        workCallRecords: List<SubRunToolCall>,
    ): SubRunResult {
        val finishedAt = System.currentTimeMillis()
        val detail = finalText.trim()
        val alreadySent = sentToUser.isNotEmpty()
        val userBrief = if (alreadySent) {
            sentToUser.last()
        } else if (finalText.isBlank()) {
            when (status) {
                SubRunStatus.DONE -> ""
                SubRunStatus.ROUND_CAP -> "${agent.name} did part of the task, then ran out of tool rounds."
                SubRunStatus.BUDGET_EXCEEDED -> "${agent.name} did part of the task, then ran out of token budget."
                SubRunStatus.FAILED -> "${agent.name} couldn't finish: ${error ?: "unknown error"}"
                SubRunStatus.INTERRUPTED -> "${agent.name} was stopped."
            }
        } else {
            fallbackBrief(finalText, sentToUser)
        }
        val detailed = buildString {
            appendLine("Sub-agent ${agent.name} finished: ${status.name.lowercase().replace('_', ' ')}.")
            appendLine("Task: $brief")
            if (toolCalls.isNotEmpty()) appendLine("Tools called: ${toolCalls.joinToString(", ")}")
            appendLine("Rounds: $rounds/${agent.roundCap}, estimated tokens: ~$tokensUsed/${agent.tokenBudget}.")
            if (error != null) appendLine("Note: $error")
            if (mainOutbox.isNotEmpty()) {
                appendLine()
                appendLine("Messages from ${agent.name} to Main:")
                mainOutbox.forEach { appendLine("- $it") }
            }
            appendLine()
            append(detail.ifBlank { "(no report text)" })
        }.trim()
        if (!alreadySent && userBrief.isNotBlank()) {
            writeSubMessage(conversationId, turnId, turnNo, agent, userBrief)
        }
        // The stored run record and the journal file are written together,
        // so they cannot drift apart: the table is what reads use (the
        // recall tool, the run detail screen), the file stays the
        // user-readable journal. A failed table write must not hide the
        // receipt — the file record still lands, and the receipt still
        // returns (the run just cannot be read back from the table).
        runCatching {
            db.subRuns().insert(
                SubRunEntity(
                    conversationId = conversationId,
                    turnNo = turnNo,
                    agent = agent.id,
                    agentName = agent.name,
                    title = title,
                    brief = brief,
                    status = status.name.lowercase(),
                    rounds = rounds,
                    tokens = tokensUsed,
                    toolCallsJson = encodeSubRunToolCalls(workCallRecords),
                    messagesToMainJson = encodeSubRunStrings(mainOutbox),
                    fullReport = detailed,
                    reasoning = reasoning,
                    error = error,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                ),
            )
        }
        writeMetaRecord(
            conversationId, agent, brief, status, rounds, tokensUsed, toolCalls,
            startedAt, finishedAt, detail.take(500), error, sentToUser.size, mainOutbox,
        )
        return SubRunResult(
            agentId = agent.id, agentName = agent.name, brief = userBrief, detailed = detailed,
            status = status, rounds = rounds, tokensUsed = tokensUsed,
            toolCalls = toolCalls.toList(), startedAt = startedAt, finishedAt = finishedAt, error = error,
            title = title, turnNo = turnNo,
            receipt = buildSubRunReceipt(agent.name, agent.id, title, turnNo, status),
        )
    }

    /**
     * One assistant row per round holding the round's real tool calls, so
     * the chat shows them in the sub's work pill under the sub's header
     * (the same pill Main's rounds use). Blank text: the row is only the
     * pill's steps. Written before the first call runs, so while the turn
     * generates the pill reads "Running…"; each result row below flips
     * its step to Done. Status is "done" at once, so a Stop never deletes
     * the row as a streaming placeholder — the engine's
     * markTurnInterrupted instead writes Interrupted markers for calls
     * with no result yet.
     */
    private suspend fun writeSubToolCalls(
        conversationId: String,
        turnId: String,
        turnNo: Int,
        agent: SubAgentConfig,
        workCalls: List<AiToolCall>,
        reasoningText: String? = null,
    ) {
        val msgDao = db.messages()
        msgDao.upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                turnId = turnId,
                turnNo = turnNo,
                seq = (msgDao.maxSeq(conversationId) ?: 0) + 1,
                role = "assistant",
                text = "",
                sender = agent.id,
                toolCallsJson = json.encodeToString(callsSer, workCalls),
                reasoningText = reasoningText,
                status = "done",
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** One tool-result row per real tool call, keyed by the call id so the card above finds its result. Same shape as Main's tool rows. */
    private suspend fun writeSubToolResult(
        conversationId: String,
        turnId: String,
        turnNo: Int,
        call: AiToolCall,
        outcome: ToolOutcome,
    ) {
        val msgDao = db.messages()
        msgDao.upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                turnId = turnId,
                turnNo = turnNo,
                seq = (msgDao.maxSeq(conversationId) ?: 0) + 1,
                role = "tool",
                text = outcome.resultText,
                toolCallId = call.id,
                toolUid = outcome.uid,
                toolName = call.name,
                toolIsError = outcome.isError,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** One of the sub's own messages (a send_message to the user, or the brief report): an assistant message tagged with the agent id. */
    private suspend fun writeSubMessage(
        conversationId: String,
        turnId: String,
        turnNo: Int,
        agent: SubAgentConfig,
        text: String,
    ) {
        val msgDao = db.messages()
        msgDao.upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                turnId = turnId,
                turnNo = turnNo,
                seq = (msgDao.maxSeq(conversationId) ?: 0) + 1,
                role = "assistant",
                text = text,
                sender = agent.id,
                status = "done",
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Append one run to sub_runs.meta.json in the session folder (JSONL: header line, then one line per run). Best-effort: no session storage, lost access or a write error never breaks the chat. */
    private suspend fun writeMetaRecord(
        conversationId: String,
        agent: SubAgentConfig,
        brief: String,
        status: SubRunStatus,
        rounds: Int,
        tokens: Int,
        toolCalls: List<String>,
        startedAt: Long,
        finishedAt: Long,
        resultSummary: String,
        error: String?,
        sentToUser: Int = 0,
        messagesToMain: List<String> = emptyList(),
    ) {
        runCatching {
            val conv = db.conversations().get(conversationId) ?: return
            val path = HistoryExporter.subRunsPath(conv)
            val record = SubRunRecord(
                agent = agent.id, agentName = agent.name, brief = brief,
                status = status.name.lowercase(), rounds = rounds, tokens = tokens,
                toolCalls = toolCalls, startedAt = startedAt, finishedAt = finishedAt,
                resultSummary = resultSummary, error = error,
                sentToUser = sentToUser, messagesToMain = messagesToMain,
            )
            val line = encodeSubRunRecord(record)
            metaMutex.withLock {
                if (fs.sessionExists(path)) fs.appendSessionText(path, "$line\n")
                else fs.writeSessionText(path, encodeSubRunsHeader(SubRunsHeader(session = conv.id.take(8))) + "\n$line\n")
            }
        }
    }
}
