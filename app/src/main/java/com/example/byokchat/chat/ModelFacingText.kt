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

    /** Tool bị tắt ở Settings (mode OFF) nhưng model vẫn gọi (quyết định 8, Đợt E):
     * model được báo rõ là tool đang tắt, để tự nói với user thay vì tưởng là không có tool. */
    fun toolTurnedOff(name: String): String =
        "The tool '$name' is turned off in the app settings, so it cannot run. Tell the user they can turn it on in Settings → Manage tools."

    /** AI chỉ được ghi file dạng text (quyết định 14): file nhị phân không ghi được. */
    fun notTextFile(path: String): String =
        "The AI can only write text files. '$path' is not a text file, so this write was not done."

    /** Đường dẫn ghi nằm ngoài thư mục con cho phép (Đợt E, quyết định 6). */
    fun outsideWriteSubfolder(path: String, subfolder: String): String =
        "The AI can only write inside '$subfolder/'. '$path' is outside that folder, so this write was not done."

    /** Tham số gọi tool không phải object JSON. */
    const val ARGS_NOT_OBJECT = "The arguments must be a JSON object."

    /** Tham số gọi tool không phải JSON hợp lệ. */
    fun invalidArgsJson(detail: String?): String =
        "The arguments are not valid JSON: ${detail?.lineSequence()?.firstOrNull()}"

    /** Không dựng được bản xem trước khi hỏi user (mode ASK). */
    fun previewFailed(detail: String?): String = "(Could not build a preview: $detail)"

    /** Tool chạy lỗi. */
    fun toolError(detail: String): String = "Error while running the tool: $detail"

    /** Kết quả tool cũ trong lịch sử đã rút gọn thành một dòng (thẻ History, Đợt C). */
    fun compactedToolResult(toolName: String, chars: Int, turn: Int): String =
        "[Shortened result: $toolName, $chars characters, turn T$turn. Call the tool again for the full result.]"
}
