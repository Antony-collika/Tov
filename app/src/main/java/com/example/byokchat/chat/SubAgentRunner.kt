package com.example.byokchat.chat

import com.example.byokchat.ai.AiEvent
import com.example.byokchat.ai.AiMessage
import com.example.byokchat.ai.AiRequest
import com.example.byokchat.ai.ProviderFactory
import com.example.byokchat.ai.RequestLog
import com.example.byokchat.ai.Role
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.ProviderType
import com.example.byokchat.data.SettingsRepository
import com.example.byokchat.tools.ParsedTool
import com.example.byokchat.tools.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** A sub-agent run currently in progress in a conversation (members list + typing indicator read this). */
data class ActiveSubRun(
    val agentId: String,
    val name: String,
    val brief: String,
    val startedAt: Long,
)

/**
 * Runs one sub-agent synchronously inside Main's turn (Phase 2): Main waits
 * for the result. The sub gets its own system prompt, its own tools and its
 * own folder; it never sees the rest of the conversation, only the brief.
 *
 * Flow: greet the user (scripted by Main) → loop tool rounds (own round cap
 * + token budget) → on finish, write the brief report as the sub's own
 * message and return the detailed report to Main (the delegate tool's
 * result). Every run is recorded in sub_runs.meta.json in the session
 * folder. Dismiss is implicit here: when the run ends, the sub leaves the
 * member list (it stays in the team and can be called again).
 *
 * Stop: the run shares Main's turn, so stopping Main stops the sub too
 * (the cancellation propagates through the provider stream); the run is
 * still recorded as interrupted.
 */
