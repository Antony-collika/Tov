package com.example.byokchat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Lưu bố cục Request panel (Đợt C) dưới dạng JSON trong SharedPreferences,
 * không đụng Room. Bố cục áp dụng toàn cục cho mọi cuộc trò chuyện.
 *
 * - [storedLayout]: đúng thứ user đã lưu (kể cả khi không hợp lệ, để Panel
 *   hiện lại và báo lỗi).
 * - [effectiveLayout]: bố cục thật sự dùng để gửi — stored nếu hợp lệ, bố cục
 *   mặc định nếu không, nên chat không bao giờ chết vì bố cục hỏng.
 * - [layoutError]: lỗi kiểm tra đầu tiên (null khi hợp lệ).
 */
class RequestLayoutStore(context: Context, private val settings: SettingsRepository) {
    private val plain = context.applicationContext.getSharedPreferences("request_layout", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _stored = MutableStateFlow(load())
    val storedLayout: StateFlow<RequestLayout> = _stored.asStateFlow()

    private val _effective = MutableStateFlow(effectiveOf(_stored.value))
    val effectiveLayout: StateFlow<RequestLayout> = _effective.asStateFlow()

    private val _layoutError = MutableStateFlow<String?>(errorOf(_stored.value))
    val layoutError: StateFlow<String?> = _layoutError.asStateFlow()

    /** Lỗi đọc file của thẻ System/Free ở lần gửi/xem trước gần nhất (Đợt D). */
    private val _fileErrors = MutableStateFlow<List<String>>(emptyList())
    val fileErrors: StateFlow<List<String>> = _fileErrors.asStateFlow()

    fun setFileErrors(errors: List<String>) {
        _fileErrors.value = errors
    }

    private fun effectiveOf(layout: RequestLayout): RequestLayout {
        if (layout.isValid()) return layout
        return RequestLayout.defaultLayout(currentSystemText(layout))
    }

    private fun errorOf(layout: RequestLayout): String? = layout.validate().firstOrNull()

    private fun currentSystemText(layout: RequestLayout): String =
        layout.systemCard()?.text ?: settings.defaultSystemPrompt.value

    /** Ghi đè toàn bộ bố cục (kể cả khi không hợp lệ — lỗi hiện ở Panel, chat dùng mặc định). */
    fun set(layout: RequestLayout) {
        _stored.value = layout
        _effective.value = effectiveOf(layout)
        _layoutError.value = errorOf(layout)
        plain.edit().putString("layout_json", json.encodeToString(RequestLayout.serializer(), layout)).apply()
    }

    fun update(transform: (RequestLayout) -> RequestLayout) = set(transform(_stored.value))

    fun updateCard(id: String, change: (RequestCard) -> RequestCard) {
        update { layout -> layout.copy(cards = layout.cards.map { if (it.id == id) change(it) else it }) }
    }

    fun moveCard(id: String, up: Boolean) {
        update { layout ->
            val cards = layout.cards.toMutableList()
            val i = cards.indexOfFirst { it.id == id }
            val j = if (up) i - 1 else i + 1
            if (i < 0 || j < 0 || j >= cards.size) layout
            else {
                val tmp = cards[i]; cards[i] = cards[j]; cards[j] = tmp
                layout.copy(cards = cards)
            }
        }
    }

    fun deleteCard(id: String) {
        update { layout -> layout.copy(cards = layout.cards.filter { it.id != id }) }
    }

    /** Thêm một thẻ Free mới, trả về id để mở thẳng màn sửa. */
    fun addFreeCard(): String {
        val id = UUID.randomUUID().toString()
        update { layout ->
            val cards = layout.cards.toMutableList()
            val free = RequestCard(id = id, kind = CardKind.FREE, label = "Free card")
            val lastTurnIndex = cards.indexOfFirst { it.kind == CardKind.LAST_TURN }
            if (lastTurnIndex >= 0) cards.add(lastTurnIndex, free) else cards.add(free)
            layout.copy(cards = cards)
        }
        return id
    }

    /** Về bố cục mặc định, giữ lại văn bản System hiện tại. */
    fun reset() {
        set(RequestLayout.defaultLayout(currentSystemText(_stored.value)))
    }

    private fun load(): RequestLayout {
        val raw = plain.getString("layout_json", null)
            ?: return RequestLayout.layoutFromSettings(settings.defaultSystemPrompt.value)
        val decoded = runCatching { json.decodeFromString(RequestLayout.serializer(), raw) }
            .getOrDefault(RequestLayout.layoutFromSettings(settings.defaultSystemPrompt.value))
        // Đợt 6: bố cục lưu từ trước chưa có thẻ Metadata — thêm vào đầu,
        // giữ nguyên mọi thẻ còn lại (kể cả thứ tự user đã sắp), rồi lưu lại
        // để lần sau không migrate nữa. Bố cục nào Metadata đã có nhưng
        // không ở đầu thì KHÔNG tự sửa: validate() báo lỗi và chat dùng mặc
        // định như các lỗi bố cục khác.
        if (decoded.cards.none { it.kind == CardKind.METADATA }) {
            val migrated = decoded.copy(
                cards = listOf(RequestCard(RequestLayout.METADATA_ID, CardKind.METADATA)) + decoded.cards
            )
            plain.edit().putString("layout_json", json.encodeToString(RequestLayout.serializer(), migrated)).apply()
            return migrated
        }
        return decoded
    }
}
