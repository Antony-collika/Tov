package com.example.byokchat.data

import com.example.byokchat.ai.ToolDef
import com.example.byokchat.chat.SystemPart
import com.example.byokchat.chat.SystemSource
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Loại thẻ trong Request panel (Đợt C). Thứ tự thẻ trong danh sách là thứ tự gửi. */
enum class CardKind { METADATA, SYSTEM, TIME, FREE, HISTORY, LAST_TURN, TOOLS }

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
     * Thẻ System/Free: file trỏ tới — URI "content://..." của file ngoài
     * (chọn ở bất cứ đâu trên máy, đợt fix 11/11/2026, I1) hoặc đường dẫn
     * tương đối trong AI playground (vd "folder_01/prompts/system.md", thẻ cũ). Khi
     * có file, nội dung file được đọc lại MỖI LẦN GỬI và thay văn bản trong
     * thẻ; đọc lỗi thì giữ văn bản cũ và báo lỗi (xem
     * RequestLayout.resolveFiles). Null = chỉ dùng văn bản trong thẻ.
     */
    val file: String? = null,
    /**
     * Thẻ Tools (RCP, 17/11/2026): UID các tool đã chọn trong app. Chỉ
     * tool trong danh sách này mới được offer cho model, theo nhóm + thứ
     * tự registry mặc định (không theo thứ tự user tích — thứ tự tính lúc
     * gửi, xem definitionsForUids). Null = chưa có lựa chọn nào lưu — coi
     * như chọn hết (xem [effectiveSelectedToolUids]); danh sách rỗng = đã
     * lưu lựa chọn rỗng (bỏ hết tick rồi Save) — 0 tools, tương đương tắt
     * thẻ. Hai trường hợp này khác nhau, đừng gộp (sửa 17/11/2026).
     */
    val selectedToolUids: List<String>? = null,
) {
    /**
     * UID thật sự được offer cho model (17/11/2026 — bỏ hẳn file
     * tools.md ngoài): chưa có lựa chọn nào lưu (null — thẻ mới, thẻ cũ
     * từ trước khi có lựa chọn, thẻ thêm lại) thì mặc định chọn hết —
     * [allUids] theo thứ tự registry; đã lưu lựa chọn (kể cả rỗng) thì
     * dùng đúng danh sách đó — rỗng nghĩa là 0 tools. Người dùng chỉ bỏ
     * tick những cái không muốn; tắt thẻ thì không offer tool nào.
     */
    fun effectiveSelectedToolUids(allUids: List<String>): List<String> =
        selectedToolUids ?: allUids
    /** Tên hiển thị trên Panel. Đợt 8: thẻ System cũng dùng label khi user đặt tên (màn sửa có ô Name); mặc định vẫn là "System". */
    fun displayName(): String = when (kind) {
        CardKind.METADATA -> "Metadata"
        CardKind.SYSTEM -> label.ifBlank { "System" }
        CardKind.TIME -> "Time"
        CardKind.FREE -> label.ifBlank { "Free card" }
        CardKind.HISTORY -> "History"
        CardKind.LAST_TURN -> "Last turn"
        CardKind.TOOLS -> "Tools"
    }

    /**
     * Thẻ cố định: không xóa được, luôn đúng 1 thẻ, không tắt được.
     * Metadata cố định như FPilot (luôn đầu tiên) — model/effort/max tokens
     * là thứ mọi request đều có, không có trạng thái "tắt metadata".
     */
    fun isFixed(): Boolean =
        kind == CardKind.METADATA || kind == CardKind.HISTORY || kind == CardKind.LAST_TURN
}

/**
 * Bố cục request: danh sách thẻ từ trên xuống, áp dụng toàn cục cho mọi cuộc.
 * Bố cục mặc định tái tạo y hệt request trước Đợt C (xem layoutFromSettings).
 */
@Serializable
data class RequestLayout(val cards: List<RequestCard>) {

