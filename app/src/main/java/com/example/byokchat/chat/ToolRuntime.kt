package com.example.byokchat.chat

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.ToolDef
import com.example.byokchat.tools.ParsedTool

data class ToolOutcome(
    val uid: String,
    val resultText: String,
    val isError: Boolean = false,
)

/** Cầu nối giữa vòng chat và các công cụ. */
interface ToolRuntime {
    /**
     * Danh sách công cụ đưa cho model.
     * @param exposed null = đường "legacy" (CHỈ khi chưa chọn playground folder —
     *   fs == null): mọi tool Settings cho phép, tên/mô tả gốc. Không có
     *   thẻ Tools KHÔNG còn cho null nữa (xóa thẻ = 0 tool). Khác null =
     *   chỉ các tool parse được từ file tools.md của thẻ Tools, đúng thứ
     *   tự file/khối. Danh sách rỗng = 0 tool.
     *   Tool tắt ở Settings (mode OFF) vẫn được offer trong cả hai đường
     *   (quyết định 8, Đợt E); chỉ chặn lúc gọi trong [execute].
     */
    suspend fun definitions(exposed: List<ParsedTool>? = null): List<ToolDef>
    suspend fun execute(call: AiToolCall, turnId: String, exposed: List<ParsedTool>? = null): ToolOutcome
    /** The run for this turn is over: forget turn-scoped permissions ("Allow all in this turn"). */
    fun endTurn(turnId: String)
    /** UID của tool mà model gọi bằng [name] (tên trong file nếu có, không thì tên gốc). */
    fun uidForName(name: String, exposed: List<ParsedTool>? = null): String?
    /** Tên model thấy của tool [uid] (tên trong file nếu có, không thì tên gốc). */
    fun nameForUid(uid: String, exposed: List<ParsedTool>? = null): String?
    fun estimateToolTokens(): Int
    /** uid -> mô tả gốc của mọi tool, để parse tools.md đối chiếu origin_description. */
    fun originDescriptions(): Map<String, String>
}
