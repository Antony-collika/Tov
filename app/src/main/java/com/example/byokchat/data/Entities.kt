package com.example.byokchat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

const val DEFAULT_TITLE = "New chat"

/** Title used by older versions of the app (before the app was switched to English). */
const val LEGACY_DEFAULT_TITLE = "Cuộc trò chuyện mới"

/** True for the placeholder title of a conversation that has not been named yet (new or legacy). */
fun isDefaultTitle(title: String): Boolean = title == DEFAULT_TITLE || title == LEGACY_DEFAULT_TITLE

/** Title to show in the UI: old Vietnamese placeholder titles are shown as "New chat". */
fun displayTitle(title: String): String = if (title == LEGACY_DEFAULT_TITLE) DEFAULT_TITLE else title

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val providerId: String,
    val model: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Id of the conversation this one was branched from (null if it is not a branch). */
    val parentId: String? = null,
    /** Pinned conversations are listed first. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
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
