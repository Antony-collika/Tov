package com.example.byokchat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.example.byokchat.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Trả về hàm mở hộp chọn thư mục của hệ thống; app ghi nhớ quyền truy cập thư mục đã chọn. */
@Composable
fun rememberFolderPicker(onPicked: () -> Unit = {}): () -> Unit {
    val fs = LocalContext.current.container.fs
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { fs.onFolderPicked(uri) }
                onPicked()
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
