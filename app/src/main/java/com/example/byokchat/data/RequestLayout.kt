package com.example.byokchat.data

import com.example.byokchat.ai.ToolDef
import com.example.byokchat.chat.SystemPart
import com.example.byokchat.chat.SystemSource
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Loại thẻ trong Request panel (Đợt C). Thứ tự thẻ trong danh sách là thứ tự gửi. */
enum class CardKind { SYSTEM, TIME, FREE, HISTORY, LAST_TURN, TOOLS }

/** Vai của thẻ Free khi gửi. */
enum class FreeRole { SYSTEM, USER }

/** Chính sách cắt lịch sử của thẻ History. */
enum class HistoryPolicy { BUDGET, LAST_N, NO_CUT }

/**
 * Một thẻ trong Request panel. Các thẻ cố định (History, Last turn) luôn có
 * đúng 1 thẻ và không tắt được; thẻ khác có thể tắt, di chuyển, xóa.
 */
@Serializable
data class RequestCard(
    val id: String,
    val kind: CardKind,
    val enabled: Boolean = true,
    /** Tên hiển thị (thẻ Free do user đặt; thẻ khác lấy tên mặc định theo loại). */
    val label: String = "",
    /** Thẻ System/Free: văn bản. */
    val text: String = "",
    /** Thẻ Free: gửi dưới vai nào. */
    val role: FreeRole = FreeRole.SYSTEM,
    /** Thẻ History: chính sách cắt. */
    val policy: HistoryPolicy = HistoryPolicy.BUDGET,
    /** Thẻ History, policy LAST_N: giữ N lượt gần nhất. */
    val lastTurns: Int = 10,
    /** Thẻ History: rút gọn kết quả tool cũ dài hơn 500 ký tự thành một dòng. */
    val compactToolResults: Boolean = false,
    /** Thẻ History: gắn thẻ lượt [T7] vào tin user. */
    val turnTags: Boolean = false,
    /** Thẻ History: có gửi lượt lỗi hay không. */
    val includeErrorTurns: Boolean = false,
    /**
     * Thẻ System/Free: đường dẫn file .md trong workspace (vd
     * "Tov/prompts/system.md"). Khi có file, nội dung file được đọc lại MỖI
     * LẦN GỬI và thay văn bản trong thẻ; đọc lỗi thì giữ văn bản cũ và báo
     * lỗi (xem RequestLayout.resolveFiles). Null = chỉ dùng văn bản trong thẻ.
     */
    val file: String? = null,
) {
    /** Tên hiển thị trên Panel. */
    fun displayName(): String = when (kind) {
        CardKind.SYSTEM -> "System"
        CardKind.TIME -> "Time"
        CardKind.FREE -> label.ifBlank { "Free card" }
        CardKind.HISTORY -> "History"
        CardKind.LAST_TURN -> "Last turn"
        CardKind.TOOLS -> "Tools"
    }

    /** Thẻ cố định: không xóa được, luôn đúng 1 thẻ, không tắt được. */
    fun isFixed(): Boolean = kind == CardKind.HISTORY || kind == CardKind.LAST_TURN
}

/**
 * Bố cục request: danh sách thẻ từ trên xuống, áp dụng toàn cục cho mọi cuộc.
 * Bố cục mặc định tái tạo y hệt request trước Đợt C (xem layoutFromSettings).
 */
@Serializable
data class RequestLayout(val cards: List<RequestCard>) {

    companion object {
        const val SYSTEM_ID = "system"
        const val TIME_ID = "time"
        const val TOOLS_ID = "tools"
        const val HISTORY_ID = "history"
        const val LAST_TURN_ID = "last-turn"

        /** UID của công cụ giờ (DailyTools): thẻ Time điều khiển việc offer nó. */
        const val TIME_NOW_UID = "time.now"

        /**
         * Biến {date}, {time}, {model}, {provider} cho lần gửi này.
         * Chỉ ngày + giờ (không múi giờ, không vị trí), theo giờ máy.
         */
        fun requestVars(model: String, provider: String): Map<String, String> {
            val now = LocalDateTime.now()
            return mapOf(
                "date" to now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                "time" to now.format(DateTimeFormatter.ofPattern("HH:mm")),
                "model" to model,
                "provider" to provider,
            )
        }

        /** Bố cục mặc định: đúng thứ mà app vẫn gửi trước Đợt C. */
        fun defaultLayout(systemPrompt: String): RequestLayout = RequestLayout(
            listOf(
                RequestCard(SYSTEM_ID, CardKind.SYSTEM, text = systemPrompt),
                RequestCard(TIME_ID, CardKind.TIME),
                RequestCard(TOOLS_ID, CardKind.TOOLS),
                RequestCard(HISTORY_ID, CardKind.HISTORY),
                RequestCard(LAST_TURN_ID, CardKind.LAST_TURN),
            )
        )

        /**
         * Tái tạo y hệt request hiện tại từ Settings (lần đầu mở Panel, hoặc
         * khi bố cục đã lưu hỏng): system prompt mặc định thành thẻ System.
         */
        fun layoutFromSettings(systemPrompt: String): RequestLayout = defaultLayout(systemPrompt)
    }

    fun card(id: String): RequestCard? = cards.firstOrNull { it.id == id }
    fun systemCard(): RequestCard? = cards.firstOrNull { it.kind == CardKind.SYSTEM }
    fun timeCard(): RequestCard? = cards.firstOrNull { it.kind == CardKind.TIME }
    fun toolsCard(): RequestCard? = cards.firstOrNull { it.kind == CardKind.TOOLS }
    fun historyCard(): RequestCard? = cards.firstOrNull { it.kind == CardKind.HISTORY }
    fun lastTurnCard(): RequestCard? = cards.firstOrNull { it.kind == CardKind.LAST_TURN }

