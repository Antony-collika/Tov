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
import com.example.byokchat.tools.CallerScope
import com.example.byokchat.tools.ParsedTool
import com.example.byokchat.tools.RunScope
import com.example.byokchat.tools.WorkspaceFs
import com.example.byokchat.tools.schema
import com.example.byokchat.tools.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Identifies one sub-agent run: an agent runs at most one task at a time in a conversation. */
data class SubRunKey(val conversationId: String, val agentId: String)

/** Who stopped a sub run: the user (Members screen) or Main (its stop tool). */
enum class SubStopSource { USER, MAIN }

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
    /** Which turn this run was delegated in, what it was called, and who delegated it — the recall tool needs all three to tell a still-running run apart from a finished one and from a run that was never delegated (recall refusal, 24/11/2026). */
    val turnNo: Int = 0,
    val title: String = "",
    val startedBy: String = "main",
    val startedByName: String = "Main",
)

/**
 * Runs a sub-agent in the background (Phase 4, 18/11/2026): Main delegates
 * and does NOT wait — the run lives in the runner's own scope, independent
 * of Main's turn, so stopping Main never stops a sub and a stuck sub never
 * freezes Main. The sub gets its own system prompt, its own tools and its
 * own folder; by default it never sees the rest of the conversation, only
 * the brief (an agent may opt into seeing Main's recent history).
 *
 * Flow: [startRun] launches the run and returns at once; the run loops
 * tool rounds (own round cap + token budget) → on finish, its brief is
 * delivered as a NEW message at the bottom of the chat (never inserted
 * into its old turn), carrying the task title as its anchor, and the full
 * report is stored in the sub_runs table, so Main can read it back later
 * with get_completed_subagent_run instead of carrying it in its context
 * (context manager, 17/11/2026). The sub greets the user itself, with
 * its send_message tool at the start of its first round (taught in its
 * system prompt) — Main never scripts the greeting. Every run is also
 * recorded in sub_runs.meta.json in the session folder. Dismiss is
 * implicit here: when the run ends, the sub leaves the member list (it
 * stays in the team and can be called again).
 *
 * One speaker streams at a time: a finished sub waits until no assistant
 * row is streaming, then its brief is written — while it waits, its pill
 * reads "đang chờ [tên agent] nói xong" ([waitingDeliveries]). Greetings
 * keep delegation order (the first run to be delegated greets first);
 * results go in completion order (whoever finishes first speaks first).
 *
 * Talking to the user: the sub's plain text goes only to Main. A message
 * reaches the group chat ONLY when the sub calls its send_message tool
 * (receiver "user" → delivered to the chat; receiver "main" → collected
 * and included in the detailed report). There is no marker and no JSON in
 * the sub's answer.
 *
 * Stop: Main's Stop button stops Main only — it never reaches a sub.
 * A sub stops three ways: Main calls its stop tool ([stopAgent] with
 * [SubStopSource.MAIN]), the user taps per-sub Stop on the Members screen
 * ([stopSub], [SubStopSource.USER]), or the run hits its round/token cap
 * (forced stop). A stopped run is still recorded as INTERRUPTED, with the
 * report it completed so far.
 */
