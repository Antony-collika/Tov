package com.example.byokchat.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.byokchat.AppContainer
import com.example.byokchat.ai.ImageStore
import com.example.byokchat.ai.MetadataParams
import com.example.byokchat.ai.ProviderFactory
import com.example.byokchat.chat.AssembledRequest
import com.example.byokchat.chat.ContextBuilder
import com.example.byokchat.chat.HistoryExporter
import com.example.byokchat.chat.HistorySync
import com.example.byokchat.chat.Mention
import com.example.byokchat.chat.MentionDecision
import com.example.byokchat.chat.RepairMode
import com.example.byokchat.chat.RequestAssembler
import com.example.byokchat.chat.TokenEstimator
import com.example.byokchat.chat.decideMention
import com.example.byokchat.chat.mentionTitle
import com.example.byokchat.chat.realUsedTokens
import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.isDefaultTitle
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import com.example.byokchat.data.RequestLayout
import com.example.byokchat.data.encodeImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

data class TokenStats(
    val used: Int,
    val limit: Int,
    val droppedTurns: Int,
    val droppedTurnIds: List<String> = emptyList(),
    /** false = `used` là số thật server đếm ở lượt vừa rồi; true = ước lượng. */
    val isEstimate: Boolean = true,
)

/** Kết quả xem trước request: sẵn sàng, hoặc lý do cụ thể vì sao chưa có gì để gửi. */
sealed interface PreviewResult {
    data class Ready(
        val request: AssembledRequest,
        val providerName: String,
        val model: String,
        /** Hãng đang dùng — Inspector cần để hiện đúng tên trường metadata (vd reasoning_effort vs thinkingLevel). */
        val providerType: ProviderType,
        /** Metadata của lần gửi này (cùng giá trị ChatEngine dùng — một nguồn duy nhất). */
        val metadata: MetadataParams,
        /** Max output tokens của provider đang dùng (theo từng provider). */
        val maxOutputTokens: Int,
    ) : PreviewResult
    data class Unavailable(val reason: PreviewUnavailable) : PreviewResult
}

enum class PreviewUnavailable {
    /** Cuộc trò chuyện không còn trong DB (đã xóa). */
    CONVERSATION_NOT_FOUND,
    /** Chưa chọn provider nào. */
    NO_PROVIDER,
    /** Provider đang chọn không còn trong danh sách (đã bị xóa). */
    PROVIDER_UNAVAILABLE,
    /** Đã chọn provider nhưng chưa chọn model (kể cả model rỗng). */
    NO_MODEL,
}

/** Danh sách mô hình chỉ làm mới lại sau 5 phút kể từ lần thành công trước. */
private const val MODELS_TTL_MS = 5 * 60 * 1000L

sealed interface ChatEvent {
    data class Deleted(val backup: List<MessageEntity>) : ChatEvent
    /** A branch was created; the screen should switch to it (and offer a way back to the original). */
    data class OpenBranch(val id: String) : ChatEvent
    data class Error(val message: String) : ChatEvent
}

@OptIn(FlowPreview::class)
class ChatViewModel(private val app: AppContainer, val conversationId: String) : ViewModel() {
    private val db = app.db

