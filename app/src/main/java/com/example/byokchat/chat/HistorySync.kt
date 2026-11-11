package com.example.byokchat.chat

import android.content.Context
import androidx.room.withTransaction
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.encodeImages
import com.example.byokchat.tools.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/**
 * Đồng bộ hai chiều history.md (Đợt E, phần cốt yếu của Glassbox-RCP).
 * Room vẫn là nguồn sự thật; file là "bản chiếu" có thể nhập lại.
 *
 * - Đọc khi MỞ ĐÚNG CHAT (không parse ngay khi mở app, không đợi đến lúc gửi).
 * - Sửa trong app → ghi ra file ngay (history.md trước rồi history.meta.json,
 *   verify bằng đọc lại như FPilot).
 * - App dùng HASH (SHA-256) để phát hiện file đổi và chỉ đọc khi có thay đổi;
 *   hash lưu theo session trong SharedPreferences "history_sync".
 * - Sửa ngoài lúc model đang trả lời thì KHÔNG parse: app phát hiện bằng hash
 *   + trạng thái đang trả lời, ghi nhận lại, và báo user khi vào lại app rằng
 *   lần sửa đó không có hiệu lực (file đã bị ghi đè khi lượt trả lời xong).
 * - Xung đột khi regenerate: giữ bản sửa ngoài, hủy đáp án mới, báo lỗi
 *   (xem hasExternalChange, gọi từ ChatViewModel trước khi regenerate).
 *
 * Thiết kế hash + báo user này VƯỢT HƠN FPilot (FPilot không có hash/timestamp,
 * xung đột chỉ xử lý khi regenerate). Tov chỉ lấy format sidecar, KHÔNG mang
 * format legacy của FPilot.
 */
