package com.example.byokchat.data

import com.example.byokchat.ai.ToolDef
import com.example.byokchat.chat.SystemPart
import com.example.byokchat.chat.SystemSource
import com.example.byokchat.tools.ParsedTool
import com.example.byokchat.tools.ToolsMdParseException
import com.example.byokchat.tools.ToolsMdParser
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Loại thẻ trong Request panel (Đợt C). Thứ tự thẻ trong danh sách là thứ tự gửi. */
enum class CardKind { METADATA, SYSTEM, TIME, FREE, HISTORY, LAST_TURN, TOOLS }

/** Nguồn nội dung của thẻ Tools: gõ trực tiếp vào thẻ (TEXT) hay file .md (FILE). */
enum class ToolsSource { TEXT, FILE }

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
    /**
     * Thẻ Tools (Đợt E): danh sách file tools.md đã chọn, theo thứ tự.
     * App gộp (hợp) tất cả tool trong các file rồi đưa cho model: theo thứ
     * tự file đã chọn, trong mỗi file theo thứ tự khối. Giữ tương thích
     * ngược với [file]: thẻ Tools cũ chỉ có [file] thì coi như danh sách
     * 1 file (xem [toolFiles]).
     */
    val files: List<String> = emptyList(),
    /**
     * Thẻ Tools: nguồn nội dung — TEXT (gõ trực tiếp vào thẻ, parse như
     * tools.md) hay FILE (các file .md đã chọn). Mặc định FILE (thẻ cũ
     * chỉ có file). Nguồn FILE mà chưa chọn file nào thì lần gửi bị chặn
     * (xem resolveToolFiles); nguồn TEXT để trống thì 0 tool, không lỗi.
     */
    val toolsSource: ToolsSource = ToolsSource.FILE,
) {
    /** File tools.md của thẻ Tools: [files] nếu có, không thì [file] cũ. */
    fun toolFiles(): List<String> = if (files.isNotEmpty()) files else listOfNotNull(file)
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
        cards.filter { (it.kind == CardKind.SYSTEM || it.kind == CardKind.FREE) && it.file != null && !it.file.endsWith(".md") }.forEach {
            errors += "The ${it.displayName()} card links to '${it.file}' — the file must end with .md."
        }
        cards.filter { it.kind == CardKind.TOOLS }.flatMap { it.toolFiles() }.filter { !it.endsWith(".md") }.forEach {
            errors += "The Tools card links to '$it' — the file must end with .md."
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
     * Kết quả resolve nguồn tools.md của thẻ Tools (Đợt E; luật nguồn sửa
     * theo quyết định 11/11/2026).
     * - exposed == null: đường "legacy" offer tất cả tool đã bỏ hẳn
     *   (quyết định 13) — không còn chỗ nào tạo ra null nữa; giữ kiểu
     *   nullable để tương thích với ToolRegistry. Không có thẻ Tools cho
     *   danh sách rỗng (xóa thẻ = 0 tool, xem resolveToolFiles).
     * - exposed == danh sách rỗng: thẻ Tools tắt, nguồn Text để trống,
     *   hoặc file rỗng → 0 tool.
     * - errors rỗng = resolve được hết; có lỗi thì exposed rỗng và lần gửi
     *   bị chặn (báo lỗi rõ ràng thay vì gửi với danh sách tool sai).
     */
    data class ToolsResolution(val exposed: List<ParsedTool>?, val errors: List<String>)

    /**
     * Đợt E: resolve danh sách tool của thẻ Tools theo nguồn đã chọn.
     * Parse lại mỗi lần gửi (gọi ở đầu mỗi lần gửi + trước preview, như
     * resolveFiles). Luật nguồn (quyết định 11/11/2026):
     * - Nguồn Text: parse văn bản trong thẻ như tools.md. Để trống → 0
     *   tool, KHÔNG lỗi, vẫn gửi được. Lỗi cú pháp → báo lỗi, chặn gửi.
     * - Nguồn File: đọc + parse các file tools.md đã chọn, gộp lại theo
     *   thứ tự file đã chọn, trong mỗi file theo thứ tự khối. Chưa chọn
     *   file nào → BÁO LỖI + chặn gửi (không còn đường "legacy" offer tất
     *   cả tool). File rỗng được phép (0 tool); chỉ báo lỗi khi không
     *   parse được.
     * - Cùng một tool ở hai file: mô tả GIỐNG HỆT nhau thì gộp bình thường
     *   (dedupe, giữ khối đầu tiên); mô tả KHÁC nhau thì BÁO LỖI nêu rõ tool
     *   nào và ở file nào, KHÔNG tự chọn một bên.
     * - Không có thẻ Tools → 0 tool, KHÔNG lỗi, vẫn gửi được (quyết định
     *   11/11/2026: xóa thẻ là ý định của user — không gửi tool; app không
     *   tự fallback offer-all sau lưng user). Đường "legacy" offer tất cả
     *   tool đã bỏ hẳn (quyết định 13); thẻ Tools tắt → 0 tool.
     */
    fun resolveToolFiles(
        readFile: (String) -> Result<String>,
        origins: Map<String, String>,
    ): ToolsResolution {
        // Không có thẻ Tools: user đã xóa thẻ = không muốn gửi tool nào.
        // 0 tool, KHÔNG lỗi, vẫn gửi được (quyết định 11/11/2026). Đường
        // "legacy" offer tất cả tool đã bỏ hẳn (quyết định 13).
        val card = toolsCard() ?: return ToolsResolution(emptyList(), emptyList())
        if (!card.enabled) return ToolsResolution(emptyList(), emptyList())
        if (card.toolsSource == ToolsSource.TEXT) {
            // Nguồn Text: parse văn bản trong thẻ như tools.md. Để trống
            // thì 0 tool, không lỗi; lỗi cú pháp thì chặn gửi như file
            // không parse được.
            if (card.text.isBlank()) return ToolsResolution(emptyList(), emptyList())
            return try {
                ToolsResolution(ToolsMdParser.parse(card.text, origins), emptyList())
            } catch (e: ToolsMdParseException) {
                ToolsResolution(emptyList(), listOf("In the Tools card text: ${e.message}"))
            }
        }
        val files = card.toolFiles()
        // Nguồn File mà chưa chọn file nào thì chặn gửi + báo rõ (quyết
        // định 11/11/2026): không còn đường "legacy" offer tất cả tool.
        if (files.isEmpty()) {
            return ToolsResolution(
                emptyList(),
                listOf(
                    "No file selected for the Tools card. " +
                        "Choose one or more tools.md files, or switch the card to Text input.",
                ),
            )
        }
        val errors = mutableListOf<String>()
        val merged = mutableListOf<ParsedTool>()
        val seenByUid = mutableMapOf<String, ParsedTool>()
        val seenInFile = mutableMapOf<String, String>()
        for (path in files) {
            val text = readFile(path).fold(
                onSuccess = { it },
                onFailure = { e ->
                    errors += "Could not read the Tools card file '$path': ${e.message}"
                    null
                },
            ) ?: continue
            val parsed = try {
                ToolsMdParser.parse(text, origins)
            } catch (e: ToolsMdParseException) {
                errors += "In tools file '$path': ${e.message}"
                null
            } ?: continue
            for (tool in parsed) {
                val prev = seenByUid[tool.uid]
                if (prev == null) {
                    seenByUid[tool.uid] = tool
                    seenInFile[tool.uid] = path
                    merged += tool
                } else if (prev.name != tool.name || prev.description != tool.description) {
                    errors += "Tool \"${tool.uid}\" is described differently in '${seenInFile[tool.uid]}' and '$path'. " +
                        "Fix the files so they agree, or remove the tool from one of them."
                }
                // Giống hệt nhau thì gộp bình thường (dedupe).
            }
        }
        if (errors.isNotEmpty()) return ToolsResolution(emptyList(), errors)
        return ToolsResolution(merged, emptyList())
    }

    /**
     * Lọc danh sách công cụ đã resolve theo thẻ Tools.
     * - Thẻ Tools tắt = không offer công cụ nào.
     * - Không có thẻ Tools = không offer công cụ nào (resolveToolFiles đã
     *   trả danh sách rỗng nên defs vào đây cũng rỗng). Đường "legacy"
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
