package com.example.byokchat.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.byokchat.tools.WorkspaceFs

/**
 * Mở một file text/.md bằng app ngoài (ACTION_VIEW, text/markdown rồi
 * fallback text/plain). Dùng chung cho màn sửa thẻ (file trong playground
 * hoặc file ngoài lưu bằng URI — đợt fix 11/11/2026, I1) và màn Messages
 * (history.md trong session storage — [inSessionStorage] = true thì tìm
 * trong Session storage thay vì playground). Đợt fix 11/11/2026 (E1):
 * tách từ hàm cục bộ của CardEditScreen ra dùng chung.
 */
fun openFileAt(
    context: Context,
    fs: WorkspaceFs,
    path: String,
    onError: (String) -> Unit = {},
    inSessionStorage: Boolean = false,
) {
    if (path.isEmpty()) return
    // Đợt fix 11/11/2026 (I1): file ngoài playground lưu bằng URI
    // (content://...) — mở thẳng URI đó, không tìm trong playground.
    val externalUri = runCatching { Uri.parse(path) }.getOrNull()
        ?.takeIf { !inSessionStorage && it.scheme == "content" }
    if (externalUri != null) {
        openUri(context, externalUri)
        return
    }
    val doc = runCatching { if (inSessionStorage) fs.findSession(path) else fs.find(path) }.getOrNull()
    if (doc == null) {
        onError("File not found: $path")
        return
    }
    openUri(context, doc.uri)
}

/**
 * Mở một URI bằng app ngoài: thử text/markdown trước, không được thì
 * text/plain, vẫn không được thì báo toast (không crash, không im lặng).
 */
private fun openUri(context: Context, uri: Uri) {
    for (mime in listOf("text/markdown", "text/plain")) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            // Thử MIME tiếp theo.
        }
    }
    Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show()
}
