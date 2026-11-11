package com.example.byokchat.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.byokchat.tools.WorkspaceFs

/**
 * Mở một file text/.md bằng app ngoài (ACTION_VIEW, text/markdown rồi
 * fallback text/plain). Dùng chung cho màn sửa thẻ (file trong workspace)
 * và màn Messages (history.md trong session storage — [inSessionStorage]
 * = true thì tìm trong Session storage thay vì workspace). Đợt fix
 * 11/11/2026 (E1): tách từ hàm cục bộ của CardEditScreen ra dùng chung.
 */
fun openFileAt(
    context: Context,
    fs: WorkspaceFs,
    path: String,
    onError: (String) -> Unit = {},
    inSessionStorage: Boolean = false,
) {
    if (path.isEmpty()) return
    try {
        val doc = if (inSessionStorage) fs.findSession(path) else fs.find(path)
        if (doc == null) {
            onError("File not found: $path")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(doc.uri, "text/markdown")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val doc = (if (inSessionStorage) fs.findSession(path) else fs.find(path)) ?: return
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(doc.uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e2: Exception) {
            Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show()
        }
    }
}
