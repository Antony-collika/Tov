package com.example.byokchat.chat

import com.example.byokchat.ai.AiEvent
import com.example.byokchat.ai.AiException
import com.example.byokchat.ai.AiRequest
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.ProviderFactory
import com.example.byokchat.ai.RequestLog
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.MessageDao
import com.example.byokchat.data.displayTitle
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.ProviderType
import com.example.byokchat.data.RequestLayout
import com.example.byokchat.data.RequestLayoutStore
import com.example.byokchat.data.SettingsRepository
import com.example.byokchat.tools.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cách nút Retry đã BỚT gửi lại sau lỗi (đợt bỏ provider 12/11/2026; sửa
 * theo ý Duy 12/11/2026): chỉ dùng khi dữ liệu hãng cần đã mất hẳn, không
 * khôi phục được — nút này tách riêng, ghi rõ, không phải Retry mặc định.
 * Retry mặc định luôn là NONE (gửi lại request đầy đủ, dựng lại từ cài đặt
 * hiện tại + reasoning đã lưu, để user sửa cài đặt xong rồi ấn Retry).
 * - NONE: gửi lại như thường (Retry mặc định);
 * - NO_TOOLS: gửi lại không kèm tools — máy chủ tương thích (vd DeepSeek)
 *   chỉ bắt buộc reasoning_content khi request có tools, nên không có
 *   tools thì không cần reasoning đã mất;
 * - DROP_LAST_TOOL_ROUND: gửi lại không có lượt tool gần nhất — chữ ký suy
 *   luận của lượt đó đã hỏng/mất nên Gemini từ chối cả request; bỏ lượt
 *   đó thì model trả lời từ chữ trong lịch sử.
 * Chỉ áp cho lần gửi Retry đó; lượt sau gửi lại bình thường.
 */
enum class RepairMode { NONE, NO_TOOLS, DROP_LAST_TOOL_ROUND }

/**
 * Điều phối việc AI trả lời. Chạy ở phạm vi toàn app nên thoát màn hình chat
 * thì AI vẫn tiếp tục trả lời; nút Dừng gọi stop().
 */
