package com.example.byokchat.chat

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.data.MessageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Một file trong workspace mà model đã dùng, suy ra từ một vòng tool thành công. */
data class UsedWorkspaceFile(
    /** Đường dẫn tương đối trong workspace, như model đã gọi. */
    val path: String,
    /** Tên hiệu dụng của tool đã dùng file này. */
    val toolName: String,
    /** Lượt chứa vòng tool đó (để hiện nhãn T<n>). */
    val turnId: String,
)

/**
 * Trích danh sách file model đã đọc/ghi qua tool, từ lịch sử tool rounds
 * (Đợt 4, thẻ Files trên màn Messages — làm theo cách FPilot suy ra file từ
 * rounds, nhưng Tov không có dấu hiệu thành công trong text kết quả như
 * FPilot nên dùng cờ lỗi của dòng tool thay cho dấu hiệu đó).
 *
 * Chỉ tính lệnh gọi nào có đủ hai điều kiện:
 * - đối số chứa đường dẫn (một trong các khóa path / source / dest /
 *   output_path / dest_dir / directory, hoặc một phần tử của danh sách
 *   paths — cùng tập khóa mà ToolRegistry dùng để kiểm tra thư mục bảo vệ
 *   Tov/, trừ khóa nguồn đọc 'sources' của fs.zip vì đích ghi của nó đã
 *   nằm ở 'dest');
 * - có dòng kết quả tool khớp toolCallId và không báo lỗi (toolIsError).
 *
 * Giới hạn (best-effort, xem báo cáo Đợt 4): convert tools khi không truyền
 * output_path sẽ tự đặt tên file đầu ra từ tên file nguồn — app không suy
 * tên đó ra nên file đầu ra đó không liệt kê được; lệnh gọi thất bại hoặc
 * bị từ chối không tính; file còn tồn tại hay không do màn hình kiểm chứng
 * thêm bằng WorkspaceFs (ở đây chỉ trích đường dẫn).
 */
object ModelFiles {
    private val json = Json { ignoreUnknownKeys = true }
    private val callsSer = ListSerializer(AiToolCall.serializer())

    private val PATH_KEYS = listOf("path", "source", "dest", "output_path", "dest_dir", "directory")

    fun extractUsedFiles(rows: List<MessageEntity>): List<UsedWorkspaceFile> {
        val results = rows.filter { it.role == "tool" && it.toolCallId != null }
            .associateBy { it.toolCallId }
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<UsedWorkspaceFile>()
        for (m in rows) {
            if (m.role != "assistant" || m.toolCallsJson == null) continue
            val calls = runCatching { json.decodeFromString(callsSer, m.toolCallsJson) }.getOrNull().orEmpty()
            for (call in calls) {
                val result = results[call.id] ?: continue
                if (result.toolIsError) continue
                val args = runCatching {
                    json.parseToJsonElement(call.argsJson).jsonObject
                }.getOrNull() ?: continue
                val paths = PATH_KEYS.mapNotNull { key ->
                    (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
                } + ((args["paths"] as? JsonArray)?.mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
                }.orEmpty())
                for (path in paths) {
                    if (path.isEmpty() || !seen.add(path)) continue
                    out += UsedWorkspaceFile(path = path, toolName = call.name, turnId = m.turnId)
                }
            }
        }
        return out
    }
}
