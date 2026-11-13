package com.example.byokchat.tools

import com.example.byokchat.chat.ModelFacingText

/**
 * Logic thuần của AI playground nhiều folder (gốc ảo) — không phụ thuộc
 * Android nên test được bằng unit test thường trên JVM.
 *
 * Mô hình (handoff §4.4–§4.5): AI không còn thấy "một workspace" mà thấy
 * một thư mục gốc ảo chứa các folder user đã chọn. Đường dẫn AI dùng bắt
 * đầu bằng tên một folder đã chọn ("folder_01/reports/november.md");
 * đường dẫn rỗng hoặc "." là gốc ảo. Folder đã chọn đối xử như root cũ:
 * AI chỉ làm việc BÊN TRONG chúng, không xóa/sửa/đổi tên/di chuyển được
 * chính các folder đó, và không tạo gì được ở cấp gốc ảo.
 */

/**
 * Tách đường dẫn AI thành các đoạn, giữ nguyên các kiểm tra cũ của
 * WorkspaceFs.segments: chặn "..", ký tự ':' và NUL. Ném [ToolException]
 * với câu báo cho AI khi đường dẫn sai.
 */
fun parsePlaygroundSegments(path: String): List<String> {
    val p = path.trim().replace('\\', '/')
    val segs = p.split('/').filter { it.isNotEmpty() && it != "." }
    if (segs.any { it == ".." }) {
        throw ToolException("The path must not contain '..' (it cannot leave the AI playground).")
    }
    if (segs.any { it.contains(':') || it.contains('\u0000') }) {
        throw ToolException("The path contains invalid characters. Use a relative path such as 'folder_01/file.txt'.")
    }
    return segs
}

/** Vị trí của một đường dẫn AI trong mô hình gốc ảo. */
sealed interface PlaygroundPath {
    /** Đường dẫn rỗng hoặc "." — gốc ảo chứa các folder đã chọn. */
    data object VirtualRoot : PlaygroundPath
    /** Đúng một đoạn: chính một folder đã chọn. */
    data class PlaygroundFolder(val rootIndex: Int, val rootName: String) : PlaygroundPath
    /** Hai đoạn trở lên: thứ gì đó bên trong một folder đã chọn. */
    data class InsideFolder(val rootIndex: Int, val rootName: String, val rest: List<String>) : PlaygroundPath
}

/**
 * Xác định vị trí của các đoạn đường dẫn theo tên các folder đã chọn (theo
 * thứ tự thêm). Đoạn đầu khớp không phân biệt hoa/thường (tên folder là
 * duy nhất nhờ kiểm tra trùng tên lúc thêm). Đoạn đầu không khớp folder
 * nào thì ném [ToolException] kèm danh sách tên folder hiện có — không
 * bao giờ lộ đường dẫn hệ thống đầy đủ, và không đoán mò (không fallback).
 */
fun resolvePlaygroundPath(rootNames: List<String>, segments: List<String>): PlaygroundPath {
    if (segments.isEmpty()) return PlaygroundPath.VirtualRoot
    val first = segments[0]
    val idx = rootNames.indexOfFirst { it.equals(first, ignoreCase = true) }
    if (idx < 0) throw ToolException(ModelFacingText.unknownPlaygroundFolder(first, rootNames))
    val name = rootNames[idx]
    return if (segments.size == 1) PlaygroundPath.PlaygroundFolder(idx, name)
    else PlaygroundPath.InsideFolder(idx, name, segments.drop(1))
}

/** Tên folder mới có trùng (không phân biệt hoa/thường) với folder đã có không. */
fun playgroundNameConflict(existingNames: List<String>, newName: String): Boolean =
    existingNames.any { it.equals(newName, ignoreCase = true) }

/** Các thao tác AI có thể yêu cầu, theo ma trận §4.5 của handoff. */
enum class PlaygroundOp {
    READ, READ_FILE, WRITE_FILE, MAKE_DIR, DELETE, RENAME,
    MOVE_SOURCE, MOVE_DEST, COPY_SOURCE, COPY_DEST, ZIP_SOURCE, ZIP_DEST, UNZIP_DEST,
}

/**
 * Ma trận thao tác §4.5: thao tác nào được phép ở cấp nào.
 * - Gốc ảo: chỉ đọc (liệt kê các folder đã chọn).
 * - Folder đã chọn: chỉ đọc, và giải nén VÀO gốc của nó (unzip dest);
 *   không xóa/đổi tên/di chuyển/copy/zip chính nó, không tạo gì ngang hàng.
 * - Bên trong folder: mọi thao tác đều được (ghi còn phải qua kiểm tra
 *   "Allowed write subfolder" riêng — xem [underWriteSubfolder]).
 */
fun isPlaygroundOpAllowed(op: PlaygroundOp, location: PlaygroundPath): Boolean = when (location) {
    is PlaygroundPath.VirtualRoot -> op == PlaygroundOp.READ
    is PlaygroundPath.PlaygroundFolder -> op == PlaygroundOp.READ || op == PlaygroundOp.UNZIP_DEST
    is PlaygroundPath.InsideFolder -> true
}

/**
 * Đường dẫn ghi có nằm trong thư mục con cho phép (Allowed write
 * subfolder) của một folder playground không.
 * - `subfolder` rỗng = ghi được ở mọi nơi bên trong các folder (cấp gốc
 *   ảo vẫn bị chặn bởi kiểm tra thao tác riêng, không phải ở đây).
 * - Không rỗng: đoạn 0 phải là tên folder, các đoạn tiếp theo phải bắt
 *   đầu bằng `subfolder` (vd "folder_01/notes/x.md" đạt khi sub = "notes";
 *   "notes/x.md" — thiếu tên folder — và "folder_01/other/x.md" không đạt).
 * - Hàm này khoan dung (không bao giờ ném lỗi): đường dẫn chứa ".." thì
 *   trả về true để phần kiểm tra đường dẫn của công cụ báo lỗi đó thay vì
 *   báo sai thành lỗi subfolder.
 */
fun underWriteSubfolder(path: String, subfolder: String): Boolean {
    val sub = subfolder.trim().replace('\\', '/').trim('/').replace(Regex("/+"), "/")
    if (sub.isEmpty()) return true
    val segs = path.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    if (segs.any { it == ".." }) return true
    if (segs.size < 2) return false
    val subSegs = sub.split('/')
    val rest = segs.drop(1)
    return rest.size >= subSegs.size && rest.take(subSegs.size) == subSegs
}
