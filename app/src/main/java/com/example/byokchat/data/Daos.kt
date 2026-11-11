package com.example.byokchat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    /** Conversations that have at least one message (empty drafts are never listed). */
    @Query("SELECT * FROM conversations WHERE id IN (SELECT DISTINCT conversationId FROM messages) ORDER BY pinned DESC, updatedAt DESC")
    fun observeNonEmpty(): Flow<List<ConversationEntity>>

    /** Removes conversations that never received a message. */
    @Query("DELETE FROM conversations WHERE id NOT IN (SELECT DISTINCT conversationId FROM messages)")
    suspend fun deleteEmpty()

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Query("SELECT id FROM conversations")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM conversations")
    suspend fun observeAllOnce(): List<ConversationEntity>

    // Chỉ dùng để tạo mới. (Không dùng REPLACE vì sẽ xóa lan sang tin nhắn.)
    @Insert
    suspend fun insert(c: ConversationEntity)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE conversations SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: String, time: Long)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}

/** Start of the newest text message (user or AI) of a conversation, used as the preview line in the drawer. */
data class LastMessageRow(val conversationId: String, val text: String)

@Dao
interface MessageDao {
    /** One row per conversation: the newest user/AI message that has text (cut to 160 characters in SQL). */
    @Query(
        "SELECT m.conversationId AS conversationId, substr(m.text, 1, 160) AS text FROM messages m " +
            "WHERE m.role IN ('user', 'assistant') AND m.text != '' AND m.seq = (" +
            "SELECT MAX(m2.seq) FROM messages m2 WHERE m2.conversationId = m.conversationId " +
            "AND m2.role IN ('user', 'assistant') AND m2.text != '')"
    )
    fun observeLastMessages(): Flow<List<LastMessageRow>>

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY seq")
    fun observe(cid: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY seq")
    suspend fun getAll(cid: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE role != 'tool' AND status != 'streaming'")
    suspend fun allChatMessages(): List<MessageEntity>

    /** Như allChatMessages nhưng chỉ của một cuộc: để tìm kiếm nạp từng cuộc một, không nạp hết vào RAM. */
    @Query("SELECT * FROM messages WHERE conversationId = :cid AND role != 'tool' AND status != 'streaming'")
    suspend fun chatMessagesOf(cid: String): List<MessageEntity>

    @Query("SELECT MAX(seq) FROM messages WHERE conversationId = :cid")
    suspend fun maxSeq(cid: String): Long?

    /** Số lượt lớn nhất hiện có trong một cuộc (Đợt 4): lượt mới lấy số này + 1. */
    @Query("SELECT MAX(turnNo) FROM messages WHERE conversationId = :cid")
    suspend fun maxTurnNo(cid: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Xóa các dòng kết quả tool của một lượt trong một cuộc (Đợt E: đồng bộ file
     * ghi lại vòng tool từ history.meta.json nên dòng cũ phải xóa trước khi ghi lại). */
    @Query("DELETE FROM messages WHERE conversationId = :cid AND turnNo = :turnNo AND role = 'tool'")
    suspend fun deleteToolRowsOfTurn(cid: String, turnNo: Int)

    @Query("DELETE FROM messages WHERE turnId = :turnId")
    suspend fun deleteTurn(turnId: String)

    /** Cập nhật chữ của một tin AI đang stream (nhẹ hơn upsert cả dòng). */
    @Query("UPDATE messages SET text = :text WHERE id = :id")
    suspend fun updateText(id: String, text: String)

    @Query("SELECT * FROM messages WHERE conversationId = :cid AND turnId = :turnId ORDER BY seq")
    suspend fun getTurn(cid: String, turnId: String): List<MessageEntity>

    @Query("SELECT MAX(seq) FROM messages WHERE conversationId = :cid AND turnId = :turnId")
    suspend fun maxSeqOfTurn(cid: String, turnId: String): Long?

    @Query("SELECT COUNT(DISTINCT turnId) FROM messages WHERE conversationId = :cid AND seq > :seq")
    suspend fun countTurnsAfter(cid: String, seq: Long): Int

    @Query("DELETE FROM messages WHERE conversationId = :cid AND seq > :seq")
    suspend fun deleteAfter(cid: String, seq: Long)

    /** Đường dẫn ảnh (dạng JSON) của mọi tin trong một cuộc. */
    @Query("SELECT imagePaths FROM messages WHERE conversationId = :cid AND imagePaths != '[]'")
    suspend fun imagePathsOf(cid: String): List<String>

    /** Đường dẫn ảnh (dạng JSON) của mọi tin thuộc các cuộc khác. */
    @Query("SELECT imagePaths FROM messages WHERE conversationId != :cid AND imagePaths != '[]'")
    suspend fun imagePathsOutside(cid: String): List<String>

    // Khi mở lại app: tin nhắn đang dở dang do app bị tắt được đánh dấu là bị gián đoạn.
    @Query("UPDATE messages SET status = 'error', error = 'Interrupted (the app was closed while the AI was replying).' WHERE status = 'streaming'")
    suspend fun markInterrupted()
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun all(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(n: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos ORDER BY done, createdAt DESC")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos ORDER BY done, createdAt DESC")
    suspend fun all(): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun get(id: String): TodoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: TodoEntity)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun delete(id: String)
}
