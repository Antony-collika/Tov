package com.example.byokchat.chat

/**
 * Mọi chuỗi chữ mà app tự viết để gửi cho AI đều nằm ở đây, để màn
 * "Preview request" (Đợt B) hiện đúng thứ app gửi đi.
 *
 * Chỉ gồm chuỗi GỬI CHO AI (chủ yếu là kết quả tool, vì kết quả tool được
 * đưa lại vào lịch sử và gửi kèm ở vòng sau). Các câu lỗi chỉ lưu trong DB
 * hoặc hiện UI — vd "Interrupted…", "The AI returned no content." — KHÔNG
 * nằm ở đây, vì `ContextBuilder` loại các lượt lỗi khỏi request.
 */
object ModelFacingText {
    /** Kết quả tool khi user từ chối cho chạy (ToolRegistry.execute, mode ASK). */
    const val DENIED_ACTION = "The user denied this action. Do not try again unless they ask you to."

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
        "The AI can only write inside '$subfolder/' within a playground folder. '$path' is outside that folder, so this write was not done."

    /** AI dùng tên folder không có trong playground: báo kèm danh sách folder hiện có. */
    fun unknownPlaygroundFolder(name: String, folders: List<String>): String =
        "There is no playground folder named '$name'. The playground folders are: ${folders.joinToString(", ")}."

    /** AI thử tạo/sửa thứ gì đó ở cấp gốc ảo (không nằm trong folder nào). */
    fun topLevelChangeDenied(folders: List<String>): String =
        "You cannot create or change items at the top level. Use a path inside one of these folders: ${folders.joinToString(", ")}."

    /** AI thử xóa/đổi tên/di chuyển/copy/zip chính một folder đã chọn. */
    fun playgroundFolderProtected(name: String): String =
        "Folder '$name' is a playground folder and cannot be deleted, renamed or moved. You can change what is inside it."

    /** AI thử ghi vào chính một folder đã chọn (không phải file bên trong nó). */
    fun writeToPlaygroundFolder(name: String): String =
        "Folder '$name' is a playground folder. Write a file inside it instead, e.g. '$name/file.txt'."

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

    // ---------- Tool media.play (kế hoạch media player, Đợt 1) ----------
    // Các câu này viết bằng tiếng Việt theo đúng kế hoạch: AI đọc rồi báo
    // lại cho user bằng tiếng Việt (user nói tiếng Việt với AI).
    /** Tool media.play: câu trả về cho AI khi bắt đầu phát batch. */
    fun mediaPlaying(name: String, duration: String, next: List<String>): String =
        "Đang phát: $name ($duration)" + if (next.isEmpty()) "" else ", tiếp theo: ${next.joinToString(", ")}"

    /** Tool media.play: không mở được file nhạc. */
    fun mediaPlayFailed(name: String): String = "Không phát được file '$name'."

    /** Tool media.play: không có đường dẫn nào. */
    const val MEDIA_NEED_PATHS = "Cần ít nhất một đường dẫn file nhạc."

    /** Tool media.play: không tìm thấy file. */
    fun mediaNotFound(path: String): String = "Không tìm thấy file '$path'."

    /** Tool media.play: đường dẫn là thư mục. */
    fun mediaIsFolder(path: String): String = "'$path' là thư mục, không phải file nhạc."
}