class SubAgentRunner(
    private val settings: SettingsRepository,
    private val db: AppDatabase,
    private val tools: ToolRuntime,
    private val fs: WorkspaceFs,
    private val requestLog: RequestLog? = null,
) {
    private val _activeSubs = MutableStateFlow<Map<String, ActiveSubRun>>(emptyMap())
    val activeSubs: StateFlow<Map<String, ActiveSubRun>> = _activeSubs.asStateFlow()

    /** Serializes sub_runs.meta.json writes (one file per session; runs are sequential per conversation, but two conversations could write at once). */
    private val metaMutex = Mutex()

    suspend fun run(
        agent: SubAgentConfig,
        brief: String,
        greeting: String?,
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
        var status = SubRunStatus.DONE
        var error: String? = null
        var finalText = ""
        _activeSubs.update { it + (conversationId to ActiveSubRun(agent.id, agent.name, brief, startedAt)) }
        // The sub greets the user when called in (scripted by Main; the
        // agent's default when Main passes none).
        writeSubMessage(conversationId, turnId, turnNo, agent, greeting ?: agent.defaultGreeting)
        try {
            val cfg = settings.currentModelProvider()
            val model = settings.currentModel.value.orEmpty()
            if (cfg == null) {
                status = SubRunStatus.FAILED
                error = "No AI provider is selected. Go to Settings → AI providers to add one."
            } else if (model.isBlank()) {
                status = SubRunStatus.FAILED
                error = "No model is selected."
            } else {
                val defs = tools.definitionsForUids(agent.toolUids)
                val exposed = defs.map { (uid, def) -> ParsedTool(uid, def.name, def.description) }
                val toolDefs = defs.map { it.second }
                val system = agent.systemPrompt
                val subMessages = mutableListOf(AiMessage(Role.USER, text = brief))
                val reasoningProvider = when (cfg.type) {
                    ProviderType.OPENAI -> "openai"
                    ProviderType.CUSTOM -> "custom"
                    else -> null
                }
                while (true) {
                    // Token budget (rough estimate, like the main engine's
                    // budget): stop before a round that would pass it.
                    val estimate = TokenEstimator.text(system) +
                        subMessages.sumOf { TokenEstimator.message(it) } +
                        toolDefs.sumOf { TokenEstimator.text(it.name + it.description + it.schema.toString()) }
                    tokensUsed = maxOf(tokensUsed, estimate)
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
                    var calls: List<com.example.byokchat.ai.AiToolCall> = emptyList()
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
                    if (calls.isEmpty()) {
                        finalText = text.toString()
                        break
                    }
                    rounds++
                    subMessages += AiMessage(
                        Role.ASSISTANT, text = text.toString(), toolCalls = calls,
                        thoughtSignature = textSignature,
                        reasoningText = reasoning.toString().takeIf { it.isNotBlank() },
                        reasoningReplay = replayJson,
                        reasoningProvider = if (reasoning.isNotBlank() || replayJson != null) reasoningProvider else null,
                    )
                    for (call in calls) {
                        val outcome = tools.execute(call, turnId, exposed, conversationId)
                        toolCalls += call.name
                        subMessages += AiMessage(
                            Role.TOOL, text = outcome.resultText,
                            toolCallId = call.id, toolName = call.name, isError = outcome.isError,
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            // Stop pressed mid-run: record the interruption, then let the
            // cancellation propagate so Main's turn settles as interrupted.
            status = SubRunStatus.FAILED
            error = "Interrupted by the user."
            withContext(NonCancellable) {
                finish(agent, brief, conversationId, turnId, turnNo, startedAt, status, error, finalText, rounds, tokensUsed, toolCalls)
            }
            _activeSubs.update { it - conversationId }
            throw e
        }
        val result = finish(agent, brief, conversationId, turnId, turnNo, startedAt, status, error, finalText, rounds, tokensUsed, toolCalls)
        _activeSubs.update { it - conversationId }
        return result
    }

    /**
     * End a run: split the final answer into brief (user) + detail (Main),
     * write the brief as the sub's own message, record the run in
     * sub_runs.meta.json, and return the detailed report.
     */
    private suspend fun finish(
        agent: SubAgentConfig,
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
    ): SubRunResult {
        val finishedAt = System.currentTimeMillis()
        val (detail, briefText) = if (finalText.isBlank()) "" to "" else splitBriefDetail(finalText)
        val userBrief = briefText.ifBlank {
            when (status) {
                SubRunStatus.DONE -> finalText.take(300)
                SubRunStatus.ROUND_CAP -> "${agent.name} did part of the task, then ran out of tool rounds."
                SubRunStatus.BUDGET_EXCEEDED -> "${agent.name} did part of the task, then ran out of token budget."
                SubRunStatus.FAILED -> "${agent.name} couldn't finish: ${error ?: "unknown error"}"
            }
        }
        val detailed = buildString {
            appendLine("Sub-agent ${agent.name} finished: ${status.name.lowercase().replace('_', ' ')}.")
            appendLine("Task: $brief")
            if (toolCalls.isNotEmpty()) appendLine("Tools called: ${toolCalls.joinToString(", ")}")
            appendLine("Rounds: $rounds/${agent.roundCap}, estimated tokens: ~$tokensUsed/${agent.tokenBudget}.")
            if (error != null) appendLine("Note: $error")
            appendLine()
            append(detail.ifBlank { "(no report text)" })
        }.trim()
        writeSubMessage(conversationId, turnId, turnNo, agent, userBrief)
        writeMetaRecord(
            conversationId, agent, brief, status, rounds, tokensUsed, toolCalls,
            startedAt, finishedAt, detail.take(500), error,
        )
        return SubRunResult(
            agentId = agent.id, agentName = agent.name, brief = userBrief, detailed = detailed,
            status = status, rounds = rounds, tokensUsed = tokensUsed,
            toolCalls = toolCalls.toList(), startedAt = startedAt, finishedAt = finishedAt, error = error,
        )
    }

    /** One of the sub's own messages (greeting or brief report): an assistant message tagged with the agent id. */
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
    ) {
        runCatching {
            val conv = db.conversations().get(conversationId) ?: return
            val path = HistoryExporter.subRunsPath(conv)
            val record = SubRunRecord(
                agent = agent.id, agentName = agent.name, brief = brief,
                status = status.name.lowercase(), rounds = rounds, tokens = tokens,
                toolCalls = toolCalls, startedAt = startedAt, finishedAt = finishedAt,
                resultSummary = resultSummary, error = error,
            )
            val line = encodeSubRunRecord(record)
            metaMutex.withLock {
                if (fs.sessionExists(path)) fs.appendSessionText(path, "$line\n")
                else fs.writeSessionText(path, encodeSubRunsHeader(SubRunsHeader(session = conv.id.take(8))) + "\n$line\n")
            }
        }
    }
}