class ChatEngine(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val tools: ToolRuntime,
    private val notifier: ChatNotifier,
    private val requestLog: RequestLog? = null,
    /** Bố cục Request panel (Đợt C); null = bố cục mặc định từ Settings. */
    private val layoutStore: RequestLayoutStore? = null,
    /** Playground để đọc file của thẻ (Đợt D) và xuất history.md; null = bỏ qua. */
    private val fs: WorkspaceFs? = null,
    /** Đồng bộ hai chiều history.md (Đợt E); null = chỉ xuất một chiều như Đợt D. */
    private val historySync: HistorySync? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val activeConversations: StateFlow<Set<String>> = _active.asStateFlow()

    // Text of the answers being streamed right now (messageId -> text so far). Kept in memory so the UI can
    // update just the streaming message without waiting for the database; the DB is only written every
    // DB_FLUSH_MS and when the answer is finished.
    private val _live = MutableStateFlow<Map<String, String>>(emptyMap())
    val liveText: StateFlow<Map<String, String>> = _live.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    fun start(conversationId: String, turnId: String, repair: RepairMode = RepairMode.NONE) {
        // A new turn supersedes the running one (send while generating):
        // cancel it, but do not wait — the old turn's network calls are
        // abandoned and its late results are discarded (see run()).
        jobs[conversationId]?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                run(conversationId, turnId, repair)
            } finally {
                tools.endTurn(turnId)
                // Only the job that still owns the conversation clears the
                // active flag; a superseded job must not switch the UI back
                // to Send while the new turn is still running.
                if (jobs.remove(conversationId, job)) {
                    _active.update { it - conversationId }
                }
            }
        }
        jobs[conversationId] = job
        _active.update { it + conversationId }
        job.start()
    }

    fun stop(conversationId: String) {
        jobs[conversationId]?.cancel()
    }

    private suspend fun run(conversationId: String, turnId: String, repair: RepairMode = RepairMode.NONE) {
        val msgDao = db.messages()
        // The job running this turn: late writes check it, so a turn that
        // was superseded by a send never writes into the new turn.
        val myJob = currentCoroutineContext()[Job]
        // Đợt 4: tin assistant và kết quả tool dùng chung số lượt của tin user
        // cùng lượt (đã gán khi gửi). Tạo lại (regenerate) giữ nguyên số cũ.
        val turnNo = msgDao.getTurn(conversationId, turnId)
            .firstOrNull { it.role == "user" }?.turnNo ?: 0
        // Đợt E: tạo thư mục session ở lần gửi đầu tiên (như FPilot) — nếu
        // chưa có file session thì xuất trạng thái hiện tại của Room.
        historySync?.let { runCatching { it.ensureSession(conversationId) } }
        // Đợt bỏ provider: lượt tool gần nhất để bỏ khi Retry sau lỗi chữ ký
        // suy luận (DROP_LAST_TOOL_ROUND) — tính MỘT lần ở đầu lượt chạy, để
        // các vòng gọi tool mới trong lượt này không bị bỏ theo.
        val dropToolTurnId = if (repair == RepairMode.DROP_LAST_TOOL_ROUND) {
            msgDao.getAll(conversationId).filter { it.role == "tool" }.maxByOrNull { it.seq }?.turnId
        } else null
        var rounds = 0
        while (true) {
            val conv = db.conversations().get(conversationId) ?: return
            val assistantId = UUID.randomUUID().toString()
            val base = MessageEntity(
                id = assistantId,
                conversationId = conversationId,
                turnId = turnId,
                turnNo = turnNo,
                seq = (msgDao.maxSeq(conversationId) ?: 0) + 1,
                role = "assistant",
                text = "",
                status = "streaming",
                createdAt = System.currentTimeMillis(),
            )
            msgDao.upsert(base)

            val text = StringBuilder()
            var calls: List<AiToolCall> = emptyList()
            // Chữ ký suy luận của Gemini kèm phần chữ của lượt này (Đợt fix
            // 12/11/2026): giữ lại để gửi ở request sau. Đặt lại mỗi vòng.
            var textSignature: String? = null
            // Suy luận của lượt này (Đợt provider 12/11/2026): tích lũy
            // riêng khỏi câu trả lời (AiEvent.Reasoning), replay JSON gửi
            // lại nguyên vẹn ở request sau (AiEvent.Replay). Đặt lại mỗi vòng.
            val reasoning = StringBuilder()
            var replayJson: String? = null
            var reasoningProvider: String? = null
            var error: String? = null
            var cancelled: CancellationException? = null
            // Danh sách tool đã parse từ file tools.md của lượt này (null =
            // chưa resolve); vòng gọi tool sau khối try cũng cần nó để map tên.
            var exposed: List<com.example.byokchat.tools.ParsedTool>? = null
            try {
                // Nhà cung cấp và mô hình dùng cho mọi cuộc trò chuyện đều là lựa chọn toàn cục trong Settings.
                val cfg = settings.currentModelProvider()
                    ?: throw AiException("No AI provider is selected. Go to Settings → AI providers to add one, then tap the model name in the message box to choose a model.")
                if (cfg.apiKey.isBlank() && cfg.type != ProviderType.CUSTOM) {
                    throw AiException("The provider \"${cfg.name}\" has no API key yet. Enter the key in Settings.")
                }
                val model = settings.currentModel.value.orEmpty()
                if (model.isBlank()) throw AiException("No model is selected. Tap the model name in the message box to choose one.")
                if (!cfg.effectiveBaseUrl().startsWith("http")) {
                    throw AiException("The server address of \"${cfg.name}\" is not valid (it must start with http:// or https://).")
                }

                // Request đi theo bố cục Request panel (Đợt C): thẻ System
                // thay ô "Default system prompt"; thẻ Time BẬT thì dòng giờ
                // "Current time: YYYY-MM-DD HH:MM" được chèn vào cuối system
                // (RequestLayout.systemParts) — dòng giờ đổi mỗi phút nên cache
                // không dùng lại được khi thẻ bật. `time.now` vẫn là công cụ
                // bình thường trong danh sách Tools.
                val baseLayout = layoutStore?.effectiveLayout?.value
                    ?: RequestLayout.layoutFromSettings(settings.defaultSystemPrompt.value)
                val vars = RequestLayout.requestVars(model, cfg.name)
                // Đợt D: thẻ System/Free trỏ file được đọc lại mỗi lần gửi.
                // Đường RCP luôn chạy, kể cả khi chưa chọn playground folder
                // (fs == null, quyết định 13): reader trả về lỗi thay vì
                // crash — nguồn Text resolve bình thường (không cần đọc
                // file), nguồn File đọc lỗi thì đường lỗi đã có của RCP
                // chặn gửi. Không còn đường "legacy" offer tất cả tool.
                val readFile: (String) -> Result<String> = { path ->
                    runCatching { fs?.readCardFile(path) ?: error("No AI playground folder has been chosen.") }
                }
                val (layout, fileErrors) = withContext(Dispatchers.IO) { baseLayout.resolveFiles(readFile) }
                // Đợt E phần A: file tools.md của thẻ Tools cũng đọc + parse
                // lại mỗi lần gửi. Parse lỗi thì CHẶN lần gửi và báo rõ lỗi
                // (như FPilot) — không gửi với danh sách tool sai.
                val toolsResolution = withContext(Dispatchers.IO) {
                    layout.resolveToolFiles(readFile, tools.originDescriptions())
                }
                layoutStore?.setFileErrors(fileErrors + toolsResolution.errors)
                if (toolsResolution.errors.isNotEmpty()) {
                    throw AiException(
                        "The Tools card file could not be used:\n" + toolsResolution.errors.joinToString("\n"),
                    )
                }
                exposed = toolsResolution.exposed
                // Đợt bỏ provider: Retry NO_TOOLS thì không offer tool nào ở
                // lượt này (model trả lời từ chữ trong lịch sử).
                val toolDefs = if (repair == RepairMode.NO_TOOLS) emptyList()
                else layout.effectiveToolDefs(tools.definitions(exposed))
                val system = layout.systemText(vars)
                val budget = ContextBuilder.budget(
                    cfg, model, system,
                    toolDefs.sumOf { TokenEstimator.text(it.name + it.description + it.schema.toString()) },
                )
                val assembled = RequestAssembler.assemble(
                    layout = layout,
                    rows = msgDao.getAll(conversationId).filter { it.id != assistantId },
                    toolDefs = toolDefs,
                    budget = budget,
                    nameForUid = { uid -> tools.nameForUid(uid, exposed) },
                    vars = vars,
                    dropToolTurnId = dropToolTurnId,
                )
                // Đợt 6: Metadata (một nguồn duy nhất với previewAssembled):
                // cùng đọc settings.metadata. Tool luôn được gửi đủ, với mọi
                // hãng (effort theo từng hãng, không ép "none" chung nữa —
                // xem MetadataParams, đợt provider 12/11/2026).
                val metadata = settings.metadata.value
                val request = AiRequest(
                    model,
                    assembled.system,
                    assembled.sentMessages,
                    assembled.toolDefs,
                    cfg.maxOutputTokens,
                    metadata,
                    // Đợt fix 11/11/2026 (F1/F2): provider ghi session + số lượt
                    // vào request log để log theo từng chat.
                    sessionId = conversationId,
                    turnNo = turnNo,
                )

                var lastFlush = System.currentTimeMillis()
                var lastLive = 0L
                // Hãng đã sinh ra suy luận của lượt này (để lượt sau biết
                // replay JSON có dùng được không — hãng nào chỉ hãng đó đọc).
                reasoningProvider = when (cfg.type) {
                    ProviderType.OPENAI -> "openai"
                    ProviderType.CUSTOM -> "custom"
                    else -> null
                }
                ProviderFactory.create(cfg, requestLog).stream(request).collect { ev ->
                    when (ev) {
                        is AiEvent.Text -> {
                            text.append(ev.delta)
                            ev.signature?.let { textSignature = it }
                            val now = System.currentTimeMillis()
                            if (now - lastLive >= LIVE_INTERVAL_MS) {
                                _live.update { it + (assistantId to text.toString()) }
                                lastLive = now
                            }
                            if (now - lastFlush > DB_FLUSH_MS) {
                                msgDao.updateText(assistantId, text.toString())
                                lastFlush = now
                            }
                        }
                        is AiEvent.Reasoning -> reasoning.append(ev.delta)
                        is AiEvent.Replay -> replayJson = ev.json
                        is AiEvent.ToolCalls -> calls = ev.calls
                    }
                }
            } catch (e: CancellationException) {
                cancelled = e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }

            calls = calls.map { it.copy(uid = tools.uidForName(it.name, exposed)) }

            withContext(NonCancellable) {
                // Show the complete text right away, then persist it and drop the in-memory copy.
                if (text.isNotEmpty()) _live.update { it + (assistantId to text.toString()) }
                when {
                    error != null -> msgDao.upsert(base.copy(text = text.toString(), status = "error", error = error))
                    text.isEmpty() && calls.isEmpty() ->
                        if (cancelled != null) msgDao.deleteById(assistantId)
                        else msgDao.upsert(base.copy(status = "error", error = "The AI returned no content."))
                    else -> msgDao.upsert(
                        base.copy(
                            text = text.toString(),
                            toolCallsJson = if (calls.isEmpty()) null else json.encodeToString(callsSer, calls),
                            thoughtSignature = textSignature,
                            // Đợt provider 12/11/2026: chỉ gắn hãng khi thật
                            // sự có suy luận/replay (tin không có suy luận thì
                            // để null như tin cũ).
                            reasoningText = reasoning.toString().takeIf { it.isNotBlank() },
                            reasoningReplay = replayJson,
                            reasoningProvider = if (reasoning.isNotBlank() || replayJson != null) reasoningProvider else null,
                            status = "done",
                        )
                    )
                }
                db.conversations().touch(conversationId, System.currentTimeMillis())
                _live.update { it - assistantId }
            }
            cancelled?.let { throw it }
            if (error != null || calls.isEmpty()) {
                notifyFinished(conversationId, displayTitle(conv.title), success = error == null && text.isNotEmpty(), answer = text.toString())
                // Ghi file ở nền (Đợt fix 12/11/2026 — nút gửi): trả quyền cho
                // user ngay, không chờ ghi history.md xong mới tắt nút gửi.
                exportHistoryInBackground(conversationId, turnNo)
                return
            }

            // AI yêu cầu gọi công cụ: chạy rồi quay lại vòng lặp để AI đọc kết quả
            rounds++
            if (rounds > MAX_TOOL_ROUNDS) {
                msgDao.upsert(
                    base.copy(
                        id = UUID.randomUUID().toString(),
                        turnNo = turnNo,
                        seq = (msgDao.maxSeq(conversationId) ?: 0) + 1,
                        status = "error",
                        text = "",
                        toolCallsJson = null,
                        error = "Stopped because the AI called tools for more than $MAX_TOOL_ROUNDS rounds in a row.",
                    )
                )
                notifyFinished(conversationId, displayTitle(conv.title), success = false, answer = "")
                // Ghi file ở nền (Đợt fix 12/11/2026 — nút gửi): như trên.
                exportHistoryInBackground(conversationId, turnNo)
                return
            }
            for (call in calls) {
                val outcome = try {
                    tools.execute(call, turnId, exposed, conversationId)
                } catch (e: CancellationException) {
                    // The user stopped the turn mid-tool: mark every call of
                    // this round that has no result yet as Interrupted, so
                    // the next request stays valid and the model knows it was
                    // interrupted, not failed. Calls that already have a
                    // result are left alone.
                    writeInterruptedResults(msgDao, conversationId, turnId, turnNo, calls, calls.indexOf(call))
                    throw e
                }
                // Superseded by a send while this turn was running: the new
                // turn owns the conversation now, so this result is dropped
                // instead of landing in the new turn's history.
                if (jobs[conversationId] !== myJob) return
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
                    )
                )
            }
        }
    }

    /**
     * Write "Interrupted by the user." tool results for the calls of a round
     * that have none yet (Stop press mid-tool, or a send that superseded the
     * turn). Skips calls that already have a result, so the marker is never
     * written twice for one call.
     */
    private suspend fun writeInterruptedResults(
        msgDao: MessageDao,
        conversationId: String,
        turnId: String,
        turnNo: Int,
        calls: List<AiToolCall>,
        fromIndex: Int,
    ) {
        if (calls.isEmpty()) return
        val existing = msgDao.getAll(conversationId)
            .filter { it.role == "tool" }
            .mapNotNull { it.toolCallId }
            .toSet()
        for (call in calls.subList(fromIndex.coerceIn(0, calls.size), calls.size)) {
            if (call.id in existing) continue
            msgDao.upsert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    turnId = turnId,
                    turnNo = turnNo,
                    seq = (msgDao.maxSeq(conversationId) ?: 0) + 1,
                    role = "tool",
                    text = ModelFacingText.INTERRUPTED,
                    toolCallId = call.id,
                    toolUid = call.uid,
                    toolName = call.name,
                    toolIsError = false,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Mark the latest turn's tool calls as Interrupted, for the calls that
     * have no result yet. Called synchronously by a send that supersedes the
     * running turn, so the new turn's history is well-formed before it
     * starts. Does nothing when the latest turn called no tools, or when
     * every call already has a result.
     */
    suspend fun markCurrentTurnInterrupted(conversationId: String) {
        val msgDao = db.messages()
        val rows = msgDao.getAll(conversationId)
        val lastTurnId = rows.map { it.turnId }.distinct().lastOrNull() ?: return
        val turnRows = rows.filter { it.turnId == lastTurnId }
        val turnNo = turnRows.firstOrNull { it.role == "assistant" }?.turnNo ?: return
        val calls = turnRows.filter { it.role == "assistant" }.flatMap { m ->
            runCatching { json.decodeFromString(callsSer, m.toolCallsJson ?: "[]") }.getOrDefault(emptyList())
        }
        writeInterruptedResults(msgDao, conversationId, lastTurnId, turnNo, calls, 0)
    }

    /** Báo cho người dùng khi AI trả lời xong (hoặc lỗi) mà app không đang hiện. Không gọi khi người dùng bấm Dừng. */
    /** Xuất lịch sử ra history.md + history.meta.json trong Session storage (Đợt E).
     * Sửa trong app thì ghi ra ngay (ghi .md trước rồi .meta.json, verify bằng
     * đọc lại — xem HistorySync.export). Best-effort. */
    suspend fun exportHistory(conversationId: String) {
        if (historySync != null) {
            historySync.export(conversationId)
            return
        }
        val f = fs ?: return
        HistoryExporter.exportHistory(f, db, conversationId)
    }

    /**
     * Xuất sau khi một lượt vừa xong (Đợt fix 12/11/2026): thử append lượt đó
     * vào cuối file trước; không append được thì ghi đủ. Sửa giữa (edit/xóa/
     * hoàn tác) thì dùng [exportHistory] không số lượt — luôn ghi đủ.
     *
     * Đợt fix 12/11/2026 (nút gửi): hàm này CHỈ gọi qua
     * [exportHistoryInBackground] ở cuối lượt — không chờ ghi file xong mới
     * trả quyền cho user.
     */
    suspend fun exportHistory(conversationId: String, turnNo: Int) {
        if (historySync != null) {
            if (historySync.appendTurn(conversationId, turnNo)) return
            historySync.export(conversationId)
            return
        }
        val f = fs ?: return
        HistoryExporter.exportHistory(f, db, conversationId)
    }

    /**
     * Ghi history.md + history.meta.json ở nền sau khi một lượt xong (Đợt fix
     * 12/11/2026 — nút gửi): `run()` trả về ngay để nút gửi trở lại, file ghi
     * xong ở nền trong scope của engine (sống lâu hơn từng coroutine `run()`),
     * nên user gõ/bấm gửi lượt tiếp theo được luôn. Tin đã lưu vào Room TRƯỚC
     * khi trả về nên dù ghi file còn đang chạy nền thì tin vẫn an toàn. Các
     * lần ghi xếp hàng một trong HistorySync (writeMutex) nên không đụng nhau
     * khi user gửi lượt mới trong lúc lượt cũ chưa ghi xong. Best-effort như
     * [exportHistory]: lỗi ghi được nuốt bên trong, không ném ra ngoài.
     */
    private fun exportHistoryInBackground(conversationId: String, turnNo: Int) {
        scope.launch { exportHistory(conversationId, turnNo) }
    }

    private fun notifyFinished(conversationId: String, title: String, success: Boolean, answer: String) {
        if (AppVisibility.inForeground) return
        val preview = if (success) answer.trim().replace("\n", " ").take(100) else "Couldn't answer. Open the app to see the error."
        notifier.notifyAnswer(conversationId, title, preview)
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 12
        private const val LIVE_INTERVAL_MS = 150L
        private const val DB_FLUSH_MS = 3000L
    }
}
