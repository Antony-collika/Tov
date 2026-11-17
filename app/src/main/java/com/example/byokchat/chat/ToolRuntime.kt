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
     * Danh sách công cụ đưa cho model (RCP, 17/11/2026).
     * @param exposed null = không có tool nào (không còn đường "legacy"
     *   offer tất cả tool). Khác null = chỉ các tool trong danh sách đã
     *   chọn, đúng thứ tự đó; mô tả lấy từ mô tả hiệu dụng (override trong
     *   ToolPrefs thắng). Danh sách rỗng = 0 tool.
     *   Tool tắt ở Settings (mode OFF) vẫn được offer trong cả hai đường
     *   (quyết định 8, Đợt E); chỉ chặn lúc gọi trong [execute].
     */
    suspend fun definitions(exposed: List<ParsedTool>? = null): List<ToolDef>
    suspend fun execute(call: AiToolCall, turnId: String, exposed: List<ParsedTool>? = null, conversationId: String? = null): ToolOutcome
    /** The run for this turn is over: forget turn-scoped permissions ("Allow all in this turn"). */
    fun endTurn(turnId: String)
    /** UID của tool mà model gọi bằng [name] (tên trong danh sách đã chọn nếu có, không thì tên gốc). */
    fun uidForName(name: String, exposed: List<ParsedTool>? = null): String?
    /** Tên model thấy của tool [uid] (tên trong danh sách đã chọn nếu có, không thì tên gốc). */
    fun nameForUid(uid: String, exposed: List<ParsedTool>? = null): String?
    fun estimateToolTokens(): Int
    /** Tool definitions for the given uids, in registry order (not the order the uids were given), with the effective description. Only tools that are available are returned. */
    fun definitionsForUids(uids: List<String>): List<Pair<String, ToolDef>>
    /** uid -> mô tả gốc của mọi tool; 17/11/2026 — bỏ hẳn file tools.md ngoài: không còn chỗ nào trong app dùng nữa, chỉ còn ToolsMdParser (chỉ test dùng). */
    fun originDescriptions(): Map<String, String>
    /**
     * UID của mọi tool trong registry, theo thứ tự nhóm + registry mặc
     * định (17/11/2026): thẻ Tools chưa có lựa chọn nào lưu thì mặc định
     * chọn hết — xem RequestCard.effectiveSelectedToolUids.
     */
    fun allUids(): List<String>
}
