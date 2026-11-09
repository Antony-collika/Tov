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
import com.example.byokchat.ai.ProviderFactory
import com.example.byokchat.chat.ContextBuilder
import com.example.byokchat.data.ContextLimits
import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.isDefaultTitle
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.ProviderConfig
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class TokenStats(val used: Int, val limit: Int, val droppedTurns: Int)

/** Input gộp cho tokenStats: thêm system prompt mặc định để đổi prompt trong Settings thì số liệu cập nhật theo, và lựa chọn mô hình toàn cục để đổi model ở chat nào thì số liệu cũng cập nhật theo. */
private data class StatsInput(
    val conversation: ConversationEntity?,
    val messages: List<MessageEntity>,
    val providers: List<ProviderConfig>,
    val systemPrompt: String,
    val currentProviderId: String? = null,
    val currentModel: String? = null,
)

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

    /** Đếm token ước lượng của phần ngữ cảnh sẽ gửi đi (luôn theo nội dung đã sửa). */
    val tokenStats: StateFlow<TokenStats?> =
        combine(conversation, messages, providers, app.settings.defaultSystemPrompt) { c, m, p, prompt ->
            StatsInput(c, m, p, prompt)
        }
            // Lựa chọn mô hình toàn cục gộp sau bằng combine 2 luồng (combine 6 luồng không biên dịch được ở đây):
            // đổi model ở chat nào thì số liệu token của mọi chat đang mở cũng cập nhật theo.
            .combine(app.settings.currentModelProviderId) { input, providerId -> input.copy(currentProviderId = providerId) }
            .combine(app.settings.currentModel) { input, model -> input.copy(currentModel = model) }
            .debounce(400)
            .map { (c, m, p, prompt, providerId, model) ->
                val cfg = p.firstOrNull { it.id == providerId }
                if (c == null || cfg == null) null
                else {
                    val modelName = model.orEmpty()
                    val budget = ContextBuilder.budget(cfg, modelName, prompt, app.registry.estimateToolTokens())
                    val plan = ContextBuilder.plan(m, budget, app.registry::nameForUid)
                    TokenStats(plan.usedTokens, ContextLimits.forModel(cfg, modelName), plan.droppedTurns)
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ChatEvent> = _events

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
        if ((text.isEmpty() && images.isEmpty()) || generating.value) return
        draft = ""
        pendingImages.clear()
        viewModelScope.launch {
            val turnId = UUID.randomUUID().toString()
            val seq = (db.messages().maxSeq(conversationId) ?: 0) + 1
            db.messages().upsert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    turnId = turnId,
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
            _modelsRefreshing.value = true
            try {
                val failed = mutableListOf<String>()
                for (cfg in providers.value) {
                    // Chưa có key hoặc địa chỉ máy chủ thì không lấy được gì: bỏ qua, không báo lỗi.
                    if (cfg.apiKey.isBlank() || cfg.effectiveBaseUrl().isBlank()) continue
                    try {
                        val models = ProviderFactory.create(cfg).listModels()
                        app.settings.saveProvider(cfg.copy(models = models))
                    } catch (e: Exception) {
                        failed += cfg.name
                    }
                }
                if (failed.isNotEmpty()) {
                    _events.emit(ChatEvent.Error("Couldn't fetch the model list for ${failed.joinToString(", ")}. Showing the saved list."))
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
        viewModelScope.launch { app.ops.editMessage(messageId, text) }
    }

    /** Delete: xóa cả lượt (cặp hỏi-đáp), có thể Hoàn tác. */
    fun deleteTurn(turnId: String) {
        if (generating.value) return
        viewModelScope.launch {
            val backup = app.ops.deleteTurn(conversationId, turnId)
            _events.emit(ChatEvent.Deleted(backup))
        }
    }

    fun undoDelete(backup: List<MessageEntity>) {
        viewModelScope.launch { app.ops.restore(backup) }
    }

    suspend fun laterTurnCount(turnId: String): Int = app.ops.laterTurnCount(conversationId, turnId)

    /** Tạo lại ngay trong cuộc hiện tại (các lượt phía sau, nếu có, bị xóa). */
    fun regenerateHere(turnId: String, newUserText: String?) {
        if (generating.value) return
        viewModelScope.launch {
            app.ops.prepareRegenerateHere(conversationId, turnId, newUserText)
            app.engine.start(conversationId, turnId)
        }
    }

    /** Tạo lại ở cuộc mới; cuộc gốc giữ nguyên. */
    fun regenerateInBranch(turnId: String, newUserText: String?) {
        if (generating.value) return
        viewModelScope.launch {
            try {
                val r = app.ops.branchForRegenerate(conversationId, turnId, newUserText)
                app.engine.start(r.conversationId, r.turnMap.getValue(turnId))
                _events.emit(ChatEvent.OpenBranch(r.conversationId))
            } catch (e: Exception) {
                _events.emit(ChatEvent.Error(e.message ?: "Couldn't create the branch."))
            }
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
