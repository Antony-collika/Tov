package com.example.byokchat.tools

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.byokchat.data.SettingsRepository
import com.example.byokchat.util.humanSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream

/**
 * Mọi thao tác tệp của AI đều đi qua lớp này:
 * - chỉ dùng đường dẫn tương đối so với thư mục làm việc (workspace)
 * - không nhận ".." nên không thể ra ngoài workspace
 * - thông báo lỗi không bao giờ chứa đường dẫn đầy đủ của hệ thống
 * Các hàm ở đây chạy chặn (blocking), phải gọi từ luồng nền.
 */
class WorkspaceFs(private val ctx: Context, private val settings: SettingsRepository) {
    private val resolver: ContentResolver get() = ctx.contentResolver
    private val _accessible = MutableStateFlow(false)
    val accessible: StateFlow<Boolean> = _accessible.asStateFlow()

    // ---------- Quyền truy cập ----------

    fun refresh(): Boolean {
        val ok = computeAccess()
        _accessible.value = ok
        return ok
    }

    private fun computeAccess(): Boolean {
        val uri = settings.workspaceUri.value?.let { Uri.parse(it) } ?: return false
        val granted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!granted) return false
        val root = DocumentFile.fromTreeUri(ctx, uri) ?: return false
        return runCatching { root.exists() && root.isDirectory && root.canWrite() }.getOrDefault(false)
    }

    fun rootName(): String? =
        runCatching { settings.workspaceUri.value?.let { DocumentFile.fromTreeUri(ctx, Uri.parse(it))?.name } }.getOrNull()

    /** Gọi sau khi người dùng chọn thư mục bằng hộp chọn của hệ thống. */
    fun onFolderPicked(uri: Uri) {
        val old = settings.workspaceUri.value?.let { Uri.parse(it) }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (old != null && old != uri) {
            runCatching {
                resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        settings.setWorkspaceUri(uri.toString())
        refresh()
    }

    fun clear() {
        settings.workspaceUri.value?.let {
            runCatching {
                resolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        settings.setWorkspaceUri(null)
        refresh()
    }

    /** Thử tạo, ghi, đọc, xóa một file tạm để chắc chắn công cụ ghi đúng chỗ. */
    fun selfTest(): String {
        if (!refresh()) return "Chưa có quyền truy cập thư mục làm việc."
        return try {
            val name = ".byok_test.txt"
            writeText(name, "ok-" + System.currentTimeMillis(), createParents = false)
            val back = readText(name)
            requireFile(name).delete()
            if (back.startsWith("ok-")) "Kiểm tra thành công: app đọc và ghi được trong thư mục '${rootName() ?: ""}'."
            else "Kiểm tra thất bại: nội dung đọc lại không khớp."
        } catch (e: Exception) {
            "Kiểm tra thất bại: ${e.message}"
        }
    }

    // ---------- Đường dẫn ----------

    fun root(): DocumentFile {
        val uri = settings.workspaceUri.value ?: throw ToolException("Chưa chọn thư mục làm việc.")
        return DocumentFile.fromTreeUri(ctx, Uri.parse(uri)) ?: throw ToolException("Không mở được thư mục làm việc.")
    }

    fun segments(path: String): List<String> {
        val p = path.trim().replace('\\', '/')
        val segs = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.any { it == ".." }) {
            throw ToolException("Đường dẫn không được chứa '..' (không được ra ngoài thư mục làm việc).")
        }
        if (segs.any { it.contains(':') || it.contains('\u0000') }) {
            throw ToolException("Đường dẫn chứa ký tự không hợp lệ. Hãy dùng đường dẫn tương đối như 'thu-muc/file.txt'.")
        }
        return segs
    }

    fun display(path: String): String = segments(path).joinToString("/").ifEmpty { "." }

    fun find(path: String): DocumentFile? {
        var cur = root()
        for (seg in segments(path)) {
            if (!cur.isDirectory) return null
            cur = cur.findFile(seg) ?: return null
        }
        return cur
    }

    fun require(path: String): DocumentFile = find(path) ?: throw ToolException("Không tìm thấy '${display(path)}'.")

    fun requireDir(path: String): DocumentFile {
        val d = require(path)
        if (!d.isDirectory) throw ToolException("'${display(path)}' không phải thư mục.")
        return d
    }

    fun requireFile(path: String): DocumentFile {
        val d = require(path)
        if (d.isDirectory) throw ToolException("'${display(path)}' là thư mục, không phải file.")
        return d
    }

    fun children(dir: DocumentFile): List<DocumentFile> =
        dir.listFiles().filter { it.name != null }.sortedWith(compareBy({ !it.isDirectory }, { it.name!!.lowercase() }))

    fun ensureDir(segs: List<String>): DocumentFile {
        var cur = root()
        for (seg in segs) {
            val next = cur.findFile(seg)
            cur = when {
                next == null -> cur.createDirectory(seg) ?: throw ToolException("Không tạo được thư mục '$seg'.")
                next.isDirectory -> next
                else -> throw ToolException("'$seg' đã tồn tại và là một file, không thể dùng làm thư mục.")
            }
        }
        return cur
    }

    // ---------- Đọc / ghi ----------

    fun openIn(doc: DocumentFile): InputStream =
        resolver.openInputStream(doc.uri) ?: throw ToolException("Không đọc được '${doc.name}'.")

    fun openOut(doc: DocumentFile): OutputStream =
        resolver.openOutputStream(doc.uri, "wt") ?: throw ToolException("Không ghi được '${doc.name}'.")

    private fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8000)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return true
        return false
    }

    fun readDocText(doc: DocumentFile, label: String, maxBytes: Long = 5_000_000): String {
        val len = doc.length()
        if (len > maxBytes) throw ToolException("File '$label' quá lớn (${humanSize(len)}); giới hạn đọc là ${humanSize(maxBytes)}.")
        val bytes = openIn(doc).use { it.readBytes() }
        if (looksBinary(bytes)) throw ToolException("'$label' không phải file văn bản.")
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    fun readText(path: String, maxBytes: Long = 5_000_000): String = readDocText(requireFile(path), display(path), maxBytes)

    fun isTextFile(doc: DocumentFile, maxBytes: Long = 2_000_000): Boolean {
        if (doc.length() > maxBytes) return false
        return runCatching {
            openIn(doc).use { ins ->
                val buf = ByteArray(2000)
                val n = ins.read(buf)
                n <= 0 || buf.take(n).none { it.toInt() == 0 }
            }
        }.getOrDefault(false)
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun createFileNamed(parent: DocumentFile, name: String): DocumentFile {
        val created = parent.createFile(mimeFor(name), name) ?: throw ToolException("Không tạo được file '$name'.")
        if (created.name != name) {
            created.renameTo(name)
            return parent.findFile(name) ?: created
        }
        return created
    }

    fun writeText(path: String, text: String, mustNotExist: Boolean = false, createParents: Boolean = true) {
        val segs = segments(path)
        if (segs.isEmpty()) throw ToolException("Thiếu tên file.")
        val parent = if (createParents) ensureDir(segs.dropLast(1)) else requireDir(segs.dropLast(1).joinToString("/"))
        val name = segs.last()
        var doc = parent.findFile(name)
        if (doc != null && doc.isDirectory) throw ToolException("'${display(path)}' là thư mục.")
        if (doc != null && mustNotExist) throw ToolException("File '${display(path)}' đã tồn tại. Dùng công cụ ghi đè nếu muốn thay thế.")
        if (doc == null) doc = createFileNamed(parent, name)
        openOut(doc).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    // ---------- Duyệt cây ----------

    /** Duyệt đệ quy. visit trả về false để dừng sớm. */
    fun walk(dir: DocumentFile, dirPath: String, maxDepth: Int, visit: (DocumentFile, String, Int) -> Boolean): Boolean =
        walkInner(dir, dirPath, maxDepth, 0, visit)

    private fun walkInner(dir: DocumentFile, dirPath: String, maxDepth: Int, depth: Int, visit: (DocumentFile, String, Int) -> Boolean): Boolean {
        for (child in children(dir)) {
            val name = child.name ?: continue
            val p = if (dirPath.isEmpty()) name else "$dirPath/$name"
            if (!visit(child, p, depth)) return false
            if (child.isDirectory && depth < maxDepth) {
                if (!walkInner(child, p, maxDepth, depth + 1, visit)) return false
            }
        }
        return true
    }

    fun countItems(dir: DocumentFile, limit: Int = 5000): Int {
        var n = 0
        walk(dir, "", Int.MAX_VALUE) { _, _, _ -> n++; n < limit }
        return n
    }

    // ---------- Sao chép / di chuyển / xóa ----------

    /** Xác định nơi đặt đích: nếu dest là thư mục có sẵn thì đặt bên trong, ngược lại dest là đường dẫn mới. */
    fun resolveTarget(srcPath: String, destPath: String): Triple<DocumentFile, String, List<String>> {
        val srcSegs = segments(srcPath)
        if (srcSegs.isEmpty()) throw ToolException("Không thể thao tác trên thư mục gốc của workspace.")
        val destSegs = segments(destPath)
        val existing = find(destPath)
        val finalSegs: List<String>
        val parentSegs: List<String>
        if (existing != null && existing.isDirectory && destSegs.isNotEmpty()) {
            finalSegs = destSegs + srcSegs.last()
            parentSegs = destSegs
        } else if (destSegs.isEmpty()) {
            finalSegs = listOf(srcSegs.last())
            parentSegs = emptyList()
        } else {
            finalSegs = destSegs
            parentSegs = destSegs.dropLast(1)
        }
        if (find(finalSegs.joinToString("/")) != null) {
            throw ToolException("Đích '${finalSegs.joinToString("/")}' đã tồn tại.")
        }
        if (finalSegs.size >= srcSegs.size && finalSegs.take(srcSegs.size) == srcSegs) {
            throw ToolException("Không thể sao chép/di chuyển một thư mục vào chính nó.")
        }
        return Triple(ensureDir(parentSegs), finalSegs.last(), finalSegs)
    }

    fun copyDoc(src: DocumentFile, destParent: DocumentFile, newName: String) {
        if (src.isDirectory) {
            val d = destParent.createDirectory(newName) ?: throw ToolException("Không tạo được thư mục '$newName'.")
            for (c in children(src)) copyDoc(c, d, c.name ?: continue)
        } else {
            val out = createFileNamed(destParent, newName)
            openIn(src).use { ins -> openOut(out).use { outs -> ins.copyTo(outs) } }
        }
    }
}
