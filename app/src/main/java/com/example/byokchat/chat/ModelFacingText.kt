package com.example.byokchat.chat

/**
 * Mọi chuỗi chữ mà app tự viết để gửi cho AI đều nằm ở đây, để màn
 * "What the AI will see" (Đợt B) hiện đúng thứ app gửi đi.
 *
 * Chỉ gồm chuỗi GỬI CHO AI (chủ yếu là kết quả tool, vì kết quả tool được
 * đưa lại vào lịch sử và gửi kèm ở vòng sau). Các câu lỗi chỉ lưu trong DB
 * hoặc hiện UI — vd "Interrupted…", "The AI returned no content." — KHÔNG
 * nằm ở đây, vì `ContextBuilder` loại các lượt lỗi khỏi request.
 */
object ModelFacingText {
    /** Kết quả tool khi user từ chối cho chạy (ToolRegistry.execute, mode ASK). */
    const val DENIED_ACTION = "The user denied this action. Do not try again unless they ask you to."

    /** Kết quả tool rỗng, chỉ Claude mới thay bằng chuỗi này (ClaudeProvider). */
    const val NO_RESULT = "(no result)"

    /** Ghi chú khi kết quả tool bị cắt ngắn (cap, tối đa 30 000 ký tự). */
    fun truncatedNote(moreChars: Int): String = "\n…[truncated, $moreChars more characters]"

    /** AI gọi tên công cụ không có, hoặc công cụ đã tắt. */
    fun noSuchTool(name: String): String = "There is no tool named '$name', or it is turned off."

    /** Tham số gọi tool không phải object JSON. */
    const val ARGS_NOT_OBJECT = "The arguments must be a JSON object."

    /** Tham số gọi tool không phải JSON hợp lệ. */
    fun invalidArgsJson(detail: String?): String =
        "The arguments are not valid JSON: ${detail?.lineSequence()?.firstOrNull()}"

    /** Không dựng được bản xem trước khi hỏi user (mode ASK). */
    fun previewFailed(detail: String?): String = "(Could not build a preview: $detail)"

    /** Tool chạy lỗi. */
    fun toolError(detail: String): String = "Error while running the tool: $detail"
}