    companion object {
        const val METADATA_ID = "metadata"
        const val SYSTEM_ID = "system"
        const val TIME_ID = "time"
        const val TOOLS_ID = "tools"
        const val HISTORY_ID = "history"
        const val LAST_TURN_ID = "last-turn"

        /** UID của công cụ giờ (DailyTools). time.now luôn được offer như một
         * tool bình thường; thẻ Time chỉ điều khiển việc chèn dòng giờ vào
         * cuối system (xem systemParts), không lọc tool nữa. */
        const val TIME_NOW_UID = "time.now"

        /**
         * Dòng giờ chèn vào cuối system khi thẻ Time BẬT (Đợt 2, quyết định 1):
         * chỉ ngày + giờ, không múi giờ, không vị trí, không giây, không weekday.
         * Lấy từ vars (requestVars) khi có; nếu không có thì lấy giờ máy lúc gọi.
         */
        fun currentTimeLine(vars: Map<String, String>): String {
            val now = LocalDateTime.now()
            val date = vars["date"] ?: now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            val time = vars["time"] ?: now.format(DateTimeFormatter.ofPattern("HH:mm"))
            return "Current time: $date $time"
        }

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

        /** Bố cục mặc định: đúng thứ mà app vẫn gửi trước Đợt C, thêm thẻ Metadata cố định lên đầu (như FPilot). */
        fun defaultLayout(systemPrompt: String): RequestLayout = RequestLayout(
            listOf(
                RequestCard(METADATA_ID, CardKind.METADATA),
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
    fun metadataCard(): RequestCard? = cards.firstOrNull { it.kind == CardKind.METADATA }
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
        val metadata = cards.filter { it.kind == CardKind.METADATA }
        val history = cards.filter { it.kind == CardKind.HISTORY }
        val lastTurn = cards.filter { it.kind == CardKind.LAST_TURN }
        if (metadata.size != 1) errors += "The Metadata card must appear exactly once (found ${metadata.size})."
        if (metadata.size == 1) {
            if (cards.indexOf(metadata[0]) != 0) errors += "The Metadata card must be first."
            if (!metadata[0].enabled) errors += "The Metadata card cannot be turned off."
        }
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
        // Đợt fix 11/11/2026 (I1): file thẻ trỏ tới có thể ở bất cứ đâu, đuôi
        // bất kỳ — không còn kiểm tra đuôi .md nữa (đọc lỗi thì đường gửi /
        // xem trước chặn lại và báo rõ, không tự fallback).
        return errors
    }

    fun isValid(): Boolean = validate().isEmpty()

    /** Thay biến {date}, {time}, {model}, {provider} trong văn bản thẻ. */
    fun substitute(text: String, vars: Map<String, String>): String {
        var out = text
        vars.forEach { (k, v) -> out = out.replace("{$k}", v) }
        return out
    }

    /**
     * Các phần system prompt, theo thứ tự thẻ (System + Free vai system),
     * rồi dòng giờ ở cuối khi thẻ Time BẬT.
     *
     * Thẻ Time BẬT: thêm "Current time: YYYY-MM-DD HH:MM" vào CUỐI phần
     * system, gắn nhãn APP_ADDED (dòng giờ do app thêm, không phải user viết).
     * Thẻ Time TẮT, hoặc không có thẻ Time: không chèn gì. Dòng giờ đổi mỗi
     * phút nên cache không dùng lại được khi thẻ bật.
     */
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
        if (timeCard()?.enabled == true) {
            parts += SystemPart(currentTimeLine(vars), SystemSource.APP_ADDED)
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
     * Lọc danh sách công cụ đã chọn theo thẻ Tools (RCP, 17/11/2026).
     * - Thẻ Tools tắt = không offer công cụ nào.
     * - Không có thẻ Tools = không offer công cụ nào (đường gửi đã cho
     *   danh sách rỗng nên defs vào đây cũng rỗng). Đường "legacy"
     *   offer-all đã bỏ hẳn (quyết định 13).
     * - Thẻ Time KHÔNG lọc tool nữa (Đợt 2, quyết định 1): time.now luôn
     *   được offer như một tool bình thường; tắt time.now ở tầng quản lý
     *   tool thì xử lý như các tool khác (vẫn offer, chặn lúc gọi).
     * Vị trí thẻ Time/Tools không ảnh hưởng gì: công cụ không phải tin nhắn, mà đi ở trường riêng.
     */
    fun effectiveToolDefs(defs: List<ToolDef>): List<ToolDef> {
        if (toolsCard()?.enabled == false) return emptyList()
        return defs
    }
}