    val conversation: StateFlow<ConversationEntity?> =
        db.conversations().observe(conversationId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages: StateFlow<List<MessageEntity>> =
        db.messages().observe(conversationId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val generating: StateFlow<Boolean> =
        app.engine.activeConversations.map { conversationId in it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** The sub-agent team (Phase 3): loaded from agents.json, edited in the agent settings. */
    val team: StateFlow<com.example.byokchat.chat.SubAgentTeam> = app.agentStore.team

    /** Most members at once this session (the ViewModel lives while the chat is open). */
    private val _peakMembers = MutableStateFlow(2)
    val peakMembers: StateFlow<Int> = _peakMembers.asStateFlow()

    /** A finished sub waiting for its turn to speak (Phase 4): agent id → the agent currently speaking, in this conversation. */
    val waitingDeliveries: StateFlow<Map<String, String>> =
        app.subRunner.waitingDeliveries.map { it[conversationId] ?: emptyMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Group chat members (Phase 3, async in Phase 4): every chat starts
     * with 2 members — you and Main. Main shows as Working while the
     * engine is answering this conversation, Available otherwise. The
     * whole team is listed: a running sub shows Working with its live
     * rounds/tokens, a finished sub waiting for its turn to speak shows
     * Waiting, the rest show Available (a turned-off agent stays listed,
     * marked, so its settings stay reachable).
     */
    val members: StateFlow<List<GroupMember>> = combine(
        generating,
        app.subRunner.activeSubs,
        app.subRunner.waitingDeliveries,
        app.agentStore.team,
    ) { gen, subs, waiting, team ->
        val base = listOf(
            GroupMember(id = "you", name = "You", role = "You", status = MemberStatus.IDLE, isUser = true),
            GroupMember(
                id = "main", name = "Main", role = "Main agent",
                status = if (gen) MemberStatus.WORKING else MemberStatus.IDLE,
                currentTask = if (gen) "Answering…" else null,
            ),
        )
        val convSubs = subs.filterKeys { it.conversationId == conversationId }
        val convWaiting = waiting[conversationId] ?: emptyMap()
        val agents = team.agents.map { agent ->
            val running = convSubs.entries.firstOrNull { it.key.agentId == agent.id }?.value
            val waitingFor = convWaiting[agent.id]
            when {
                running != null -> GroupMember(
                    id = agent.id, name = agent.name, role = agent.role,
                    status = MemberStatus.WORKING,
                    currentTask = running.brief.take(120),
                    startedAt = running.startedAt,
                    roundsUsed = running.rounds, roundCap = running.roundCap,
                    tokensUsed = running.tokensUsed, tokenBudget = running.tokenBudget,
                    enabled = agent.enabled, isAgent = true,
                )
                waitingFor != null -> GroupMember(
                    id = agent.id, name = agent.name, role = agent.role,
                    status = MemberStatus.WAITING,
                    currentTask = "Đã xong · đang chờ $waitingFor nói xong",
                    enabled = agent.enabled, isAgent = true,
                )
                else -> GroupMember(
                    id = agent.id, name = agent.name, role = agent.role,
                    status = MemberStatus.IDLE, enabled = agent.enabled, isAgent = true,
                )
            }
        }
        base + agents
    }.onEach { _peakMembers.value = maxOf(_peakMembers.value, it.size) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), defaultMembers())

    /** Session header for the Members screen (Phase 3): id, start, peak, message counts, session token bar, journal path. */
    // Note: two nested 3-flow combines, not one 6-flow combine — kotlinc
    // could not infer the transform lambda of combine(6 flows) here
    // (it fell back to the vararg overload), while 2- and 3-flow
    // combines infer fine elsewhere in this file.
    val sessionInfo: StateFlow<SessionInfo?> = combine(
        combine(conversation, messages, peakMembers) { conv, msgs, peak -> Triple(conv, msgs, peak) },
        combine(app.subRunner.activeSubs, app.subRunner.sessionSubTokens, app.agentStore.team) { subs, subTokens, team ->
            Triple(subs, subTokens, team)
        },
    ) { first, second ->
        val (conv, msgs, peak) = first
        val (subs, subTokens, team) = second
        if (conv == null) null else buildSessionInfo(
            conversationId = conv.id,
            startedAt = conv.createdAt,
            peakMembers = peak,
            messageCount = msgs.size,
            subMessageCount = msgs.count { it.sender != null },
            finishedSubTokens = subTokens[conversationId] ?: 0,
            liveSubTokens = subs.filterKeys { it.conversationId == conversationId }.values.sumOf { it.tokensUsed },
            teamTokenBudget = team.enabledAgents().sumOf { it.tokenBudget },
            journalPath = HistoryExporter.subRunsPath(conv),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Stop one sub in this conversation (Members screen, per agent): Main keeps going. */
    fun stopSub(agentId: String) {
        app.subRunner.stopSub(conversationId, agentId)
    }

    val providers: StateFlow<List<ProviderConfig>> = app.settings.providers

    /** Lựa chọn mô hình toàn cục: mọi cuộc trò chuyện đều dùng mô hình này. */
    val currentModelProviderId: StateFlow<String?> = app.settings.currentModelProviderId
    val currentModel: StateFlow<String?> = app.settings.currentModel

    /**
     * Ghép request cho lần gửi kế tiếp, đúng đường như ChatEngine.run (kể cả
     * danh sách công cụ đã lọc). Dùng chung cho hộp «Context window» (chỉ lấy
     * số liệu) và màn Inspector (lấy toàn bộ), nên hai chỗ không lệch nhau.
     *
     * Đọc DB trực tiếp bằng DAO như ChatEngine, thay vì đọc `.value` của
     * flow `conversation`/`messages`: hai flow dùng `WhileSubscribed` nên ở
     * màn Inspector — nơi không có ai collect chúng — `.value` mãi là giá
     * trị khởi tạo (null / rỗng) và preview luôn báo sai là chưa chọn model.
     * Khi chưa có gì để gửi thì trả về lý do cụ thể thay vì null chung chung,
     * để màn hình báo đúng nguyên nhân (chưa có cuộc trò chuyện / chưa chọn
     * provider / provider đã bị xóa / chưa chọn model).
     */
    suspend fun previewAssembled(): PreviewResult = withContext(Dispatchers.Default) {
        if (db.conversations().get(conversationId) == null)
            return@withContext PreviewResult.Unavailable(PreviewUnavailable.CONVERSATION_NOT_FOUND)
        // Đợi kho provider đọc xong (nền, lúc mở app) để không nhầm "chưa
        // tải xong" với "provider đã bị xóa"; quá 5 giây thì dùng giá trị hiện có.
        withTimeoutOrNull(5_000) { app.settings.ready.first { it } }
        val providerId = currentModelProviderId.value
            ?: return@withContext PreviewResult.Unavailable(PreviewUnavailable.NO_PROVIDER)
        val cfg = providers.value.firstOrNull { it.id == providerId }
            ?: return@withContext PreviewResult.Unavailable(PreviewUnavailable.PROVIDER_UNAVAILABLE)
        // Kiểm tra model rỗng như đường gửi (ChatEngine ném lỗi khi model rỗng).
        val modelName = currentModel.value.orEmpty()
        if (modelName.isBlank()) return@withContext PreviewResult.Unavailable(PreviewUnavailable.NO_MODEL)
        val rows = db.messages().getAll(conversationId)
        val baseLayout = app.layoutStore.effectiveLayout.value
        val vars = RequestLayout.requestVars(modelName, cfg.name)
        // Đợt D: thẻ System/Free trỏ file được đọc lại mỗi lần xem trước.
        val (layout, fileErrors) = withContext(Dispatchers.IO) {
            baseLayout.resolveFiles { path -> runCatching { app.fs.readCardFile(path) } }
        }
        app.layoutStore.setFileErrors(fileErrors)
        // RCP (17/11/2026): như đường gửi — thẻ Tools chỉ chọn trong app,
        // không còn parse file tools.md. Thẻ chưa có lựa chọn đã lưu thì
        // mặc định chọn hết.
        val selectedUids = layout.toolsCard()?.takeIf { it.enabled }?.effectiveSelectedToolUids(app.registry.all.map { it.uid }).orEmpty()
        val defsForUids = app.registry.definitionsForUids(selectedUids)
        val exposed = defsForUids.map { (uid, def) ->
            com.example.byokchat.tools.ParsedTool(uid, def.name, def.description)
        }
        val toolDefs = layout.effectiveToolDefs(app.registry.definitions(exposed))
        val system = layout.systemText(vars)
        val budget = ContextBuilder.budget(
            cfg, modelName, system,
            toolDefs.sumOf { TokenEstimator.text(it.name + it.description + it.schema.toString()) },
        )
        val assembled = RequestAssembler.assemble(
            layout = layout,
            rows = rows,
            toolDefs = toolDefs,
            budget = budget,
            nameForUid = { uid -> app.registry.nameForUid(uid, exposed) },
            vars = vars,
        )
        // Đợt 6: cùng logic metadata với ChatEngine.run (một nguồn duy nhất).
        // Tool luôn được gửi đủ, với mọi hãng (xem MetadataParams.effectiveEffort).
        val metadata = app.settings.metadata.value
        PreviewResult.Ready(
            request = assembled,
            providerName = cfg.name,
            model = modelName,
            providerType = cfg.type,
            metadata = metadata,
            maxOutputTokens = cfg.maxOutputTokens,
        )
    }

    /**
     * Đếm token của phần ngữ cảnh sẽ gửi đi. Chỉ gọi khi người dùng mở hộp «Context window»
     * (trước đây chạy liên tục nên mỗi lần AI viết thêm chữ lại duyệt cả lịch sử).
     * Số đã dùng ưu tiên số thật server đếm ở lượt Main gần nhất (xem realUsedOrEstimate);
     * giới hạn là con số toàn cục do user đặt (mặc định 100k).
     */
    suspend fun computeTokenStats(): TokenStats? {
        val preview = previewAssembled()
        if (preview !is PreviewResult.Ready) return null
        val assembled = preview.request
        val limit = app.settings.contextWindowTokens.value
        val (used, isEstimate) = realUsedOrEstimate(assembled)
        return TokenStats(
            used,
            limit,
            assembled.droppedTurnIds.size,
            assembled.droppedTurnIds,
            isEstimate,
        )
    }

    /**
     * Số thật đã dùng cho lượt sắp gửi (20/11/2026): input + output server
     * đếm ở lượt Main gần nhất trong request log, điều chỉnh cho các lượt
     * thêm vào hoặc bị cắt kể từ đó (xem realUsedTokens). Không có số thật
     * (log tắt, hãng không báo, đổi model) thì dùng ước lượng của
     * RequestAssembler như trước.
     *
     * Lưu ý: log ghi cả lượt của sub-agent; mục mới nhất cùng model/provider
     * thường là lượt Main vừa rồi (Main stream cuối, sau khi các sub xong).
     * Nếu hộp mở đúng lúc một sub đang chạy thì có thể hiện số của sub — chỉ
     * ảnh hưởng con số hiển thị trong hộp, không ảnh hưởng việc gửi.
     */
    private suspend fun realUsedOrEstimate(assembled: AssembledRequest): Pair<Int, Boolean> {
        val model = currentModel.value.orEmpty()
        val cfgName = providers.value.firstOrNull { it.id == currentModelProviderId.value }?.name
        val entry = withContext(Dispatchers.IO) { app.requestLog.entries(conversationId) }
            .firstOrNull {
                it.usage?.inputTokens != null && it.model == model &&
                    (cfgName == null || it.provider == cfgName)
            }
            ?: return assembled.historyTokens to true
        val lastTotal = (entry.usage?.inputTokens ?: 0) + (entry.usage?.outputTokens ?: 0)
        val rows = withContext(Dispatchers.IO) { db.messages().getAll(conversationId) }
        return realUsedTokens(lastTotal, entry.turnNo ?: 0, rows, assembled.droppedTurnIds.toSet()) to false
    }

    /** Giới hạn cửa sổ ngữ cảnh toàn cục (mặc định 100k) — hộp «Context window» đọc để hiện theo thời gian thực. */
    val contextWindowLimit: StateFlow<Int> = app.settings.contextWindowTokens

    /** User chỉnh giới hạn bằng thanh trượt trong hộp «Context window». */
    fun setContextWindowLimit(tokens: Int) {
        app.settings.setContextWindowTokens(tokens)
    }

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ChatEvent> = _events

    /** Chống chạy chồng sync history.md (init + resume cùng lúc — xem I2). */
    private val syncMutex = Mutex()

    init {
        // Đợt E: đồng bộ history.md khi mở đúng chat (không parse ngay khi mở
        // app, không đợi đến lúc gửi). Chạy nền; lỗi sync chỉ báo bằng toast,
        // Room giữ nguyên nên chat vẫn mở được.
        viewModelScope.launch { syncHistoryOnOpen() }
    }

    /**
     * Đồng bộ hai chiều history.md khi mở chat (Đợt E, xem HistorySync).
     * Đợt fix 11/11/2026 (I2): gọi lại được khi màn chat quay lại
     * foreground (ON_RESUME, xem ChatScreen) — lần chạy trùng (init +
     * resume) thì lần sau bỏ qua nhờ mutex; hash đã làm lần chạy thừa
     * thành no-op khi file không đổi. Lỗi / sửa ngoài bị bỏ vẫn báo qua
     * _events như đường init.
     */
    suspend fun syncHistoryOnOpen() {
        if (!syncMutex.tryLock()) return
        try {
            val sync = app.historySync ?: return
            when (val r = sync.syncOnOpen(conversationId) { app.engine.activeConversations.value.contains(conversationId) }) {
                is HistorySync.SyncResult.Failed ->
                    _events.emit(ChatEvent.Error("Could not sync history.md: ${r.error}"))
                HistorySync.SyncResult.ExternalEditDiscarded ->
                    _events.emit(ChatEvent.Error("Your edit to history.md while the AI was answering had no effect — the file was overwritten when the answer finished."))
                else -> Unit
            }
        } finally {
            syncMutex.unlock()
        }
    }

    var draft by mutableStateOf("")
    val pendingImages = mutableStateListOf<String>()

    // ---------- Soạn và gửi ----------

    fun addImages(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                val path = withContext(Dispatchers.IO) { ImageStore.import(app.context, uri) }
                if (path != null) pendingImages.add(path)
            }
        }
    }

    fun removePendingImage(path: String) {
        pendingImages.remove(path)
        File(path).delete()
    }

    fun send() {
        val text = draft.trim()
        val images = pendingImages.toList()
        if (text.isEmpty() && images.isEmpty()) return
        // @mention (GĐ5, 26/11/2026): a leading @Name routes the message
        // straight to that sub-agent instead of Main — decided before the
        // draft is cleared, so a refused send keeps the user's text.
        // With images the message goes to Main as today: a sub run takes
        // a text brief only. A running Main turn is left alone — the
        // @mention runs in the runner's own scope, beside it.
        if (images.isEmpty()) {
            when (val decision = decideMention(text, team.value) { agentId ->
                app.subRunner.activeRun(conversationId, agentId) != null
            }) {
                is MentionDecision.Route -> {
                    draft = ""
                    viewModelScope.launch { sendMention(decision, text) }
                    return
                }
                is MentionDecision.AgentOff -> {
                    viewModelScope.launch {
                        _events.emit(ChatEvent.Error("${decision.agent.name} is off."))
                    }
                    return
                }
                is MentionDecision.AgentBusy -> {
                    viewModelScope.launch {
                        _events.emit(ChatEvent.Error("${decision.agent.name} is busy. Ask Main instead."))
                    }
                    return
                }
                MentionDecision.NoMention -> Unit
            }
        }
        draft = ""
        pendingImages.clear()
        viewModelScope.launch {
            if (app.engine.isActive(conversationId)) {
                // One tap while a turn is running: stop it and send the new
                // message right away — do not wait for the old turn's
                // network calls to finish; their late results are discarded.
                // The stop settles the old turn (Interrupted markers, no
                // lingering spinner); wait for that settle so the new
                // request is assembled with the markers already in place.
                app.engine.stop(conversationId)
                app.engine.awaitSettled()
            }
            val turnId = UUID.randomUUID().toString()
            // Đợt 4: số lượt ổn định — lượt mới lấy (số lớn nhất hiện có + 1),
            // chỉ tăng không giảm nên số của lượt đã xóa không dùng lại.
            val turnNo = (db.messages().maxTurnNo(conversationId) ?: 0) + 1
            val seq = (db.messages().maxSeq(conversationId) ?: 0) + 1
            db.messages().upsert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    turnId = turnId,
                    turnNo = turnNo,
                    seq = seq,
                    role = "user",
                    text = text,
                    imagePaths = encodeImages(images),
                    createdAt = System.currentTimeMillis(),
                )
            )
            val conv = db.conversations().get(conversationId)
            if (conv != null && isDefaultTitle(conv.title)) {
                val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40) ?: "Image"
                db.conversations().rename(conversationId, title)
            }
            app.engine.start(conversationId, turnId)
        }
    }