class SubAgentRunner(
    private val settings: SettingsRepository,
    private val db: AppDatabase,
    private val tools: ToolRuntime,
    private val fs: WorkspaceFs,
    private val requestLog: RequestLog? = null,
    /** Diagnostic log (16/11/2026): the handoff steps, for the Diagnostics screen. Null = no logging. */
    private val diagnosticsLog: DiagnosticsLog? = null,
    /** History sync (Phase 4 fix, 18/11/2026): the single shared instance —
     *  the same one ChatEngine uses — so a sub's messages also reach
     *  history.md. Null = no file export. */
    private val historySync: HistorySync? = null,
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

        /** How long a finished sub waits for the chat to be free before its
         *  brief is written anyway (a stuck speaker must not swallow the
         *  result forever). */
        const val DELIVERY_WAIT_TIMEOUT_MS = 5 * 60 * 1000L
        /** How long a greeting waits for its turn in delegation order before it is written anyway. */
        const val GREETING_ORDER_TIMEOUT_MS = 30_000L
        /** How long Main's stop tool waits for the stopped run's report. */
        const val STOP_AWAIT_TIMEOUT_MS = 120_000L
    }

    /** The runner's own scope: background sub runs live here, independent
     *  of Main's turn job — so Main's Stop can never cascade to a sub. A
     *  SupervisorJob, so one run failing never takes the others down. */
    private val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeSubs = MutableStateFlow<Map<SubRunKey, ActiveSubRun>>(emptyMap())
    val activeSubs: StateFlow<Map<SubRunKey, ActiveSubRun>> = _activeSubs.asStateFlow()

    /** The run currently in progress for one agent in a conversation, if any (recall refusal, 24/11/2026). */
    fun activeRun(conversationId: String, agentId: String): ActiveSubRun? =
        _activeSubs.value[SubRunKey(conversationId, agentId)]

    /** Tokens used by finished sub runs, per conversation — the session token bar on the Members screen. */
    private val _sessionSubTokens = MutableStateFlow<Map<String, Int>>(emptyMap())
    val sessionSubTokens: StateFlow<Map<String, Int>> = _sessionSubTokens.asStateFlow()

    /** A finished sub waiting for its turn to speak: conversation → agent
     *  → the agent currently speaking (Main or another sub). The pill
     *  shows "đang chờ [tên] nói xong" while this is set. */
    private val _waitingDeliveries = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val waitingDeliveries: StateFlow<Map<String, Map<String, String>>> = _waitingDeliveries.asStateFlow()

    /** The launcher job of each running sub (a child of [runScope]). */
    private val runJobs = ConcurrentHashMap<SubRunKey, Job>()
    /** The sub's own job (a child of its launcher job): the cancellation
     *  token [stopSub]/[stopAgent] cancel, so the run stops without
     *  touching Main's turn, the launcher, or the other subs. */
    private val runSubJobs = ConcurrentHashMap<SubRunKey, Job>()
    /** The result each running sub will produce, so Main's stop tool can await it. */
    private val runResults = ConcurrentHashMap<SubRunKey, CompletableDeferred<SubRunResult>>()
    /** Runs a stop was asked for (set by [stopSub]/[stopAgent], read by the run's catch). */
    private val stopRequested = ConcurrentHashMap<SubRunKey, SubStopSource>()
    /** Greeting order: tickets handed out in delegation order, per conversation. */
    private val greetingTickets = ConcurrentHashMap<String, AtomicInteger>()
    private val greetingNext = ConcurrentHashMap<String, AtomicInteger>()
    private val runTickets = ConcurrentHashMap<SubRunKey, Int>()
    /** Serializes the runner's own message writes per conversation (several subs can write at once now). */
    private val writeMutexes = ConcurrentHashMap<String, Mutex>()
    /** Finished runs waiting for Main to be woken (wake, GĐ4 batch):
     *  conversation → outcomes not yet handed to Main. */
    private val pendingOutcomes = ConcurrentHashMap<String, MutableList<SubRunResult>>()
    /** Runs whose result Main already saw through a manual wake (peer
     *  mode, 19/11/2026): conversation → agent ids. The automatic wake
     *  skips those outcomes, so Main never gets the same result twice.
     *  One-shot: an entry is consumed when its run finishes. */
    private val manualWakeDelivered = ConcurrentHashMap<String, MutableSet<String>>()
    /** Serializes the wake bookkeeping in [noteRunFinished]. */
    private val wakeMutex = Mutex()

    /**
     * Wakes Main with finished runs (wake, GĐ4 batch): called with the
     * conversation id and the runs that finished — once when every run in
     * the conversation is done, or at once when a run errors. Set by the
     * app container; null = no waking (tests, or a runner without an
     * engine).
     */
    var onWake: ((String, List<SubRunResult>) -> Unit)? = null

    /**
     * A sub woke Main by hand with its result (peer mode, 19/11/2026):
     * remember it, so the automatic wake later does not repeat that
     * run's result. Called by the wake tool; the run itself still
     * finishes normally.
     */
    fun noteManualWakeToMain(conversationId: String, agentId: String) {
        manualWakeDelivered.getOrPut(conversationId) { ConcurrentHashMap.newKeySet() } += agentId
    }

    /**
     * Start a sub run in the background and return its key at once
     * (Phase 4): the caller does NOT wait for the result. Null when this
     * agent is already running a task in this conversation — one task at
     * a time per agent, the delegate tool reports that instead of
     * queueing a second task behind the first. [startedBy]/[startedByName]
     * say who started the run ("main"/"Main" for Main, else the peer
     * sub-agent's id/name) — the conversation screens show the brief
     * under that name (20/11/2026).
     */
    fun startRun(
        agent: SubAgentConfig,
        title: String,
        brief: String,
        conversationId: String,
        turnId: String,
        startedBy: String = "main",
        startedByName: String = "Main",
    ): SubRunKey? {
        val key = SubRunKey(conversationId, agent.id)
        val deferred = CompletableDeferred<SubRunResult>()
        if (runResults.putIfAbsent(key, deferred) != null) return null
        runTickets[key] = greetingTickets.getOrPut(conversationId) { AtomicInteger(0) }.getAndIncrement()
        // One fresh round id per delegation (25/11/2026): "Allow all in
        // this round" covers only this run — Main calling the same sub
        // twice in one turn is two rounds.
        val runKey = "sub:${agent.id}:${UUID.randomUUID()}"
        val job = runScope.launch {
            try {
                val result = run(agent, title, brief, conversationId, turnId, startedBy, startedByName, runKey)
                deferred.complete(result)
                // Wake Main (GĐ4 batch): bookkeeping only — it must never
                // break the run's completion, so a failure here is swallowed.
                runCatching { noteRunFinished(key, result) }
            } catch (e: CancellationException) {
                deferred.completeExceptionally(e)
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            } finally {
                runJobs.remove(key)
                runResults.remove(key)
                // The run is over: its "Allow all in this round" must not
                // outlive it. Never breaks completion, so swallowed.
                runCatching { tools.endRun(runKey) }
            }
        }
        runJobs[key] = job
        return key
    }

    /**
     * Stop one sub agent in this conversation ([source] says who asked:
     * Main through its stop tool, or the user from the Members screen).
     * Cancels only that sub's own job — Main's turn and the other subs
     * keep going — and awaits the run's report (partial results). Null
     * when the agent is not running, or when its report did not land in
     * time (it still lands in the sub_runs table — read it back with
     * get_completed_subagent_run).
     */
    suspend fun stopAgent(conversationId: String, agentId: String, source: SubStopSource): SubRunResult? {
        val key = SubRunKey(conversationId, agentId)
        val deferred = runResults[key] ?: return null
        if (runSubJobs.containsKey(key)) {
            stopRequested[key] = source
            runSubJobs[key]?.cancel()
        }
        return try {
            withTimeoutOrNull(STOP_AWAIT_TIMEOUT_MS) { deferred.await() }
        } catch (e: CancellationException) {
            // The run died before recording a result (its deferred holds
            // the cancellation) — or this caller was cancelled. Tell them
            // apart: a cancelled caller must propagate, a dead run just has
            // no report to return.
            if (currentCoroutineContext().isActive) null else throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Stop one sub from the Members screen. Main's turn keeps going: the
     *  run ends as INTERRUPTED and its report is stored for Main to read
     *  back. No-op when this agent is not running here. */
    fun stopSub(conversationId: String, agentId: String) {
        val key = SubRunKey(conversationId, agentId)
        if (!runResults.containsKey(key)) return
        stopRequested[key] = SubStopSource.USER
        runSubJobs[key]?.cancel()
    }

    /**
     * Wake bookkeeping (GĐ4 batch): after a run finishes, remember its
     * outcome for the conversation. Main wakes once when every run in the
     * conversation is done, or at once when a run errors — the [onWake]
     * callback carries the outcomes. Called in the launcher after the run
     * returns, wrapped in runCatching so bookkeeping can never break the
     * run's completion.
     */
    private suspend fun noteRunFinished(key: SubRunKey, result: SubRunResult) {
        val wake = wakeMutex.withLock {
            // Peer mode: a run whose result Main already saw through a
            // manual wake is not queued again — unless it failed, hit a
            // cap or was interrupted, which is new information, not the
            // repeated result. The entry is consumed either way: one run
            // finishes once.
            val manuallyDelivered = manualWakeDelivered[key.conversationId]?.remove(key.agentId) == true &&
                result.status == SubRunStatus.DONE
            val list = pendingOutcomes.getOrPut(key.conversationId) { mutableListOf() }
            if (!manuallyDelivered) list += result
            val error = isWakeError(result.status)
            // runResults still holds this run (the launcher's finally
            // removes it after this returns), so exclude this key when
            // asking whether anything else is still running.
            val othersRunning = runResults.keys.any { it.conversationId == key.conversationId && it != key }
            if (error || !othersRunning) {
                pendingOutcomes.remove(key.conversationId)?.toList()?.takeIf { it.isNotEmpty() }
            } else null
        }
        if (wake != null) onWake?.invoke(key.conversationId, wake)
    }

    /** Serializes sub_runs.meta.json writes (one file per session; runs are sequential per conversation, but two conversations could write at once). */
    private val metaMutex = Mutex()

    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    /** One run, in the background scope (started by [startRun]): the loop itself. Never called directly — the launcher owns it. */
    private suspend fun run(
        agent: SubAgentConfig,
        title: String,
        brief: String,
        conversationId: String,
        turnId: String,
        startedBy: String = "main",
        startedByName: String = "Main",
        runKey: String = "sub:${agent.id}:${UUID.randomUUID()}",
    ): SubRunResult {
        val startedAt = System.currentTimeMillis()
        val msgDao = db.messages()
        // The sub's messages belong to Main's turn (same turnId), so deleting
        // or regenerating the turn takes them along.
        val turnNo = msgDao.getTurn(conversationId, turnId).firstOrNull { it.role == "user" }?.turnNo ?: 0
        var rounds = 0
        // Consecutive-error block (24/11/2026): one blocker per run —
        // a tool that fails 3 times in a row (default) is switched off
        // for the rest of this run. A new run (Main delegating this sub
        // again) starts counting again. send_message is not counted:
        // it is handled locally below, not through tools.execute.
        val errorBlocker = ToolErrorBlocker(settings.maxConsecutiveToolErrors.value)
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
        // Chars of per-step results stored so far in this run (25/11/2026):
        // at most 2,000 per call, 20,000 per run in total.
        var stepResultsChars = 0
        val key = SubRunKey(conversationId, agent.id)
        var status = SubRunStatus.DONE
        var error: String? = null
        var finalText = ""
        var stopSource: SubStopSource? = null
        _activeSubs.update {
            it + (key to ActiveSubRun(agent.id, agent.name, brief, startedAt, roundCap = agent.roundCap, tokenBudget = agent.tokenBudget, turnNo = turnNo, title = title, startedBy = startedBy, startedByName = startedByName))
        }
        // The sub's own job: a child of the launcher job in the runner's
        // own scope (Phase 4) — never of Main's turn job, so stopping Main
        // can never reach it. It is the cancellation token stopSub /
        // stopAgent cancel; the loop below stops on it, and finish() still
        // runs in this coroutine's context, which a sub stop does not cancel.
        val subJob = Job(parent = currentCoroutineContext()[Job])
        runSubJobs[key] = subJob
        // A stop asked in the tiny window between startRun and here (the
        // flag is set, the job was not registered yet to cancel): stop the
        // loop at its first step, and remember who asked, so the run is
        // recorded with the right stop reason.
        val pendingStop = stopRequested.remove(key)
        if (pendingStop != null) subJob.cancel()
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
                // Tool-round timeout (25/11/2026): this run's own time
                // budget, from the Manage tools setting (default 3
                // minutes, no upper limit). One run of one agent — not
                // the whole turn. Checked between rounds, like the round
                // cap; a single very long tool call can still overrun.
                val timeoutMinutes = settings.maxToolRoundTimeoutMinutes.value
                val deadlineMs = startedAt + timeoutMinutes * 60_000L
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
                    updateActive(key, agent, rounds, tokensUsed)
                    if (System.currentTimeMillis() > deadlineMs) {
                        status = SubRunStatus.TIMEOUT
                        error = "Stopped: the run took longer than $timeoutMinutes minute(s) (Max tool-round timeout)."
                        break
                    }
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
                    updateActive(key, agent, rounds, tokensUsed)
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
                            handleSend(call, agent, key, title, conversationId, turnId, turnNo, mainOutbox, sentToUser)
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
                            // CallerScope (peer mode, 19/11/2026): the
                            // tool runs as this agent, so a tool that needs
                            // to know who called it (wake_agent) can read
                            // it. execute() only adds context elements, so
                            // the scope survives into the tool's run.
                            val toolUid = call.uid ?: tools.uidForName(call.name, exposed) ?: call.name
                            val result = errorBlocker.refusalFor(toolUid, call.name)?.let { refusal ->
                                ToolOutcome(toolUid, refusal, false)
                            } ?: withContext(CallerScope(agent.id) + RunScope(runKey)) {
                                tools.execute(call, turnId, exposed, conversationId)
                            }.also { errorBlocker.record(toolUid, it.isError) }
                            runCatching {
                                writeSubToolResult(conversationId, turnId, turnNo, call, result)
                            }
                            // Per-step result for the stored record
                            // (25/11/2026): the call's returned text, cut
                            // to the per-call and per-run caps, so a run
                            // that ends early still shows what its steps
                            // already found. A finished run's results are
                            // dropped at finish time instead.
                            val lastIdx = workCallRecords.lastIndex
                            if (lastIdx >= 0) {
                                val stored = truncateStepResult(
                                    result.resultText,
                                    RUN_STEP_RESULTS_MAX_CHARS - stepResultsChars,
                                )
                                stepResultsChars += stored.length
                                workCallRecords[lastIdx] =
                                    workCallRecords[lastIdx].copy(result = stored)
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
            // A stop (Members screen or Main's stop tool) or the run's own
            // scope going away: the run ends as interrupted and is still
            // recorded, with the report it completed so far. A per-sub stop
            // never rethrows — there is no Main turn waiting on this run
            // any more (Phase 4), and the launcher completes the deferred
            // with the result instead.
            val source = stopRequested.remove(key) ?: pendingStop
            stopSource = source
            status = SubRunStatus.INTERRUPTED
            error = when (source) {
                SubStopSource.USER -> "Stopped from the Members screen."
                SubStopSource.MAIN -> "Interrupted by Main."
                null -> "Interrupted by the user."
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
                status = SubRunStatus.INTERRUPTED
                error = "Interrupted by the user."
            } else {
                status = SubRunStatus.FAILED
                error = e.message ?: e.toString()
            }
        } finally {
            // A bare child Job blocks its parent's completion (16/11/2026):
            // subJob is a child of the launcher job. Without complete()
            // here, the launcher job can never complete after a normal run
            // — the sub finished, but its Job stays ACTIVE forever.
            // complete() on an already-cancelled Job is a no-op, so the
            // stopSub / stopAgent paths are unaffected.
            subJob.complete()
            _activeSubs.update { it - key }
            runSubJobs.remove(key)
            // A run that never greeted must not block the greetings behind
            // it in delegation order.
            advanceGreetingGate(key)
            runTickets.remove(key)
            stopRequested.remove(key)
        }
        // End the run: brief message + stored record + meta record. This
        // must still land when the run was cancelled, so it always runs
        // NonCancellable (a stop cancels the sub's job, not this
        // coroutine's context — but a scope cancellation would abort a
        // plain finish); a write failure never hides the original outcome
        // (finishOrFallback always returns a result).
        val result = withContext(NonCancellable) {
            finishOrFallback(agent, title, brief, conversationId, turnId, turnNo, startedAt, status, error, finalText, rounds, tokensUsed, toolCalls, mainOutbox, sentToUser, allReasoning.toString(), workCallRecords, interruptedByMain = stopSource == SubStopSource.MAIN, startedBy = startedBy, startedByName = startedByName)
        }
        // A stop asked while finish() was writing (the loop was already
        // over, so it had nothing to stop) must not linger into the next
        // run of this agent.
        stopRequested.remove(key)
        _sessionSubTokens.update { it + (conversationId to (it[conversationId] ?: 0) + result.tokensUsed) }
        // Handoff step (16/11/2026): the run returned — the next step is
        // the brief delivery (or the stop tool awaiting this result).
        diagnosticsLog?.handoff(
            "sub.run.return", conversationId, turnId,
            "agent=${agent.id} status=${result.status} rounds=${result.rounds} took=${System.currentTimeMillis() - startedAt}ms",
        )
        return result
    }

    /** Refresh the live run shown on the Members screen (rounds, tokens). No-op once the run is gone. */
    private fun updateActive(
        key: SubRunKey,
        agent: SubAgentConfig,
        rounds: Int,
        tokensUsed: Int,
    ) {
        _activeSubs.update { current ->
            val active = current[key] ?: return@update current
            current + (key to active.copy(rounds = rounds, roundCap = agent.roundCap, tokensUsed = tokensUsed, tokenBudget = agent.tokenBudget))
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
        key: SubRunKey,
        title: String,
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
                // The first user message is the greeting: written at once
                // under the delegation turn, in delegation order (no
                // waiting state — it is the run's first appearance). Every
                // later message is a late result: it waits for its turn to
                // speak and is written as a new message at the bottom,
                // carrying the task title as its anchor.
                if (sentToUser.isEmpty()) {
                    awaitGreetingTurn(key)
                    writeSubMessage(conversationId, turnId, turnNo, agent, text)
                    advanceGreetingGate(key)
                } else {
                    deliverSubMessage(key, agent, title, text)
                }
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
     * Deliver one of the sub's messages to the user as a NEW message at the
     * bottom of the chat (Phase 4): a fresh turn, so the conversation
     * never scrolls back to the run's old turn, carrying the task title
     * as its anchor ("↩ <title>"). One speaker streams at a time, so the
     * message waits until no assistant row is streaming (the pill shows
     * "đang chờ [tên] nói xong" while it waits); the wait is bounded and
     * the message is written anyway rather than lost.
     */
    private suspend fun deliverSubMessage(key: SubRunKey, agent: SubAgentConfig, title: String, text: String) {
        waitForFreeChannel(key)
        // The write itself is NonCancellable: a stop that lands while the
        // channel frees must not lose the brief.
        withContext(NonCancellable) {
            val msgDao = db.messages()
            val turnNo = (msgDao.maxTurnNo(key.conversationId) ?: 0) + 1
            writeSubMessage(key.conversationId, UUID.randomUUID().toString(), turnNo, agent, text, taskTitle = title)
        }
    }

    /** Wait until no assistant row is streaming in this conversation (one speaker at a time), then clear the waiting state. Bounded: on timeout or cancellation the caller writes anyway. */
    private suspend fun waitForFreeChannel(key: SubRunKey) {
        try {
            withTimeoutOrNull(DELIVERY_WAIT_TIMEOUT_MS) {
                db.messages().observe(key.conversationId).first { rows ->
                    val streaming = rows.firstOrNull { it.role == "assistant" && it.status == "streaming" }
                    if (streaming != null) {
                        val speaker = if (streaming.sender == null) "Main" else SubAgentStore.snapshot.displayName(streaming.sender)
                        setWaiting(key, speaker)
                        false
                    } else {
                        true
                    }
                }
            }
        } catch (e: CancellationException) {
            // Cancelled while waiting (the run was stopped, or the app is
            // going away): deliver anyway rather than lose the brief.
        } finally {
            clearWaiting(key)
        }
    }

    private fun setWaiting(key: SubRunKey, speakerName: String) {
        _waitingDeliveries.update { current ->
            val conv = current[key.conversationId] ?: emptyMap()
            if (conv[key.agentId] == speakerName) current
            else current + (key.conversationId to (conv + (key.agentId to speakerName)))
        }
    }

    private fun clearWaiting(key: SubRunKey) {
        _waitingDeliveries.update { current ->
            val conv = current[key.conversationId] ?: return@update current
            val next = conv - key.agentId
            if (next.isEmpty()) current - key.conversationId else current + (key.conversationId to next)
        }
    }

    /** Greeting order (Phase 4): greetings go in delegation order. Each run takes a ticket when it starts; a greeting waits until its ticket is the next one to speak. A run that is skipped (its turn already passed, or it timed out) greets at once instead of waiting forever. */
    private suspend fun awaitGreetingTurn(key: SubRunKey) {
        val myTicket = runTickets[key] ?: return
        val next = greetingNext.getOrPut(key.conversationId) { AtomicInteger(0) }
        val deadline = System.currentTimeMillis() + GREETING_ORDER_TIMEOUT_MS
        while (next.get() < myTicket) {
            if (System.currentTimeMillis() >= deadline) return
            delay(100)
        }
    }

    /** Let the next greeting in delegation order speak. Idempotent: a run that already greeted, or never greeted, advances past its own ticket at most once. */
    private fun advanceGreetingGate(key: SubRunKey) {
        val myTicket = runTickets[key] ?: return
        greetingNext.getOrPut(key.conversationId) { AtomicInteger(0) }.getAndUpdate { current -> maxOf(current, myTicket + 1) }
    }

    /** The mutex serializing this runner's own message writes in one conversation. */
    private fun writeMutex(conversationId: String): Mutex =
        writeMutexes.getOrPut(conversationId) { Mutex() }

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
        interruptedByMain: Boolean = false,
        startedBy: String = "main",
        startedByName: String = "Main",
    ): SubRunResult {
        return try {
            finish(agent, title, brief, conversationId, turnId, turnNo, startedAt, status, error, finalText, rounds, tokensUsed, toolCalls, mainOutbox, sentToUser, reasoning, workCallRecords, interruptedByMain, startedBy, startedByName)
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
        interruptedByMain: Boolean = false,
        startedBy: String = "main",
        startedByName: String = "Main",
    ): SubRunResult {
        val finishedAt = System.currentTimeMillis()
        val detail = finalText.trim()
        // A run Main stopped is shown in the Main↔sub conversation as
        // "saying" its completed results + "Interrupted by Main" — the app
        // records the stop, the sub does not report it. The marker goes in
        // the report body (the header is stripped there), so it reads as
        // the sub's last words.
        val reportDetail = when {
            !interruptedByMain -> detail
            detail.isBlank() -> "Interrupted by Main."
            else -> "$detail\n\nInterrupted by Main."
        }
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
                SubRunStatus.TIMEOUT -> "${agent.name} did part of the task, then ran out of time."
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
            append(reportDetail.ifBlank { "(no report text)" })
        }.trim()
        if (!alreadySent && userBrief.isNotBlank()) {
            // The brief arrives as a new message at the bottom (Phase 4),
            // never inserted into the run's old turn.
            deliverSubMessage(SubRunKey(conversationId, agent.id), agent, title, userBrief)
        }
        // The stored run record and the journal file are written together,
        // so they cannot drift apart: the table is what reads use (the
        // recall tool, the run detail screen), the file stays the
        // user-readable journal. A failed table write must not hide the
        // receipt — the file record still lands, and the receipt still
        // returns (the run just cannot be read back from the table).
        // Per-step results are only kept for runs that did not finish
        // cleanly (25/11/2026): a finished run already has the sub's full
        // report, so its step results are dropped here instead of stored.
        val storedCalls = if (status == SubRunStatus.DONE) {
            workCallRecords.map { it.copy(result = "") }
        } else {
            workCallRecords
        }
        runCatching {
            db.subRuns().insert(
                SubRunEntity(
                    conversationId = conversationId,
                    turnNo = turnNo,
                    agent = agent.id,
                    agentName = agent.name,
                    startedBy = startedBy,
                    startedByName = startedByName,
                    title = title,
                    brief = brief,
                    status = status.name.lowercase(),
                    rounds = rounds,
                    tokens = tokensUsed,
                    toolCallsJson = encodeSubRunToolCalls(storedCalls),
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
            startedAt, finishedAt, reportDetail.take(500), error, sentToUser.size, mainOutbox,
            startedBy, startedByName,
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
        writeMutex(conversationId).withLock {
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
    }

    /** One tool-result row per real tool call, keyed by the call id so the card above finds its result. Same shape as Main's tool rows. */
    private suspend fun writeSubToolResult(
        conversationId: String,
        turnId: String,
        turnNo: Int,
        call: AiToolCall,
        outcome: ToolOutcome,
    ) {
        writeMutex(conversationId).withLock {
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
    }

    /** One of the sub's own messages (a send_message to the user, or the brief report): an assistant message tagged with the agent id. */
    private suspend fun writeSubMessage(
        conversationId: String,
        turnId: String,
        turnNo: Int,
        agent: SubAgentConfig,
        text: String,
        taskTitle: String? = null,
    ) {
        writeMutex(conversationId).withLock {
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
                    taskTitle = taskTitle,
                    status = "done",
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        // Phase 4 fix (18/11/2026): a sub message used to live only in Room —
        // nothing ever exported its turn, so history.md skipped it (a real
        // session jumped T1 → T3, missing the sub's T2 result and both
        // greetings). Export the turn now, in the background, the same way
        // ChatEngine does at the end of a Main turn: try append, fall back
        // to a full export. Best-effort: the message is already safe in Room,
        // and the export runs through the shared HistorySync so file writes
        // stay serialized.
        historySync?.let { sync ->
            runScope.launch {
                runCatching {
                    if (!sync.appendTurn(conversationId, turnNo)) sync.export(conversationId)
                }
            }
        }
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
        startedBy: String = "main",
        startedByName: String = "Main",
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
                startedBy = startedBy, startedByName = startedByName,
            )
            val line = encodeSubRunRecord(record)
            metaMutex.withLock {
                if (fs.sessionExists(path)) fs.appendSessionText(path, "$line\n")
                else fs.writeSessionText(path, encodeSubRunsHeader(SubRunsHeader(session = conv.id.take(8))) + "\n$line\n")
            }
        }
    }
}
