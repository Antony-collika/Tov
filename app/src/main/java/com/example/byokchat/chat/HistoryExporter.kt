package com.example.byokchat.chat

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.images
import com.example.byokchat.tools.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Một vòng gọi tool trong history.meta.json (Đợt E, format sidecar mượn FPilot).
 * Ghi từ Room: lệnh gọi nằm ở toolCallsJson của tin assistant, kết quả nằm
 * ở các dòng tool cùng lượt (nối bằng toolCallId).
 */
@Serializable
data class HistoryToolRound(
    /** id của lệnh gọi (call id của model). */
    val id: String,
    /** uid thật của tool (null nếu không rõ). */
    val uid: String? = null,
    /** tên model đã gọi (tên trong file tools.md, hoặc tên gốc). */
    val name: String,
    /** tham số gọi, dạng chuỗi JSON. */
    val args: String = "{}",
    /** kết quả tool (văn bản đầy đủ). */
    val result: String = "",
    val is_error: Boolean = false,
)

/** Một tin nhắn trong history.meta.json, keyed theo UUID của tin. */
@Serializable
data class HistoryMetaMessage(
    /** File đính kèm: đường dẫn ảnh của tin user (như lưu trong Room). */
    val attachments: List<String> = emptyList(),
    val createdAt: Long,
    /**
     * Nội dung thêm của provider (vd thought_signature của Gemini, suy luận
     * của OpenAI / máy chủ tương thích — xem HistoryExporter.buildExtraContent).
     * Lúc nhập thì đọc lại vào Room (xem HistorySync.reasoningOf).
     */
    val extra_content: JsonElement? = null,
    val tool_rounds: List<HistoryToolRound> = emptyList(),
)

/** history.meta.json: cầu nối giữa history.md và Room (id từng tin + vòng tool). */
@Serializable
data class HistoryMeta(
    val version: Int = 1,
    val session: String,
    val messages: Map<String, HistoryMetaMessage>,
)

/**
 * Một dòng trong history.meta.json dạng JSONL (Đợt fix 12/11/2026): một tin
 * nhắn, kèm id. Cùng id thì dòng sau thắng khi đọc — để append có thể cập
 * nhật một tin cũ mà không cần ghi lại toàn bộ file.
 */
@Serializable
data class HistoryMetaEntry(
    val id: String,
    val attachments: List<String> = emptyList(),
    val createdAt: Long,
    val extra_content: JsonElement? = null,
    val tool_rounds: List<HistoryToolRound> = emptyList(),
)

/** Dòng đầu của history.meta.json dạng JSONL. */
@Serializable
data class HistoryMetaHeader(
    val version: Int = 2,
    val session: String,
)

/**
 * Xuất lịch sử chat ra history.md + history.meta.json trong Session storage (Đợt E).
 * Room vẫn là nguồn duy nhất; file là "bản chiếu" để đọc/grep/đồng bộ.
 * Định dạng sidecar mượn FPilot: frontmatter (session 8 ký tự, title có ngoặc
 * kép, created ISO 8601), rồi mỗi lượt "## Tn user" / "## Tn assistant"
 * kèm dòng {"id":...} ngay sau heading. Số lượt tiếp theo KHÔNG ghi trong
 * file — parser suy ra từ các section (lượt lớn nhất + 1); file cũ có dòng
 * next_turn thì parser vẫn đọc và kẹp lên, không lỗi. Vòng tool KHÔNG nằm
 * trong .md nữa mà nằm trong history.meta.json. Lượt chưa xong (user gửi mà
 * chưa có assistant trả lời) không ghi vào file.
 */
object HistoryExporter {
    private val json = Json { ignoreUnknownKeys = true }
    /** JSON gọn (một dòng) cho các dòng JSONL của history.meta.json. */
    private val metaLineJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** Thư mục session: sessions/<ngày>_<session-id 8 ký tự>/ (Đợt E). */
    fun sessionDir(conversation: ConversationEntity): String {
        val date = Instant.ofEpochMilli(conversation.createdAt)
            .atZone(ZoneId.systemDefault()).toLocalDate().format(dateFmt)
        return "sessions/${date}_${conversation.id.take(8)}"
    }

    fun historyPath(conversation: ConversationEntity): String = "${sessionDir(conversation)}/history.md"

    fun metaPath(conversation: ConversationEntity): String = "${sessionDir(conversation)}/history.meta.json"

    /**
     * Các lượt đã xong, kèm số lượt ổn định (Đợt 4): một lượt chỉ "xong" khi
     * có ít nhất một tin assistant không còn ở trạng thái streaming (tin lỗi
     * cũng tính là xong). Lượt chỉ có tin user thì chưa xong, không xuất.
     */
    private fun completedTurns(rows: List<MessageEntity>): List<Pair<Int, List<MessageEntity>>> {
        val turns = LinkedHashMap<String, MutableList<MessageEntity>>()
        rows.forEach { turns.getOrPut(it.turnId) { mutableListOf() }.add(it) }
        val numbers = TurnNumbers.effectiveTurnNumbers(rows)
        return turns.entries.mapNotNull { (turnId, rs) ->
            val done = rs.any { it.role == "assistant" && it.status != "streaming" }
            if (!done) null else (numbers[turnId] ?: 0) to rs
        }
    }