class HistorySync(
    context: Context,
    private val db: AppDatabase,
    private val fs: WorkspaceFs,
) {
    private val prefs = context.applicationContext.getSharedPreferences("history_sync", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    sealed interface SyncResult {
        /** File khớp với Room (hoặc chưa có gì để đồng bộ). */
        data object UpToDate : SyncResult
        /** Đã áp thay đổi từ file vào Room. */
        data class Imported(val changed: Int, val deleted: Int) : SyncResult
        /** Model đang trả lời nên không parse; lần sửa ngoài (nếu có) đã được ghi nhận. */
        data object SkippedWhileGenerating : SyncResult
        /** Chưa có file session nào (chat chưa gửi lần nào). */
        data object NoSessionFile : SyncResult
        /** File lỗi, Room giữ nguyên. */
        data class Failed(val error: String) : SyncResult
        /** Sửa ngoài lúc model đang trả lời đã bị ghi đè khi lượt trả lời xong. */
        data object ExternalEditDiscarded : SyncResult
    }

    private fun hashKey(conversationId: String) = "hash:$conversationId"
    private fun skippedKey(conversationId: String) = "skipped:$conversationId"

    private fun hashOf(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun storedHash(conversationId: String): String? = prefs.getString(hashKey(conversationId), null)

    private fun storeHash(conversationId: String, hash: String) {
        prefs.edit().putString(hashKey(conversationId), hash).apply()
    }

    /**
     * Đồng bộ khi mở một cuộc trò chuyện. Chỉ đọc file khi hash đổi so với
     * lần sync/ghi gần nhất; file lỗi thì Room giữ nguyên và trả Failed.
     */
    suspend fun syncOnOpen(conversationId: String, isGenerating: () -> Boolean): SyncResult {
        val conv = db.conversations().get(conversationId) ?: return SyncResult.UpToDate
        val mdPath = HistoryExporter.historyPath(conv)
        val metaPath = HistoryExporter.metaPath(conv)

        if (isGenerating()) {
            // Không parse lúc model đang trả lời. Chỉ ghi nhận là file đã đổi
            // (so bằng hash) để báo user khi vào lại app.
            val current = runCatching { withContext(Dispatchers.IO) { fs.readSessionText(mdPath) } }.getOrNull()
            if (current != null && hashOf(current) != storedHash(conversationId)) {
                prefs.edit().putString(skippedKey(conversationId), hashOf(current)).apply()
            }
            return SyncResult.SkippedWhileGenerating
        }

        val mdText = runCatching { withContext(Dispatchers.IO) { fs.readSessionText(mdPath) } }.getOrNull()
            ?: run {
                prefs.edit().remove(skippedKey(conversationId)).apply()
                return SyncResult.NoSessionFile
            }
        val currentHash = hashOf(mdText)

        // Đã ghi nhận sửa ngoài lúc đang trả lời?
        if (prefs.contains(skippedKey(conversationId))) {
            prefs.edit().remove(skippedKey(conversationId)).apply()
            if (currentHash == storedHash(conversationId)) {
                // File đã bị ghi đè bởi lần xuất cuối lượt → lần sửa đó thật sự
                // không có hiệu lực, báo user. Còn nếu file vẫn giữ bản sửa
                // (vd lượt bị dừng nên không xuất) thì để lần sync này áp nó.
                return SyncResult.ExternalEditDiscarded
            }
        }
        if (currentHash == storedHash(conversationId)) return SyncResult.UpToDate

        val parsed = try {
            HistoryMdParser.parseMd(mdText)
        } catch (e: HistoryParseException) {
            return SyncResult.Failed(e.message ?: "Could not read history.md.")
        }
        if (parsed.session != conversationId.take(8)) {
            return SyncResult.Failed(
                "history.md is for session \"${parsed.session}\", not this chat (${conversationId.take(8)}). The file was left unchanged.",
            )
        }
        val metaText = runCatching { withContext(Dispatchers.IO) { fs.readSessionText(metaPath) } }.getOrNull()
            ?: return SyncResult.Failed("history.meta.json is missing next to history.md. The file was left unchanged.")
        val meta = try {
            HistoryMdParser.parseMeta(metaText)
        } catch (e: HistoryParseException) {
            return SyncResult.Failed(e.message ?: "Could not read history.meta.json.")
        }
        if (meta.session != parsed.session) {
            return SyncResult.Failed(
                "history.meta.json is for session \"${meta.session}\" but history.md is for \"${parsed.session}\". The file was left unchanged.",
            )
        }
        val missing = parsed.messages.firstOrNull { it.id !in meta.messages }
        if (missing != null) {
            return SyncResult.Failed(
                "history.meta.json has no entry for message \"${missing.id}\". The file was left unchanged.",
            )
        }
        val applied = applyParsed(conversationId, parsed, meta)
        storeHash(conversationId, currentHash)
        return if (applied.changed == 0 && applied.deleted == 0) SyncResult.UpToDate
        else SyncResult.Imported(applied.changed, applied.deleted)
    }

    private data class AppliedCounts(val changed: Int, val deleted: Int)

    /**
     * Áp nội dung file vào Room (một transaction).
     * - Tin trong file: có id thì cập nhật chữ/số lượt/trạng thái, chưa có thì thêm mới.
     * - Vòng tool: xóa dòng tool cũ của lượt rồi ghi lại từ meta (tool không có
     *   {"id"} trong file nên không nối được theo id).
     * - Tin trong Room mà file không còn: chỉ xóa khi thuộc lượt mà file "biết"
     *   (số lượt < next_turn của file — lượt đã xóa khỏi file thì xóa khỏi Room,
     *   lượt mới hơn file (vd lượt chưa xong, chưa xuất) thì giữ nguyên).
     * - Hàng turnNo == 0 (dữ liệu cũ) và tin streaming dở dang thì không xóa.
     */
    private suspend fun applyParsed(
        conversationId: String,
        parsed: ParsedHistory,
        meta: HistoryMeta,
    ): AppliedCounts {
        var changed = 0
        var deleted = 0
        db.withTransaction {
            val rows = db.messages().getAll(conversationId)
            val byId = rows.associateBy { it.id }
            val parsedById = parsed.messages.associateBy { it.id }
            val knownTurns = (1 until parsed.nextTurn).toSet()
            var maxSeq = rows.maxOfOrNull { it.seq } ?: 0
            val turnIds = rows.groupBy({ it.turnNo }, { it.turnId })
                .mapValues { (_, ids) -> ids.first() }.toMutableMap()

            for (pm in parsed.messages) {
                val entry = meta.messages.getValue(pm.id)
                val callsJson = if (pm.role == "assistant" && entry.tool_rounds.isNotEmpty()) {
                    json.encodeToString(callsSer, entry.tool_rounds.map { AiToolCall(it.id, it.name, it.args) })
                } else {
                    null
                }
                val existing = byId[pm.id]
                if (existing != null) {
                    val newStatus = if (pm.error != null) "error" else "done"
                    val newImages = if (pm.role == "user") encodeImages(entry.attachments) else existing.imagePaths
                    if (existing.text != pm.text || existing.turnNo != pm.turnNo ||
                        existing.status != newStatus || existing.error != pm.error ||
                        existing.toolCallsJson != callsJson || existing.imagePaths != newImages
                    ) {
                        db.messages().upsert(
                            existing.copy(
                                text = pm.text,
                                turnNo = pm.turnNo,
                                status = newStatus,
                                error = pm.error,
                                toolCallsJson = callsJson,
                                imagePaths = newImages,
                                edited = if (existing.text != pm.text) true else existing.edited,
                            ),
                        )
                        changed++
                    }
                } else {
                    val turnId = turnIds.getOrPut(pm.turnNo) { UUID.randomUUID().toString() }
                    maxSeq++
                    db.messages().upsert(
                        MessageEntity(
                            id = pm.id,
                            conversationId = conversationId,
                            turnId = turnId,
                            turnNo = pm.turnNo,
                            seq = maxSeq,
                            role = pm.role,
                            text = pm.text,
                            imagePaths = if (pm.role == "user") encodeImages(entry.attachments) else "[]",
                            toolCallsJson = callsJson,
                            status = if (pm.error != null) "error" else "done",
                            error = pm.error,
                            createdAt = entry.createdAt,
                        ),
                    )
                    changed++
                }
            }

            // Vòng tool của mọi lượt file biết: xóa dòng cũ, ghi lại từ meta.
            for (turnNo in knownTurns) {
                db.messages().deleteToolRowsOfTurn(conversationId, turnNo)
                val assistants = parsed.messages.filter { it.turnNo == turnNo && it.role == "assistant" }
                for (pm in assistants) {
                    val entry = meta.messages.getValue(pm.id)
                    val turnId = turnIds[turnNo] ?: continue
                    for (round in entry.tool_rounds) {
                        maxSeq++
                        db.messages().upsert(
                            MessageEntity(
                                id = UUID.randomUUID().toString(),
                                conversationId = conversationId,
                                turnId = turnId,
                                turnNo = turnNo,
                                seq = maxSeq,
                                role = "tool",
                                text = round.result,
                                toolCallId = round.id,
                                toolUid = round.uid,
                                toolName = round.name,
                                toolIsError = round.is_error,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }

            for (row in rows) {
                if (row.role != "user" && row.role != "assistant") continue
                if (row.turnNo <= 0 || row.turnNo !in knownTurns) continue
                if (row.status == "streaming") continue
                if (row.id !in parsedById) {
                    db.messages().deleteById(row.id)
                    deleted++
                }
            }

            val conv = db.conversations().get(conversationId)
            if (conv != null && parsed.title.isNotBlank() && parsed.title != conv.title) {
                db.conversations().rename(conversationId, parsed.title)
                changed++
            }
        }
        return AppliedCounts(changed, deleted)
    }

    /**
     * Ghi history.md + history.meta.json cho một cuộc (ghi .md trước rồi
     * .meta.json, verify bằng đọc lại). Best-effort: trả false khi ghi lỗi
     * (vd chưa chọn thư mục, mất quyền) thay vì làm hỏng chat. Ghi xong thì
     * cập nhật hash để lần mở sau không parse lại chính file mình vừa ghi.
     */
    suspend fun export(conversationId: String): Boolean {
        return runCatching {
            val conv = db.conversations().get(conversationId) ?: return false
            val rows = db.messages().getAll(conversationId)
            val md = HistoryExporter.renderHistory(conv, rows)
            val meta = HistoryExporter.renderMeta(conv, rows)
            withContext(Dispatchers.IO) {
                fs.writeSessionText(HistoryExporter.historyPath(conv), md)
                fs.writeSessionText(HistoryExporter.metaPath(conv), meta)
                val back = fs.readSessionText(HistoryExporter.historyPath(conv))
                if (hashOf(back) != hashOf(md)) {
                    throw IllegalStateException("history.md verification failed after writing.")
                }
            }
            storeHash(conversationId, hashOf(md))
            true
        }.getOrDefault(false)
    }

    /**
     * Tạo thư mục session ở lần gửi đầu tiên (như FPilot): nếu chưa có file
     * session thì xuất trạng thái hiện tại của Room (chỉ gồm các lượt đã xong).
     * Đã có file thì không làm gì.
     */
    suspend fun ensureSession(conversationId: String) {
        val conv = db.conversations().get(conversationId) ?: return
        val exists = runCatching {
            withContext(Dispatchers.IO) { fs.sessionExists(HistoryExporter.historyPath(conv)) }
        }.getOrDefault(false)
        if (!exists) export(conversationId)
    }

    /**
     * File có đổi ngoài app kể từ lần sync/ghi gần nhất không — dùng để chặn
     * regenerate khi xung đột (giữ bản sửa ngoài, hủy đáp án mới). Chưa có
     * baseline (chưa sync/ghi lần nào) thì coi như không đổi.
     */
    suspend fun hasExternalChange(conversationId: String): Boolean {
        val conv = db.conversations().get(conversationId) ?: return false
        val stored = storedHash(conversationId) ?: return false
        val current = runCatching {
            withContext(Dispatchers.IO) { fs.readSessionText(HistoryExporter.historyPath(conv)) }
        }.getOrNull() ?: return false
        return hashOf(current) != stored
    }
}
