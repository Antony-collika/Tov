package com.example.byokchat.ui

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.example.byokchat.container

/**
 * Hộp chọn file (SAF OpenDocument), dùng chung cho mục "File" của thẻ
 * System/Free, trình sửa thẻ Tools (đợt fix 11/11/2026, A1+A2 — theo mẫu
 * FPilot: chọn bằng hộp chọn, không gõ đường dẫn tay) và mục "Config
 * file" của màn cài đặt agent.
 * Đợt fix 11/11/2026 (I1): file có thể ở BẤT CỨ ĐÂU trên máy, đuôi bất kỳ
 * — nội dung mới là thứ được parse, playground chỉ là thư mục của AI chứ
 * không phải nơi bắt buộc chứa file. Chọn xong thì giữ quyền đọc lâu dài
 * (persistable) rồi trả về URI dạng chuỗi; giữ không được quyền thì báo
 * lỗi (không lưu URI sẽ mất quyền khi khởi động lại app). File trong
 * playground vẫn lưu bằng đường dẫn tương đối như thẻ cũ. [writeAccess]
 * = true thì giữ thêm quyền ghi lâu dài (cấu hình agent ghi lại file khi
 * lưu trong app; thẻ chỉ cần đọc nên mặc định không giữ quyền ghi).
 */
@Composable
internal fun rememberCardFilePicker(
    onPicked: (String) -> Unit,
    /** File đã chọn không dùng được (không giữ được quyền, ...). */
    onPickError: (String) -> Unit,
    writeAccess: Boolean = false,
): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val fs = LocalContext.current.container.fs
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // File trong playground thì lưu đường dẫn tương đối như thẻ cũ (dễ
        // đọc, quyền đọc đã có sẵn từ quyền của cả cây); file ngoài thì
        // giữ quyền lâu dài rồi lưu URI dạng chuỗi.
        val relative = fs.relativePathForDocument(uri)
        if (relative != null) {
            onPicked(relative)
            return@rememberLauncherForActivityResult
        }
        val kept = if (writeAccess) fs.takeCardFileReadWritePermission(uri) else fs.takeCardFilePermission(uri)
        if (!kept) {
            onPickError("Could not keep access to this file. Pick it again, or copy it into one of the playground folders.")
            return@rememberLauncherForActivityResult
        }
        onPicked(uri.toString())
    }
}