    /** Các tin được xuất ra file: tin trong lượt đã xong, trừ tin assistant đang stream
     * và tin assistant trống hoàn toàn (không chữ, không lệnh gọi, không lỗi). */
    private fun exportedMessages(rows: List<MessageEntity>): List<MessageEntity> {
        val completedIds = completedTurns(rows).flatMap { (_, rs) -> rs.map { it.id } }.toSet()
        return rows.filter { m ->
            m.id in completedIds && when (m.role) {
                "assistant" -> m.status != "streaming" &&
                    (m.text.isNotBlank() || m.error != null || !m.toolCallsJson.isNullOrBlank())
                // Tin user luôn xuất. Tin tool không có trong .md (vòng tool
                // nằm trong meta của tin assistant) nên cũng không có trong meta.
                "user" -> true
                else -> false
            }
        }
    }

    /** Dựng toàn bộ nội dung history.md từ DB (hàm thuần, để test được). */
    fun renderHistory(conversation: ConversationEntity, rows: List<MessageEntity>): String {
        val turns = completedTurns(rows)
        val created = DateTimeFormatter.ISO_INSTANT
            .format(Instant.ofEpochMilli(conversation.createdAt))
        return buildString {
            appendLine("---")
            appendLine("session: ${conversation.id.take(8)}")
            appendLine("title: ${json.encodeToString(conversation.title.ifBlank { "Untitled" })}")
            appendLine("created: $created")
            appendLine("---")
            turns.forEach { (n, rs) ->
                appendLine()
                renderTurn(n, rs)
            }
        }
    }

    private fun StringBuilder.renderTurn(n: Int, rs: List<MessageEntity>) {
        for (m in rs) {
            when (m.role) {
                "user" -> {
                    appendLine("## T$n user")
                    appendLine("{\"id\":\"${m.id}\"}")
                    appendLine(m.text)
                    val images = m.images()
                    if (images.isNotEmpty()) appendLine("(+ ${images.size} image(s))")
                    appendLine()
                }
                "assistant" -> {
                    if (m.status == "streaming") continue
                    if (m.text.isBlank() && m.error == null && m.toolCallsJson.isNullOrBlank()) continue
                    appendLine("## T$n assistant")
                    appendLine("{\"id\":\"${m.id}\"}")
                    if (m.text.isNotBlank()) appendLine(m.text)
                    if (m.error != null) appendLine("(error: ${m.error})")
                    appendLine()
                }
                // Tin tool không ghi trong .md nữa — vòng tool nằm trong history.meta.json.
            }
        }
    }

    /**
     * Dựng history.meta.json dạng JSONL từ DB (hàm thuần, để test được):
     * dòng đầu là header {version: 2, session}, mỗi dòng sau là một tin
     * (xem [HistoryMetaEntry]). Nhờ dạng này, có lượt mới xong thì chỉ cần
     * append thêm dòng — không phải ghi lại toàn bộ file (xem
     * [HistorySync.appendTurn]).
     */
    fun renderMeta(conversation: ConversationEntity, rows: List<MessageEntity>): String {
        return buildString {
            appendLine(metaLineJson.encodeToString(HistoryMetaHeader(session = conversation.id.take(8))))
            metaEntries(rows).forEach { appendLine(metaLineJson.encodeToString(it)) }
        }
    }

    /** Các dòng meta của các tin đã xuất trong [rows] (dùng chung cho ghi đủ và append). */
    private fun metaEntries(rows: List<MessageEntity>): List<HistoryMetaEntry> {
        val exported = exportedMessages(rows)
        val turns = completedTurns(rows)
        val resultsByTurn = turns.flatMap { (_, rs) -> rs.filter { it.role == "tool" } }
            .groupBy { it.turnId }
        // Mỗi kết quả tool chỉ gắn cho một tin assistant trong lượt (tin đã gọi
        // tool đó); tin assistant sau trong cùng lượt không nhận lại.
        val claimedByTurn = mutableMapOf<String, MutableSet<String?>>()
        return exported.map { m ->
            val rounds = if (m.role != "assistant") emptyList() else {
                val claimed = claimedByTurn.getOrPut(m.turnId) { mutableSetOf() }
                toolRoundsOf(m, resultsByTurn[m.turnId].orEmpty(), claimed)
            }
            HistoryMetaEntry(
                id = m.id,
                attachments = if (m.role == "user") m.images() else emptyList(),
                createdAt = m.createdAt,
                extra_content = buildExtraContent(m),
                tool_rounds = rounds,
            )
        }
    }

