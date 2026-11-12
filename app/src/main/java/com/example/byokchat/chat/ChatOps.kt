package com.example.byokchat.chat

import androidx.room.withTransaction
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.MessageEntity
import java.util.UUID

data class BranchResult(val conversationId: String, val turnMap: Map<String, String>)

/** Đợt provider 12/11/2026: thông báo khi user sửa phần bị khóa vì thinking của Claude. */
const val THINKING_SEAL_MESSAGE =
    "This chat has used Claude thinking, so the system prompt, tools and earlier messages are locked — changing them would break the reasoning chain. Start a new chat to change them."

/** Các thao tác sửa / xóa / tạo lại / rẽ nhánh tin nhắn. */
class ChatOps(private val db: AppDatabase) {

    suspend fun editMessage(id: String, text: String) {
        val m = db.messages().get(id) ?: return
        if (m.text == text) return
        db.messages().upsert(m.copy(text = text, edited = true))
        db.conversations().touch(m.conversationId, System.currentTimeMillis())
    }

    /** Xóa cả lượt; trả về các dòng đã xóa để có thể Hoàn tác. */
    suspend fun deleteTurn(conversationId: String, turnId: String): List<MessageEntity> {
        val rows = db.messages().getTurn(conversationId, turnId)
        db.messages().deleteTurn(turnId)
        return rows
    }

    suspend fun restore(rows: List<MessageEntity>) {
        if (rows.isNotEmpty()) db.messages().upsertAll(rows)
    }

    /** Số lượt nằm phía sau lượt này. */
    suspend fun laterTurnCount(conversationId: String, turnId: String): Int {
        val turnMax = db.messages().maxSeqOfTurn(conversationId, turnId) ?: return 0
        return db.messages().countTurnsAfter(conversationId, turnMax)
    }

    /** Chuẩn bị tạo lại ngay trong cuộc hiện tại: xóa mọi thứ sau tin nhắn của bạn (câu trả lời cũ + các lượt sau). */
    suspend fun prepareRegenerateHere(conversationId: String, turnId: String, newUserText: String?) {
        db.withTransaction {
            val user = db.messages().getTurn(conversationId, turnId).firstOrNull { it.role == "user" }
                ?: return@withTransaction
            db.messages().deleteAfter(conversationId, user.seq)
            if (newUserText != null && newUserText != user.text) {
                db.messages().upsert(user.copy(text = newUserText, edited = true))
            }
            db.conversations().touch(conversationId, System.currentTimeMillis())
        }
    }

    /**
     * Tạo cuộc mới, sao chép từ đầu đến hết dòng có seq = lastSeq (kể cả ảnh đính kèm).
     * Cuộc gốc giữ nguyên. overrideUser = (id tin nhắn của bạn, nội dung mới) nếu cần sửa bản sao.
     */
    suspend fun branchUpTo(conversationId: String, lastSeq: Long, overrideUser: Pair<String, String>? = null): BranchResult {
        val conv = db.conversations().get(conversationId) ?: throw IllegalStateException("Conversation not found.")
        val rows = db.messages().getAll(conversationId).filter { it.seq <= lastSeq }
        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val baseTitle = conv.title.removeSuffix(" (branch)").removeSuffix(" (nhánh)")
        db.conversations().insert(
            conv.copy(id = newId, title = "$baseTitle (branch)", createdAt = now, updatedAt = now, parentId = conv.id, pinned = false)
        )
        val turnMap = rows.map { it.turnId }.distinct().associateWith { UUID.randomUUID().toString() }
        val copied = rows.map { r ->
            var m = r.copy(id = UUID.randomUUID().toString(), conversationId = newId, turnId = turnMap.getValue(r.turnId))
            if (overrideUser != null && r.id == overrideUser.first && overrideUser.second != r.text) {
                m = m.copy(text = overrideUser.second, edited = true)
            }
            m
        }
        db.messages().upsertAll(copied)
        return BranchResult(newId, turnMap)
    }

    /** Branch từ một tin nhắn (dùng cho menu của AI hoặc của bạn). Nếu là tin AI có công cụ thì lấy luôn kết quả công cụ đi kèm. */
    suspend fun branchAt(conversationId: String, messageId: String): BranchResult {
        val all = db.messages().getAll(conversationId)
        val row = all.firstOrNull { it.id == messageId } ?: throw IllegalStateException("Message not found.")
        var last = row.seq
        for (r in all.filter { it.seq > row.seq }) {
            if (r.turnId == row.turnId && r.role == "tool") last = r.seq else break
        }
        return branchUpTo(conversationId, last)
    }

    /** Tạo lại ở cuộc mới: sao chép đến hết tin nhắn của bạn ở lượt đó (có thể kèm nội dung sửa). */
    suspend fun branchForRegenerate(conversationId: String, turnId: String, newUserText: String?): BranchResult {
        val user = db.messages().getAll(conversationId).firstOrNull { it.turnId == turnId && it.role == "user" }
            ?: throw IllegalStateException("Your message for this turn was not found.")
        return branchUpTo(conversationId, user.seq, newUserText?.let { user.id to it })
    }

    /**
     * Đợt provider 12/11/2026 — "dấu niêm phong" thinking của Claude: cuộc này
     * có tin AI nào mang khối thinking cần gửi lại nguyên vẹn không (tin có
     * reasoningReplay của hãng anthropic). Khi có, system prompt, tools và
     * tin cũ không được đổi nữa (Anthropic kiểm tra chữ ký gắn với toàn bộ
     * nội dung trước đó — đổi là request sau lỗi 400).
     */
    suspend fun hasThinkingSeal(conversationId: String): Boolean =
        db.messages().getAll(conversationId).any {
            it.reasoningProvider == "anthropic" && it.reasoningReplay != null
        }

    /**
     * Rẽ nhánh khi đổi model giữa session (Đợt provider 12/11/2026): sao chép
     * toàn bộ cuộc, nhưng ở các tin AI của Claude thì BỎ khối thinking đã
     * niêm phong (reasoningReplay + reasoningProvider) — cuộc mới sẽ không
     * gửi lại khối đó cho hãng khác, và cũng không kích hoạt rẽ nhánh lần
     * nữa. Chữ suy luận để đọc (reasoningText) thì giữ nguyên.
     */
    suspend fun branchForModelSwitch(conversationId: String): BranchResult {
        val lastSeq = db.messages().maxSeq(conversationId) ?: 0
        val r = branchUpTo(conversationId, lastSeq)
        val stripped = db.messages().getAll(r.conversationId)
            .filter { it.reasoningProvider == "anthropic" && it.reasoningReplay != null }
            .map { it.copy(reasoningReplay = null, reasoningProvider = null) }
        if (stripped.isNotEmpty()) db.messages().upsertAll(stripped)
        return r
    }
}
