package com.example.byokchat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.example.byokchat.container
import com.example.byokchat.tools.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Trả về hàm mở hộp chọn thư mục của hệ thống để THÊM một folder vào AI
 * playground. Kết quả trả về UI trên luồng chính: thêm được ([onPicked]),
 * trùng tên ([onConflict] kèm tên folder), hoặc lỗi ([onPickError]).
 */
@Composable
fun rememberFolderPicker(
    onPicked: () -> Unit = {},
    onConflict: (String) -> Unit = {},
    onPickError: (String) -> Unit = {},
): () -> Unit {
    val fs = LocalContext.current.container.fs
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                when (val res = fs.addFolder(uri)) {
                    is WorkspaceFs.AddFolderResult.Added -> withContext(Dispatchers.Main) { onPicked() }
                    is WorkspaceFs.AddFolderResult.NameConflict -> withContext(Dispatchers.Main) { onConflict(res.name) }
                    is WorkspaceFs.AddFolderResult.Failed -> withContext(Dispatchers.Main) { onPickError(res.message) }
                }
            }
        }
    }
    return { launcher.launch(null) }
}

/** Như rememberFolderPicker nhưng cho thư mục Session storage riêng (Đợt 7). */
@Composable
fun rememberSessionFolderPicker(onPicked: () -> Unit = {}): () -> Unit {
    val fs = LocalContext.current.container.fs
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { fs.onSessionFolderPicked(uri) }
                onPicked()
            }
        }
    }
    return { launcher.launch(null) }
}
