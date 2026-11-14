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
import com.example.byokchat.chat.HistorySync
import com.example.byokchat.chat.RepairMode
import com.example.byokchat.chat.RequestAssembler
import com.example.byokchat.chat.TokenEstimator
import com.example.byokchat.data.ContextLimits
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

data class TokenStats(val used: Int, val limit: Int, val droppedTurns: Int, val droppedTurnIds: List<String> = emptyList())

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
    /** File tools.md của thẻ Tools đọc/parse lỗi (xem fileErrors để biết chi tiết). */
    TOOLS_FILE_ERROR,
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
        // Đợt E phần A: file tools.md của thẻ Tools cũng đọc + parse lại mỗi lần xem trước.
        val toolsResolution = withContext(Dispatchers.IO) {
            layout.resolveToolFiles(
                { path -> runCatching { app.fs.readCardFile(path) } },
                app.registry.originDescriptions(),
            )
        }
        app.layoutStore.setFileErrors(fileErrors + toolsResolution.errors)
        if (toolsResolution.errors.isNotEmpty()) {
            return@withContext PreviewResult.Unavailable(PreviewUnavailable.TOOLS_FILE_ERROR)
        }
        val exposed = toolsResolution.exposed
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
     * Đếm token ước lượng của phần ngữ cảnh sẽ gửi đi. Chỉ gọi khi người dùng mở hộp «Context window»
     * (trước đây chạy liên tục nên mỗi lần AI viết thêm chữ lại duyệt cả lịch sử).
     */
    suspend fun computeTokenStats(): TokenStats? {
        val preview = previewAssembled()
        if (preview !is PreviewResult.Ready) return null
        val assembled = preview.request
        val cfg = providers.value.firstOrNull { it.id == currentModelProviderId.value } ?: return null
        return TokenStats(
            assembled.historyTokens,
            ContextLimits.forModel(cfg, currentModel.value.orEmpty()),
            assembled.droppedTurnIds.size,
            assembled.droppedTurnIds,
        )
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
        draft = ""
        pendingImages.clear()
        viewModelScope.launch {
            if (generating.value) {
                // One tap while a turn is running: stop it (the running turn
                // is marked Interrupted) and send the new message right
                // away — do not wait for the old turn's network calls to
                // finish; their late results are discarded.
                app.engine.stop(conversationId)
                app.engine.markCurrentTurnInterrupted(conversationId)
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

    fun stop() = app.engine.stop(conversationId)

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
        if (generating.value) return
        viewModelScope.launch {
            app.ops.editMessage(messageId, text)
            app.engine.exportHistory(conversationId)
        }
    }

    /** Delete: xóa cả lượt (cặp hỏi-đáp), có thể Hoàn tác. */
    fun deleteTurn(turnId: String) {
        if (generating.value) return
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
        if (generating.value) return
        viewModelScope.launch {
            // Đợt E: xung đột khi regenerate — file đổi ngoài app thì giữ bản
            // sửa ngoài, hủy việc tạo lại, báo lỗi.
            if (app.historySync?.hasExternalChange(conversationId) == true) {
                _events.emit(ChatEvent.Error("History changed outside the app. The external edit was kept; the regeneration was cancelled."))
                return@launch
            }
            app.ops.prepareRegenerateHere(conversationId, turnId, newUserText)
            app.engine.start(conversationId, turnId)
        }
    }

    /** Tạo lại ở cuộc mới; cuộc gốc giữ nguyên. */
    fun regenerateInBranch(turnId: String, newUserText: String?) {
        if (generating.value) return
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
        if (generating.value) return
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
            app.engine.start(conversationId, turnId, repair)
        }
    }

    /** Branch: tạo cuộc mới, sao chép từ đầu đến tin nhắn được chọn. */
    fun branchAt(messageId: String) {
        if (generating.value) return
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
