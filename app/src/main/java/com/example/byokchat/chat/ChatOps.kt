package com.example.byokchat.chat

import androidx.room.withTransaction
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.MessageEntity
import java.util.UUID

data class BranchResult(val conversationId: String, val turnMap: Map<String, String>)

/**
 * Đợt provider 12/11/2026 (quyết định của Duy — KHÓA CỨNG thay vì branch,
 * vì cho sửa từ mọi nơi, mọi lượt khiến code phải sửa đủ thứ): khi user sửa
 * thứ sẽ phá vỡ mạch suy luận của Claude thì app CHẶN HẲN và giải thích rõ
 * lý do bằng lời dễ hiểu (không dùng thuật ngữ nội bộ như "niêm phong" hay
 * "chữ ký"). Muốn đổi thì mở cuộc mới. Chỉ chặn khi đủ 3 điều kiện: cuộc có
 * niêm phong, provider hiện tại là Claude, thinking đang bật.
 */
const val THINKING_SEAL_BLOCK_MESSAGE =
    "This chat already has Claude's thinking saved with it, so earlier messages can't be changed — it would break the thinking. " +
        "To change something, start a new chat."

/** Như trên, cho trường hợp sửa thẻ (System/Tools/Free) — thẻ lưu chung cho mọi cuộc. */
const val THINKING_SEAL_CARD_BLOCK_MESSAGE =
    "This chat already has Claude's thinking saved with it, so this card can't be changed — it would break the thinking. " +
        "To change it, start a new chat and edit the card there."

/** Sửa ngoài chạm vào lịch sử đã niêm phong (phương án A): không áp, file đã ghi lại từ Room để khôi phục. */
const val THINKING_SEAL_EXTERNAL_REJECTED_MESSAGE =
    "Your edit to history.md had no effect — this chat already has Claude's thinking saved with it, so earlier messages can't be changed. " +
        "The file was restored to match the app."

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
        stripThinkingSeal(r.conversationId)
        return r
    }

    /**
     * Bỏ khối thinking đã niêm phong ở mọi tin AI của Claude trong một cuộc
     * (reasoningReplay + reasoningProvider) — dùng khi rẽ nhánh sang cuộc
     * mới mà lịch sử đã đổi (sửa/xóa/tạo lại), vì khối cũ gắn với lịch sử
     * cũ nên gửi lại sẽ lỗi 400. Chữ suy luận để đọc (reasoningText) thì
     * giữ nguyên.
     */
    suspend fun stripThinkingSeal(conversationId: String) {
        val stripped = db.messages().getAll(conversationId)
            .filter { it.reasoningProvider == "anthropic" && it.reasoningReplay != null }
            .map { it.copy(reasoningReplay = null, reasoningProvider = null) }
        if (stripped.isNotEmpty()) db.messages().upsertAll(stripped)
    }

    /**
     * Rẽ nhánh để sửa một tin trong cuộc có thinking của Claude (Đợt
     * provider 12/11/2026). LƯU Ý: từ quyết định KHÓA CỨNG của Duy
     * (12/11/2026) thì đường sửa trong app không gọi hàm này nữa mà chặn
     * hẳn; hàm được giữ lại phòng khi cần dùng sau.
     * Sao chép cuộc đến hết tin cần sửa (kèm các dòng tool cùng lượt nếu
     * tin là của AI), sửa chữ của tin đó trên bản sao, và bỏ khối thinking
     * đã niêm phong ở mọi tin đã sao chép. Các lượt sau tin đó không mang
     * sang cuộc mới — chúng dựa trên nội dung cũ nên không dùng được nữa.
     * Cuộc gốc giữ nguyên.
     */
    suspend fun branchForEdit(conversationId: String, messageId: String, newText: String): BranchResult {
        val all = db.messages().getAll(conversationId)
        val row = all.firstOrNull { it.id == messageId } ?: throw IllegalStateException("Message not found.")
        var last = row.seq
        for (r in all.filter { it.seq > row.seq }) {
            if (r.turnId == row.turnId && r.role == "tool") last = r.seq else break
        }
        val result = branchUpTo(conversationId, last)
        // Bản sao giữ nguyên seq của tin gốc nên tìm được theo seq.
        val copy = db.messages().getAll(result.conversationId).firstOrNull { it.seq == row.seq }
            ?: throw IllegalStateException("Message not found.")
        if (copy.text != newText) db.messages().upsert(copy.copy(text = newText, edited = true))
        stripThinkingSeal(result.conversationId)
        return result
    }

    /**
     * Rẽ nhánh để xóa một lượt trong cuộc có thinking của Claude (Đợt
     * provider 12/11/2026). LƯU Ý: như [branchForEdit] — từ quyết định
     * KHÓA CỨNG thì đường sửa trong app không gọi hàm này nữa mà chặn hẳn;
     * hàm được giữ lại phòng khi cần dùng sau. Sao chép cuộc đến hết lượt
     * TRƯỚC lượt cần xóa (lượt cần xóa và các lượt sau không mang sang —
     * chúng dựa trên nội dung sẽ xóa). Cuộc gốc giữ nguyên.
     */
    suspend fun branchBeforeTurn(conversationId: String, turnId: String): BranchResult {
        val rows = db.messages().getAll(conversationId)
        val firstSeq = rows.filter { it.turnId == turnId }.minOfOrNull { it.seq }
            ?: throw IllegalStateException("Turn not found.")
        val result = branchUpTo(conversationId, firstSeq - 1)
        stripThinkingSeal(result.conversationId)
        return result
    }
}
