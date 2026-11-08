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

    @Query("UPDATE conversations SET providerId = :providerId, model = :model WHERE id = :id")
    suspend fun setModel(id: String, providerId: String, model: String)

    @Query("UPDATE conversations SET systemPrompt = :prompt WHERE id = :id")
    suspend fun setSystemPrompt(id: String, prompt: String)

    @Query("UPDATE conversations SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: String, time: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY seq")
    fun observe(cid: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY seq")
    suspend fun getAll(cid: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE role != 'tool' AND status != 'streaming'")
    suspend fun allChatMessages(): List<MessageEntity>

    @Query("SELECT MAX(seq) FROM messages WHERE conversationId = :cid")
    suspend fun maxSeq(cid: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE turnId = :turnId")
    suspend fun deleteTurn(turnId: String)

    // Khi mở lại app: tin nhắn đang dở dang do app bị tắt được đánh dấu là bị gián đoạn.
    @Query("UPDATE messages SET status = 'error', error = 'Bị gián đoạn (ứng dụng đã bị đóng khi AI đang trả lời).' WHERE status = 'streaming'")
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