    /**
     * @mention (GĐ5): write the user's row (text as typed, with the
     * @Name visible) and start the sub run directly — the same machinery
     * as a delegate, only the starter is the user. A null return is the
     * small race where the agent started another run between the check
     * in [send] and here: the message row stays, and the user is told
     * the agent is busy.
     */
    private suspend fun sendMention(route: MentionDecision.Route, text: String) {
        val turnId = UUID.randomUUID().toString()
        val turnNo = (db.messages().maxTurnNo(conversationId) ?: 0) + 1
        val seq = (db.messages().maxSeq(conversationId) ?: 0) + 1
        db.messages().upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                turnId = turnId,
                turnNo = turnNo,
                seq = seq,
                role = "user",
                text = text,
                createdAt = System.currentTimeMillis(),
            )
        )
        val title = mentionTitle(route.brief, route.agent)
        val conv = db.conversations().get(conversationId)
        if (conv != null && isDefaultTitle(conv.title)) {
            db.conversations().rename(conversationId, title)
        }
        val key = app.subRunner.startRun(
            route.agent, title, route.brief, conversationId, turnId,
            startedBy = Mention.STARTED_BY, startedByName = Mention.STARTED_BY_NAME,
        )
        if (key == null) {
            _events.emit(ChatEvent.Error("${route.agent.name} is busy. Ask Main instead."))
        }
    }

    fun stop() {
        // The engine settles the stopped turn itself (Interrupted markers,
        // no lingering spinner), in the background.
        app.engine.stop(conversationId)
    }

    // ---------- Cài đặt cuộc trò chuyện ----------

    /** Chọn mô hình: lựa chọn toàn cục duy nhất — mọi cuộc trò chuyện đều dùng mô hình này. */
    fun setModel(providerId: String, model: String) {
        app.settings.setCurrentModel(providerId, model)
    }

    /** Ghim / bỏ ghim một mô hình của nhà cung cấp (không đổi mô hình đang dùng). */
    fun togglePin(providerId: String, model: String) {
        val cfg = app.settings.provider(providerId) ?: return
        val pinned = if (model in cfg.pinnedModels) cfg.pinnedModels - model else cfg.pinnedModels + model
        app.settings.saveProvider(cfg.copy(pinnedModels = pinned))
    }

    /** Đang làm mới danh sách mô hình (hiện trong ModelPickerSheet). */
    private val _modelsRefreshing = MutableStateFlow(false)
    val modelsRefreshing: StateFlow<Boolean> = _modelsRefreshing.asStateFlow()

    /**
     * Làm mới danh sách mô hình của mọi nhà cung cấp từ máy chủ của họ rồi lưu lại,
     * để ModelPickerSheet luôn hiện danh sách mới nhất thay vì danh sách lưu từ lúc thêm provider.
     * Lỗi của một provider không chặn các provider khác và không chặn việc chọn mô hình:
     * danh sách đã lưu được giữ nguyên, chỉ hiện một thông báo lỗi.
     */
    fun refreshModels() {
        if (_modelsRefreshing.value) return
        viewModelScope.launch {
            // Bỏ qua nếu vừa làm mới thành công (trong 5 phút), trừ khi có nhà cung cấp đã có key mà chưa có mô hình nào.
            val fresh = System.currentTimeMillis() - app.modelsRefreshedAt < MODELS_TTL_MS
            val needsFill = providers.value.any { it.apiKey.isNotBlank() && it.models.isEmpty() }
            if (fresh && !needsFill) return@launch

            _modelsRefreshing.value = true
            try {
                val failed = mutableListOf<String>()
                for (cfg in providers.value) {
                    // Chưa có key hoặc địa chỉ máy chủ thì không lấy được gì: bỏ qua, không báo lỗi.
                    if (cfg.apiKey.isBlank() || cfg.effectiveBaseUrl().isBlank()) continue
                    try {
                        val models = ProviderFactory.create(cfg).listModels()
                        // Chỉ ghi vào kho khi danh sách thực sự khác.
                        if (models != cfg.models) app.settings.saveProvider(cfg.copy(models = models))
                    } catch (e: Exception) {
                        failed += cfg.name
                    }
                }
                if (failed.isNotEmpty()) {
                    _events.emit(ChatEvent.Error("Couldn't fetch the model list for ${failed.joinToString(", ")}. Showing the saved list."))
                } else {
                    app.modelsRefreshedAt = System.currentTimeMillis()
                }
            } finally {
                _modelsRefreshing.value = false
            }
        }
    }

    fun rename(title: String) {
        viewModelScope.launch { app.conversations.rename(conversationId, title) }
    }

    // ---------- Menu tin nhắn ----------

    /** Edit message: chỉ lưu nội dung đã sửa, từ lần gửi sau dùng nội dung đó. */
    fun saveEdit(messageId: String, text: String) {
        if (app.engine.isActive(conversationId)) return
        viewModelScope.launch {
            app.ops.editMessage(messageId, text)
            app.engine.exportHistory(conversationId)
        }
    }

    /** Delete: xóa cả lượt (cặp hỏi-đáp), có thể Hoàn tác. */
    fun deleteTurn(turnId: String) {
        if (app.engine.isActive(conversationId)) return
        viewModelScope.launch {
            val backup = app.ops.deleteTurn(conversationId, turnId)
            app.engine.exportHistory(conversationId)
            _events.emit(ChatEvent.Deleted(backup))
        }
    }

    fun undoDelete(backup: List<MessageEntity>) {
        viewModelScope.launch {
            app.ops.restore(backup)
            app.engine.exportHistory(conversationId)
        }
    }

    suspend fun laterTurnCount(turnId: String): Int = app.ops.laterTurnCount(conversationId, turnId)

    /** Tạo lại ngay trong cuộc hiện tại (các lượt phía sau, nếu có, bị xóa). */
    fun regenerateHere(turnId: String, newUserText: String?) {
        if (app.engine.isActive(conversationId)) return
        viewModelScope.launch {
            // Đợt E: xung đột khi regenerate — file đổi ngoài app thì giữ bản
            // sửa ngoài, hủy việc tạo lại, báo lỗi.
            if (app.historySync?.hasExternalChange(conversationId) == true) {
                _events.emit(ChatEvent.Error("History changed outside the app. The external edit was kept; the regeneration was cancelled."))
                return@launch
            }
            app.ops.prepareRegenerateHere(conversationId, turnId, newUserText)
            // Fork history: bản cũ giữ trong file cũ, bản tạo lại sang file
            // mới (history_(NN).md). Best-effort — lỗi thì vẫn tạo lại như cũ.
            app.historySync?.forkHistory(conversationId, turnId)
            app.engine.start(conversationId, turnId)
        }
    }

    /** Tạo lại ở cuộc mới; cuộc gốc giữ nguyên. */
    fun regenerateInBranch(turnId: String, newUserText: String?) {
        if (app.engine.isActive(conversationId)) return
        viewModelScope.launch {
            // Đợt E: xung đột khi regenerate — như regenerateHere.
            if (app.historySync?.hasExternalChange(conversationId) == true) {
                _events.emit(ChatEvent.Error("History changed outside the app. The external edit was kept; the regeneration was cancelled."))
                return@launch
            }
            try {
                val r = app.ops.branchForRegenerate(conversationId, turnId, newUserText)
                app.engine.start(r.conversationId, r.turnMap.getValue(turnId))
                _events.emit(ChatEvent.OpenBranch(r.conversationId))
            } catch (e: Exception) {
                _events.emit(ChatEvent.Error(e.message ?: "Couldn't create the branch."))
            }
        }
    }

    /** Các lượt đã Retry một lần mà vẫn lỗi (chỉ nhớ trong phiên này): bong bóng lỗi của lượt đó sẽ thêm nút "Start a new chat". */
    private val _retriedTurns = MutableStateFlow<Set<String>>(emptySet())
    val retriedTurns: StateFlow<Set<String>> = _retriedTurns.asStateFlow()

    /**
     * Retry một lượt đã lỗi (nút Retry trong bong bóng lỗi, đợt bỏ provider
     * 12/11/2026; sửa theo ý Duy 12/11/2026): mặc định gửi lại request ĐẦY
     * ĐỦ ([RepairMode.NONE]) — dựng lại từ cài đặt hiện tại + reasoning đã
     * lưu, để user sửa cài đặt xong (vd bật lại "Keep thought signature")
     * rồi ấn Retry là gửi lại đúng phần hợp lệ. KHÔNG tự BỚT gì ở lần
     * Retry đầu. Nút Retry đã BỚT (chỉ khi dữ liệu đã mất, xem
     * [ApiErrorHelp]) gọi hàm này với repair tương ứng. Retry xong mà vẫn
     * lỗi thì lượt được ghi nhớ để bong bóng lỗi thêm nút mở cuộc mới.
     */
    fun retryTurn(turnId: String, repair: RepairMode = RepairMode.NONE) {
        if (app.engine.isActive(conversationId)) return
        viewModelScope.launch {
            // Lượt lỗi mà còn lượt mới hơn phía sau thì không Retry thẳng
            // (sẽ xóa các lượt đó): bảo user dùng Regenerate trong menu —
            // đường đó hỏi trước khi xóa.
            if (laterTurnCount(turnId) > 0) {
                _events.emit(ChatEvent.Error("This turn has newer turns after it. Use Regenerate from the message menu instead — it asks before deleting them."))
                return@launch
            }
            _retriedTurns.update { it + turnId }
            // Đợt E: xung đột khi regenerate — như regenerateHere.
            if (app.historySync?.hasExternalChange(conversationId) == true) {
                _events.emit(ChatEvent.Error("History changed outside the app. The external edit was kept; the regeneration was cancelled."))
                return@launch
            }
            app.ops.prepareRegenerateHere(conversationId, turnId, null)
            // Retry cũng là tạo lại tại chỗ: fork như regenerate để giữ bản lỗi cũ.
            app.historySync?.forkHistory(conversationId, turnId)
            app.engine.start(conversationId, turnId, repair)
        }
    }

    /** Branch: tạo cuộc mới, sao chép từ đầu đến tin nhắn được chọn. */
    fun branchAt(messageId: String) {
        if (app.engine.isActive(conversationId)) return
        viewModelScope.launch {
            try {
                val r = app.ops.branchAt(conversationId, messageId)
                _events.emit(ChatEvent.OpenBranch(r.conversationId))
            } catch (e: Exception) {
                _events.emit(ChatEvent.Error(e.message ?: "Couldn't create the branch."))
            }
        }
    }
}
