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

    /**
     * Đợt 8: chèn lại một thẻ đã xóa ở đúng vị trí cũ (cho Undo sau khi vuốt
     * xóa). Không làm gì nếu thẻ đã có lại trong bố cục (tránh trùng khi
     * Undo bấm hai lần).
     */
    fun insertCard(card: RequestCard, index: Int) {
        update { layout ->
            if (layout.cards.any { it.id == card.id }) return@update layout
            val cards = layout.cards.toMutableList()
            cards.add(index.coerceIn(0, cards.size), card)
            layout.copy(cards = cards)
        }
    }

    /**
     * Thêm một thẻ mới (menu "Add card", theo FPilot): System / Time / Tools
     * chỉ thêm được khi chưa có thẻ đó (luật kiểm tra: mỗi loại tối đa 1 thẻ);
     * Free thì thêm bao nhiêu cũng được, vai mặc định System (đổi được ở màn
     * sửa, vai User/System). Thẻ mới chèn trước thẻ History — như FPilot
     * (thẻ mới nằm trên History; History vẫn trên Last turn) — nếu không có
     * History thì chèn trước Last turn, nếu cũng không có thì thêm vào cuối.
     * Trả về id để mở thẳng màn sửa. Nhờ vậy thẻ đã xóa (System/Time/Tools)
     * thêm lại được từ menu này, không cần Reset toàn bộ panel.
     */
    fun addCard(kind: CardKind): String {
        // System/Time/Tools mỗi loại tối đa 1 thẻ — đã có thì trả về id của
        // thẻ đó (mở màn sửa của nó) thay vì tạo thẻ trùng rồi trả về id ảo.
        if (kind == CardKind.SYSTEM || kind == CardKind.TIME || kind == CardKind.TOOLS) {
            _stored.value.cards.firstOrNull { it.kind == kind }?.let { return it.id }
        }
        val id = UUID.randomUUID().toString()
        update { layout ->
            val cards = layout.cards.toMutableList()
            val card = when (kind) {
                // Thẻ System thêm lại lấy system prompt mặc định (như bố cục
                // mặc định); nội dung cũ của thẻ đã xóa thì không lấy lại được.
                CardKind.SYSTEM -> RequestCard(id = id, kind = kind, text = currentSystemText(layout))
                CardKind.TIME -> RequestCard(id = id, kind = kind)
                CardKind.TOOLS -> RequestCard(id = id, kind = kind)
                else -> RequestCard(id = id, kind = CardKind.FREE, label = "Free card")
            }
            val historyIndex = cards.indexOfFirst { it.kind == CardKind.HISTORY }
            val lastTurnIndex = cards.indexOfFirst { it.kind == CardKind.LAST_TURN }
            val at = when {
                historyIndex >= 0 -> historyIndex
                lastTurnIndex >= 0 -> lastTurnIndex
                else -> cards.size
            }
            cards.add(at, card)
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
