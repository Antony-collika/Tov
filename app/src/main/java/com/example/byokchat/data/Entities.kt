package com.example.byokchat.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

const val DEFAULT_TITLE = "Cuộc trò chuyện mới"

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val providerId: String,
    val model: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Một dòng tin nhắn. Mỗi "lượt" (turn) gồm: tin nhắn của bạn + (các) tin nhắn trả lời của AI
 * + (nếu có) kết quả công cụ. Tất cả cùng một turnId để xóa/tạo lại theo lượt.
 * role: "user" | "assistant" | "tool"
 * status: "done" | "streaming" | "error"
 */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId"), Index("turnId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val turnId: String,
    val seq: Long,
    val role: String,
    val text: String,
    val edited: Boolean = false,
    val imagePaths: String = "[]",
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolUid: String? = null,
    val toolName: String? = null,
    val toolIsError: Boolean = false,
    val status: String = "done",
    val error: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey val id: String,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long,
)

private val listSer = ListSerializer(String.serializer())

fun MessageEntity.images(): List<String> =
    runCatching { Json.decodeFromString(listSer, imagePaths) }.getOrDefault(emptyList())

fun encodeImages(paths: List<String>): String = Json.encodeToString(listSer, paths)
