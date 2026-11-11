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
     * Nội dung thêm của provider (vd thought_signature của Gemini). Room
     * KHÔNG lưu trường này (không đổi cấu trúc DB đợt này) nên lúc xuất
     * luôn là null; lúc nhập thì bỏ qua. Xem HistorySync.
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
 * Xuất lịch sử chat ra history.md + history.meta.json trong Session storage (Đợt E).
 * Room vẫn là nguồn duy nhất; file là "bản chiếu" để đọc/grep/đồng bộ.
 * Định dạng sidecar mượn FPilot: frontmatter (session 8 ký tự, title có ngoặc
 * kép, created ISO 8601, next_turn), rồi mỗi lượt "## Tn user" / "## Tn assistant"
 * kèm dòng {"id":...} ngay sau heading. Vòng tool KHÔNG nằm trong .md nữa mà
 * nằm trong history.meta.json. Lượt chưa xong (user gửi mà chưa có assistant
 * trả lời) không ghi vào file.
 */
object HistoryExporter {
    private val json = Json { ignoreUnknownKeys = true }
    private val metaJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
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
            appendLine("next_turn: ${TurnNumbers.nextTurnNo(rows)}")
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

    /** Dựng history.meta.json từ DB (hàm thuần, để test được). */
    fun renderMeta(conversation: ConversationEntity, rows: List<MessageEntity>): String {
        val exported = exportedMessages(rows)
        val turns = completedTurns(rows)
        val resultsByTurn = turns.flatMap { (_, rs) -> rs.filter { it.role == "tool" } }
            .groupBy { it.turnId }
        val messages = LinkedHashMap<String, HistoryMetaMessage>()
        for (m in exported) {
            val rounds = if (m.role != "assistant") emptyList() else toolRoundsOf(m, resultsByTurn[m.turnId].orEmpty())
            messages[m.id] = HistoryMetaMessage(
                attachments = if (m.role == "user") m.images() else emptyList(),
                createdAt = m.createdAt,
                extra_content = null,
                tool_rounds = rounds,
            )
        }
        return metaJson.encodeToString(HistoryMeta(session = conversation.id.take(8), messages = messages))
    }

    /** Vòng tool của một tin assistant: lệnh gọi + kết quả đã ghép theo toolCallId. */
    private fun toolRoundsOf(assistant: MessageEntity, toolRows: List<MessageEntity>): List<HistoryToolRound> {
        val calls = assistant.toolCallsJson
            ?.let { runCatching { json.decodeFromString(callsSer, it) }.getOrNull() }
            .orEmpty()
        val byCallId = toolRows.associateBy { it.toolCallId }
        val matched = mutableSetOf<String?>()
        val rounds = calls.map { call ->
            val result = byCallId[call.id]
            if (result != null) matched += call.id
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
        val unmatched = toolRows.filter { it.toolCallId !in matched }.map { m ->
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
