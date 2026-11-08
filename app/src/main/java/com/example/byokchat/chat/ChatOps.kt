package com.example.byokchat.chat

import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.MessageEntity
import java.util.UUID

data class BranchResult(val conversationId: String, val turnMap: Map<String, String>)

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
        val rows = db.messages().getAll(conversationId).filter { it.turnId == turnId }
        db.messages().deleteTurn(turnId)
        return rows
    }

    suspend fun restore(rows: List<MessageEntity>) {
        if (rows.isNotEmpty()) db.messages().upsertAll(rows)
    }

    /** Số lượt nằm phía sau lượt này. */
    suspend fun laterTurnCount(conversationId: String, turnId: String): Int {
        val all = db.messages().getAll(conversationId)
        val turnMax = all.filter { it.turnId == turnId }.maxOfOrNull { it.seq } ?: return 0
        return all.filter { it.seq > turnMax }.map { it.turnId }.distinct().size
    }

    /** Chuẩn bị tạo lại ngay trong cuộc hiện tại: xóa mọi thứ sau tin nhắn của bạn (câu trả lời cũ + các lượt sau). */
    suspend fun prepareRegenerateHere(conversationId: String, turnId: String, newUserText: String?) {
        val all = db.messages().getAll(conversationId)
        val user = all.firstOrNull { it.turnId == turnId && it.role == "user" } ?: return
        all.filter { it.seq > user.seq }.forEach { db.messages().deleteById(it.id) }
        if (newUserText != null && newUserText != user.text) {
            db.messages().upsert(user.copy(text = newUserText, edited = true))
        }
        db.conversations().touch(conversationId, System.currentTimeMillis())
    }

    /**
     * Tạo cuộc mới, sao chép từ đầu đến hết dòng có seq = lastSeq (kể cả ảnh đính kèm).
     * Cuộc gốc giữ nguyên. overrideUser = (id tin nhắn của bạn, nội dung mới) nếu cần sửa bản sao.
     */
    suspend fun branchUpTo(conversationId: String, lastSeq: Long, overrideUser: Pair<String, String>? = null): BranchResult {
        val conv = db.conversations().get(conversationId) ?: throw IllegalStateException("Không tìm thấy cuộc trò chuyện.")
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
        val row = all.firstOrNull { it.id == messageId } ?: throw IllegalStateException("Không tìm thấy tin nhắn.")
        var last = row.seq
        for (r in all.filter { it.seq > row.seq }) {
            if (r.turnId == row.turnId && r.role == "tool") last = r.seq else break
        }
        return branchUpTo(conversationId, last)
    }

    /** Tạo lại ở cuộc mới: sao chép đến hết tin nhắn của bạn ở lượt đó (có thể kèm nội dung sửa). */
    suspend fun branchForRegenerate(conversationId: String, turnId: String, newUserText: String?): BranchResult {
        val user = db.messages().getAll(conversationId).firstOrNull { it.turnId == turnId && it.role == "user" }
            ?: throw IllegalStateException("Không tìm thấy tin nhắn của bạn ở lượt này.")
        return branchUpTo(conversationId, user.seq, newUserText?.let { user.id to it })
    }
}