    /**
     * Nội dung thêm của provider cho một tin (Đợt provider 12/11/2026 — theo
     * mẫu thoughtSignature của Gemini, key theo hãng):
     * - google: {"thought_signature": ...} (đã có từ đợt fix 12/11/2026);
     * - openai: {"reasoning_summary": ..., "response_items": [...]} — mảng
     *   output items gửi lại nguyên vẹn ở lượt sau (kèm encrypted_content);
     * - deepseek: {"reasoning_content": ...} — chuỗi suy luận dạng text
     *   (dữ liệu cũ; nay DeepSeek dùng qua Custom, key "custom").
     * - custom: {"reasoning_content": ...} — chuỗi suy luận của máy chủ
     *   tương thích (vd DeepSeek qua Custom).
     * Khối thinking của hãng đã bỏ ("anthropic") thì không ghi nữa.
     * history.md không ghi thêm gì (Duy 12/11 ~10:28: file đó chỉ là lịch sử).
     */
    private fun buildExtraContent(m: MessageEntity): JsonElement? {
        val obj = buildJsonObject {
            // Đợt fix 12/11/2026 (K): chữ ký suy luận của Gemini cho phần
            // chữ (nếu có) — đúng dạng template của FPilot
            // {"google": {"thought_signature": "..."}}.
            m.thoughtSignature?.let { sig ->
                put("google", buildJsonObject { put("thought_signature", sig) })
            }
            when (m.reasoningProvider) {
                "openai" -> put("openai", buildJsonObject {
                    m.reasoningText?.let { put("reasoning_summary", it) }
                    m.reasoningReplay?.let { replay ->
                        runCatching { json.parseToJsonElement(replay) }.getOrNull()
                            ?.let { put("response_items", it) }
                    }
                })
                "deepseek" -> put("deepseek", buildJsonObject {
                    m.reasoningText?.let { put("reasoning_content", it) }
                })
                "custom" -> put("custom", buildJsonObject {
                    m.reasoningText?.let { put("reasoning_content", it) }
                })
            }
        }
        return obj.takeIf { it.isNotEmpty() }
    }

    /**
     * Dựng riêng phần history.md của một lượt đã xong (không có frontmatter),
     * đúng y như [renderHistory] viết cho lượt đó: một dòng trống rồi các
     * section của lượt. Dùng cho append (xem [HistorySync.appendTurn]).
     * Trả null nếu lượt chưa xong hoặc không có gì để ghi.
     */
    fun renderTurnSection(turnNo: Int, rows: List<MessageEntity>): String? {
        val turn = completedTurns(rows).firstOrNull { (n, _) -> n == turnNo } ?: return null
        return buildString {
            appendLine()
            renderTurn(turn.first, turn.second)
        }
    }

    /**
     * Các dòng meta JSONL của một lượt đã xong (dùng cho append).
     * Trả null nếu lượt chưa xong.
     */
    fun renderMetaLines(turnNo: Int, rows: List<MessageEntity>): List<String>? {
        val turn = completedTurns(rows).firstOrNull { (n, _) -> n == turnNo } ?: return null
        return metaEntries(turn.second).map { metaLineJson.encodeToString(it) }
    }

    /** Vòng tool của một tin assistant: lệnh gọi + kết quả đã ghép theo toolCallId. */
    private fun toolRoundsOf(
        assistant: MessageEntity,
        toolRows: List<MessageEntity>,
        claimed: MutableSet<String?>,
    ): List<HistoryToolRound> {
        val calls = assistant.toolCallsJson
            ?.let { runCatching { json.decodeFromString(callsSer, it) }.getOrNull() }
            .orEmpty()
        val byCallId = toolRows.associateBy { it.toolCallId }
        val matched = mutableSetOf<String?>()
        val rounds = calls.map { call ->
            val result = byCallId[call.id]
            if (result != null) {
                matched += call.id
                claimed += call.id
            }
            HistoryToolRound(
                id = call.id,
                uid = result?.toolUid,
                name = call.name,
                args = call.argsJson,
                result = result?.text.orEmpty(),
                is_error = result?.toolIsError ?: false,
            )
        }
        // Kết quả tool không gắn được với lệnh gọi nào (vd lượt bị ngắt giữa chừng).
        // Chỉ gắn những kết quả chưa ai nhận trong lượt (xem claimed).
        val unmatched = toolRows.filter { it.toolCallId !in matched && it.toolCallId !in claimed }.map { m ->
            claimed += m.toolCallId
            HistoryToolRound(
                id = m.toolCallId ?: "?",
                uid = m.toolUid,
                name = m.toolName ?: "?",
                args = "{}",
                result = m.text,
                is_error = m.toolIsError,
            )
        }
        return rounds + unmatched
    }

    /**
     * Ghi history.md + history.meta.json cho một cuộc trò chuyện (ghi .md
     * trước rồi .meta.json). Best-effort: chưa chọn thư mục, mất quyền hay
     * lỗi ghi đều bỏ qua, không bao giờ làm hỏng chat.
     */
    suspend fun exportHistory(fs: WorkspaceFs, db: AppDatabase, conversationId: String) {
        runCatching {
            val conv = db.conversations().get(conversationId) ?: return
            val rows = db.messages().getAll(conversationId)
            val md = renderHistory(conv, rows)
            val meta = renderMeta(conv, rows)
            withContext(Dispatchers.IO) {
                fs.writeSessionText(historyPath(conv), md)
                fs.writeSessionText(metaPath(conv), meta)
            }
        }
    }
}