    /**
     * Luật kiểm tra. Trả về danh sách lỗi (rỗng = hợp lệ); mỗi lỗi nêu tên thẻ.
     * Bố cục không hợp lệ thì chat vẫn chạy bằng bố cục mặc định (xem
     * RequestLayoutStore.effectiveLayout).
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val history = cards.filter { it.kind == CardKind.HISTORY }
        val lastTurn = cards.filter { it.kind == CardKind.LAST_TURN }
        if (history.size != 1) errors += "The History card must appear exactly once (found ${history.size})."
        if (lastTurn.size != 1) errors += "The Last turn card must appear exactly once (found ${lastTurn.size})."
        if (history.size == 1 && lastTurn.size == 1) {
            if (cards.indexOf(history[0]) > cards.indexOf(lastTurn[0])) {
                errors += "The History card must be above the Last turn card."
            }
        }
        if (history.size == 1 && !history[0].enabled) errors += "The History card cannot be turned off."
        if (lastTurn.size == 1 && !lastTurn[0].enabled) errors += "The Last turn card cannot be turned off."
        if (cards.count { it.kind == CardKind.SYSTEM } > 1) errors += "The System card must appear at most once."
        if (cards.count { it.kind == CardKind.TIME } > 1) errors += "The Time card must appear at most once."
        if (cards.count { it.kind == CardKind.TOOLS } > 1) errors += "The Tools card must appear at most once."
        cards.filter { it.kind == CardKind.FREE && it.text.isBlank() && it.file.isNullOrBlank() }.forEach {
            errors += "The Free card \"${it.displayName()}\" is empty — write something, link a file, or delete it."
        }
        cards.filter { (it.kind == CardKind.SYSTEM || it.kind == CardKind.FREE) && it.file != null && !it.file.endsWith(".md") }.forEach {
            errors += "The ${it.displayName()} card links to '${it.file}' — the file must end with .md."
        }
        return errors
    }

    fun isValid(): Boolean = validate().isEmpty()

    /** Thay biến {date}, {time}, {model}, {provider} trong văn bản thẻ. */
    fun substitute(text: String, vars: Map<String, String>): String {
        var out = text
        vars.forEach { (k, v) -> out = out.replace("{$k}", v) }
        return out
    }

    /** Các phần system prompt, theo thứ tự thẻ (System + Free vai system). */
    fun systemParts(vars: Map<String, String>): List<SystemPart> {
        val parts = mutableListOf<SystemPart>()
        for (card in cards) {
            if (!card.enabled) continue
            when (card.kind) {
                CardKind.SYSTEM ->
                    if (card.text.isNotBlank()) parts += SystemPart(substitute(card.text, vars), SystemSource.USER_PROMPT)
                CardKind.FREE ->
                    if (card.role == FreeRole.SYSTEM && card.text.isNotBlank()) {
                        parts += SystemPart(substitute(card.text, vars), SystemSource.USER_PROMPT)
                    }
                else -> { /* Time/Tools/History/Last turn không phải system */ }
            }
        }
        return parts
    }

    fun systemText(vars: Map<String, String>): String = systemParts(vars).joinToString("\n\n") { it.text }

    /**
     * Đợt D: thay văn bản của thẻ System/Free trỏ file bằng nội dung file
     * (đọc lại mỗi lần gửi). Trả về bố cục đã thay + danh sách lỗi; thẻ nào
     * đọc lỗi thì giữ văn bản cũ trong thẻ để chat vẫn chạy, lỗi hiện ở
     * Panel/Inspector (xem RequestLayoutStore.fileErrors). Không ghi lại
     * vào bộ nhớ: văn bản trong thẻ là bản gõ tay gần nhất, file là nguồn
     * mới mỗi lần gửi.
     */
    fun resolveFiles(readFile: (String) -> Result<String>): Pair<RequestLayout, List<String>> {
        val errors = mutableListOf<String>()
        val cards = this.cards.map { card ->
            val file = card.file
            if ((card.kind == CardKind.SYSTEM || card.kind == CardKind.FREE) && !file.isNullOrBlank()) {
                readFile(file).fold(
                    onSuccess = { content -> card.copy(text = content) },
                    onFailure = { e ->
                        errors += "Could not read the ${card.displayName()} card from '$file': ${e.message}"
                        card
                    },
                )
            } else card
        }
        return copy(cards = cards) to errors
    }

    /**
     * Lọc danh sách công cụ đã resolve theo thẻ Time/Tools.
     * - Không có thẻ Tools (hoặc thẻ đang bật): giữ nguyên. Thẻ Tools tắt = không offer công cụ nào.
     * - Không có thẻ Time (hoặc thẻ đang bật): giữ nguyên. Thẻ Time tắt = không offer time.now
     *   (nhận ra bằng tên hiệu dụng, vì definitions() dùng cùng tên đó).
     * Vị trí thẻ Time/Tools không ảnh hưởng gì: công cụ không phải tin nhắn, mà đi ở trường riêng.
     */
    fun effectiveToolDefs(defs: List<ToolDef>, timeToolName: String?): List<ToolDef> {
        if (toolsCard()?.enabled == false) return emptyList()
        if (timeCard()?.enabled != false || timeToolName == null) return defs
        return defs.filter { it.name != timeToolName }
    }
}
