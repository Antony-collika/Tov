package com.example.byokchat.chat

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.images
import com.example.byokchat.tools.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Xuất lịch sử chat ra history.md trong workspace (Đợt D).
 * Chỉ xuất — Room vẫn là nguồn duy nhất; app không đọc lại file này.
 * Định dạng mượn FPilot: phần đầu front matter, rồi mỗi lượt
 * "## Tn user" / "## Tn round k" (từng lệnh gọi và kết quả công cụ) /
 * "## Tn assistant". Để đọc, grep, đồng bộ qua Syncthing/Drive.
 */
object HistoryExporter {
    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** Thư mục của cuộc trò chuyện: Tov/chats/<ngày>_<id 8 ký tự>/history.md */
    fun historyPath(conversation: ConversationEntity): String {
        val date = Instant.ofEpochMilli(conversation.createdAt)
            .atZone(ZoneId.systemDefault()).toLocalDate().format(dateFmt)
        return "Tov/chats/${date}_${conversation.id.take(8)}/history.md"
    }

    /** Dựng toàn bộ nội dung history.md từ DB (hàm thuần, để test được). */
    fun renderHistory(conversation: ConversationEntity, rows: List<MessageEntity>): String {
        val turns = LinkedHashMap<String, MutableList<MessageEntity>>()
        rows.forEach { turns.getOrPut(it.turnId) { mutableListOf() }.add(it) }
        val created = DateTimeFormatter.ISO_INSTANT
            .format(Instant.ofEpochMilli(conversation.createdAt))
        return buildString {
            appendLine("---")
            appendLine("session: ${conversation.id}")
            appendLine("title: ${json.encodeToString(conversation.title.ifBlank { "Untitled" })}")
            appendLine("created: $created")
            appendLine("next_turn: ${turns.size + 1}")
            appendLine("---")
            turns.entries.forEachIndexed { index, (_, rs) ->
                appendLine()
                renderTurn(index + 1, rs)
            }
        }
    }

    private fun StringBuilder.renderTurn(n: Int, rs: List<MessageEntity>) {
        val resultsByCallId = rs.filter { it.role == "tool" }.associateBy { it.toolCallId }
        val matched = mutableSetOf<String?>()
        var round = 0
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
                    val calls = m.toolCallsJson
                        ?.let { runCatching { json.decodeFromString(callsSer, it) }.getOrNull() }
                        .orEmpty()
                    if (calls.isNotEmpty()) {
                        round++
                        appendLine("## T$n round $round")
                        if (m.text.isNotBlank()) {
                            appendLine(m.text)
                            appendLine()
                        }
                        for (call in calls) {
                            appendLine("### call ${call.id} ${call.name}")
                            appendLine("```json")
                            appendLine(call.argsJson)
                            appendLine("```")
                            val result = resultsByCallId[call.id]
                            if (result != null) {
                                matched += call.id
                                appendLine("### result ${call.id}")
                                appendLine(result.text)
                            }
                            appendLine()
                        }
                    } else if (m.text.isNotBlank() || m.error != null) {
                        appendLine("## T$n assistant")
                        appendLine("{\"id\":\"${m.id}\"}")
                        if (m.text.isNotBlank()) appendLine(m.text)
                        if (m.error != null) appendLine("(error: ${m.error})")
                        appendLine()
                    }
                }
                // Tin tool đã gắn với lệnh gọi ở trên.
            }
        }
        // Kết quả tool không gắn được với lệnh gọi nào (vd lượt bị ngắt giữa chừng).
        rs.filter { it.role == "tool" && it.toolCallId !in matched }.forEach { m ->
            appendLine("### result ${m.toolCallId ?: "?"} (unmatched)")
            appendLine(m.text)
            appendLine()
        }
    }

    /**
     * Ghi history.md cho một cuộc trò chuyện. Best-effort: workspace chưa
     * chọn, mất quyền hay lỗi ghi đều bỏ qua, không bao giờ làm hỏng chat.
     */
    suspend fun exportHistory(fs: WorkspaceFs, db: AppDatabase, conversationId: String) {
        runCatching {
            val conv = db.conversations().get(conversationId) ?: return
            val rows = db.messages().getAll(conversationId)
            val text = renderHistory(conv, rows)
            withContext(Dispatchers.IO) { fs.writeText(historyPath(conv), text) }
        }
    }
}
