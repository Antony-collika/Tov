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
    /**
     * Số lượt ổn định, không dùng lại (Đợt 4): gán khi tạo lượt mới bằng
     * (số lớn nhất hiện có trong cuộc trò chuyện + 1), chỉ tăng không giảm.
     * Tin assistant và kết quả tool dùng chung số của tin user cùng lượt.
     * Hàng cũ (trước migration 4→5) được điền lại theo thứ tự lượt.
     */
    @ColumnInfo(defaultValue = "0") val turnNo: Int = 0,
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
    /**
     * Chữ ký suy luận của Gemini cho phần chữ của tin assistant này (Đợt fix
     * 12/11/2026): gửi lại nguyên vẹn ở request sau để giữ mạch suy luận.
     * Chỉ Gemini dùng; chữ ký của lệnh gọi tool nằm trong AiToolCall.signature.
     */
    val thoughtSignature: String? = null,
    /**
     * Suy luận đọc được của tin assistant này (Đợt provider 12/11/2026):
     * Responses thì là tóm tắt suy luận, Custom (máy chủ tương thích, vd
     * DeepSeek) thì là reasoning_content. Hiện ở khối "Suy luận" + Preview.
     */
    val reasoningText: String? = null,
    /**
     * Dữ liệu gửi lại nguyên vẹn ở request sau (Đợt provider 12/11/2026),
     * dạng JSON: Responses thì là mảng output items (kèm encrypted_content).
     * Custom: null (gửi lại dùng reasoningText làm reasoning_content).
     * Dữ liệu cũ của hãng đã bỏ ("anthropic") thì app bỏ qua khi đọc,
     * không gửi lại.
     */
    val reasoningReplay: String? = null,
    /** Hãng đã sinh ra suy luận của tin này ("openai" / "custom" / "deepseek" — "deepseek" là dữ liệu cũ, nay DeepSeek dùng qua Custom; "anthropic" là dữ liệu cũ, hãng đó đã bỏ). */
    val reasoningProvider: String? = null,
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

/**
 * Một bài trong "Recently played" của một cuộc trò chuyện (2026-11-14):
 * chỉ lưu nguồn + khóa phát lại + tên hiển thị — KHÔNG BAO GIỜ lưu stream
 * URL (link YouTube có hạn dùng, phát lại phải resolve lại link mới từ khóa).
 * `position`: 0 = mới nhất; đọc theo position tăng dần.
 * Xóa cuộc trò chuyện thì các hàng này xóa theo (CASCADE).
 */
@Entity(
    tableName = "recently_played",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId")],
)
data class RecentlyPlayedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    /** Tên enum PlayedSource (LOCAL / YOUTUBE / SOUNDCLOUD). */
    val source: String,
    /** Khóa phát lại: đường dẫn playground (local), video ID (YouTube), link track (SoundCloud). */
    val replayKey: String,
    val name: String,
    /** Vị trí trong danh sách: 0 = mới nhất. */
    val position: Int,
)

private val listSer = ListSerializer(String.serializer())

/** Giải mã danh sách đường dẫn ảnh (dạng JSON) thành List; lỗi thì trả về rỗng. */
fun decodeImages(json: String): List<String> =
    runCatching { Json.decodeFromString(listSer, json) }.getOrDefault(emptyList())

fun MessageEntity.images(): List<String> = decodeImages(imagePaths)

fun encodeImages(paths: List<String>): String = Json.encodeToString(listSer, paths)
