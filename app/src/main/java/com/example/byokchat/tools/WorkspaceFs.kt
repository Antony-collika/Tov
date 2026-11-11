package com.example.byokchat.tools

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.byokchat.data.SettingsRepository
import com.example.byokchat.util.humanSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

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

    /** Mốc (SystemClock.elapsedRealtime) của lần kiểm tra quyền gần nhất; -1 = chưa kiểm tra lần nào. */
    private var lastRefreshAt = -1L

    fun refresh(): Boolean {
        val ok = computeAccess()
        _accessible.value = ok
        lastRefreshAt = SystemClock.elapsedRealtime()
        return ok
    }

    /**
     * Chỉ kiểm tra lại quyền khi lần trước đã cũ hơn [maxAgeMs]. Dùng cho mỗi lượt gửi tới AI,
     * để không hỏi nhà cung cấp tài liệu lại mỗi lần.
     */
    fun refreshIfStale(maxAgeMs: Long = 10_000): Boolean {
        val last = lastRefreshAt
        if (last >= 0 && SystemClock.elapsedRealtime() - last < maxAgeMs) return _accessible.value
        return refresh()
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

    /**
     * The chosen folder written the way the AI sees paths: relative, e.g. "./homework". Null if no folder is chosen.
     * Blocking (reads from the document provider), so call it from a background thread.
     */
    fun displayPath(): String? {
        val raw = settings.workspaceUri.value ?: return null
        val uri = Uri.parse(raw)
        val name = rootName()?.takeIf { it.isNotBlank() }
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/') }
                .getOrNull()?.takeIf { it.isNotBlank() }
        return "./" + (name ?: "")
    }

    /**
     * Đợt 8: đường dẫn tương đối trong workspace của một document user vừa
     * chọn bằng hộp chọn file của hệ thống (SAF OpenDocument). So bằng
     * document ID: document nằm trong cây workspace thì ID của nó bắt đầu
     * bằng ID của cây + "/". Null = file nằm ngoài workspace (app chỉ trỏ
     * file .md trong workspace, như FileSection đã ghi), hoặc chưa chọn
     * workspace. Không cần xin thêm quyền: file trong cây workspace đã nằm
     * trong quyền persistable của cây đó.
     */
    fun relativePathForDocument(uri: Uri): String? {
        val treeUri = settings.workspaceUri.value?.let { Uri.parse(it) } ?: return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val prefix = "$treeId/"
        if (!docId.startsWith(prefix)) return null
        return docId.removePrefix(prefix).takeIf { it.isNotEmpty() }
    }

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

    // ---------- Thư mục Session storage (Đợt 7) ----------
    // Gương của onFolderPicked/clear/displayPath ở trên, nhưng cho thư mục
    // Session storage riêng (setting session_storage_uri, Đợt E). Chưa chọn
    // thì sessionRoot() dùng chung workspace (xem sessionRoot).

    fun sessionRootName(): String? =
        runCatching { settings.sessionStorageUri.value?.let { DocumentFile.fromTreeUri(ctx, Uri.parse(it))?.name } }.getOrNull()

    /** Tên thư mục session hiển thị kiểu "./tên": null nếu chưa chọn riêng. */
    fun displaySessionPath(): String? {
        val raw = settings.sessionStorageUri.value ?: return null
        val uri = Uri.parse(raw)
        val name = sessionRootName()?.takeIf { it.isNotBlank() }
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/') }
                .getOrNull()?.takeIf { it.isNotBlank() }
        return "./" + (name ?: "")
    }

    /** Gọi sau khi người dùng chọn thư mục Session storage bằng hộp chọn của hệ thống. */
    fun onSessionFolderPicked(uri: Uri) {
        val old = settings.sessionStorageUri.value?.let { Uri.parse(it) }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (old != null && old != uri) {
            runCatching {
                resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        settings.setSessionStorageUri(uri.toString())
    }

    fun clearSessionStorage() {
        settings.sessionStorageUri.value?.let {
            runCatching {
                resolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        settings.setSessionStorageUri(null)
    }

    /** Thử tạo, ghi, đọc, xóa một file tạm để chắc chắn công cụ ghi đúng chỗ. */
    fun selfTest(): String {
        if (!refresh()) return "No access to the workspace folder."
        return try {
            val name = ".byok_test.txt"
            writeText(name, "ok-" + System.currentTimeMillis(), createParents = false)
            val back = readText(name)
            requireFile(name).delete()
            if (back.startsWith("ok-")) "Check passed: the app can read and write in the folder '${rootName() ?: ""}'."
            else "Check failed: the content read back does not match."
        } catch (e: Exception) {
            "Check failed: ${e.message}"
        }
    }

    // ---------- Đường dẫn ----------

    fun root(): DocumentFile = rootFrom(settings.workspaceUri.value, "workspace")

    private fun rootFrom(uriString: String?, label: String): DocumentFile {
        val uri = uriString ?: throw ToolException("No $label folder has been chosen.")
        return DocumentFile.fromTreeUri(ctx, Uri.parse(uri)) ?: throw ToolException("Could not open the $label folder.")
    }

    /**
     * Thư mục Session storage (Đợt E): đã chọn thư mục riêng thì dùng nó,
     * chưa chọn thì dùng chung thư mục workspace (để xuất history vẫn chạy
     * như trước khi user chưa vào Settings chọn thêm).
     */
    fun sessionRoot(): DocumentFile =
        rootFrom(settings.sessionStorageUri.value ?: settings.workspaceUri.value, "session storage")

    fun sessionRootOrNull(): DocumentFile? = runCatching { sessionRoot() }.getOrNull()

    fun segments(path: String): List<String> {
        val p = path.trim().replace('\\', '/')
        val segs = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.any { it == ".." }) {
            throw ToolException("The path must not contain '..' (it cannot leave the workspace folder).")
        }
        if (segs.any { it.contains(':') || it.contains('\u0000') }) {
            throw ToolException("The path contains invalid characters. Use a relative path such as 'folder/file.txt'.")
        }
        return segs
    }

    fun display(path: String): String = segments(path).joinToString("/").ifEmpty { "." }

    fun find(path: String): DocumentFile? = findUnder(root(), path)

    /** Tìm trong Session storage; null nếu chưa có thư mục session hoặc không thấy. */
    fun findSession(path: String): DocumentFile? {
        val root = sessionRootOrNull() ?: return null
        return findUnder(root, path)
    }

    fun findUnder(root: DocumentFile, path: String): DocumentFile? {
        var cur = root
        for (seg in segments(path)) {
            if (!isDir(cur)) return null
            cur = childByName(cur, seg) ?: return null
        }
        return cur
    }

    fun requireUnder(root: DocumentFile, path: String): DocumentFile =
        findUnder(root, path) ?: throw ToolException("Not found: '${display(path)}'.")

    fun require(path: String): DocumentFile = requireUnder(root(), path)

    fun requireDirUnder(root: DocumentFile, path: String): DocumentFile {
        val d = requireUnder(root, path)
        if (!d.isDirectory) throw ToolException("'${display(path)}' is not a folder.")
        return d
    }

    fun requireDir(path: String): DocumentFile = requireDirUnder(root(), path)

    fun requireFileUnder(root: DocumentFile, path: String): DocumentFile {
        val d = requireUnder(root, path)
        if (d.isDirectory) throw ToolException("'${display(path)}' is a folder, not a file.")
        return d
    }

    fun requireFile(path: String): DocumentFile = requireFileUnder(root(), path)

    // ---------- Bộ nhớ đệm metadata ----------
    // Hỏi nhà cung cấp tài liệu (qua DocumentFile) mỗi lần rất chậm. Khi liệt kê thư mục, ta lấy
    // tên, loại, kích thước, ngày sửa trong MỘT truy vấn và nhớ lại ở đây. Bộ nhớ đệm bị xóa khi có ghi.

    private class Meta(val name: String, val isDir: Boolean, val size: Long, val modified: Long, val mime: String?)

    private val meta = ConcurrentHashMap<String, Meta>()

    fun clearMeta() = meta.clear()

    fun nameOf(d: DocumentFile): String = meta[d.uri.toString()]?.name ?: d.name.orEmpty()
    fun isDir(d: DocumentFile): Boolean = meta[d.uri.toString()]?.isDir ?: d.isDirectory
    fun sizeOf(d: DocumentFile): Long = meta[d.uri.toString()]?.size ?: d.length()
    fun modifiedOf(d: DocumentFile): Long = meta[d.uri.toString()]?.modified ?: d.lastModified()
    fun mimeOf(d: DocumentFile): String? = meta[d.uri.toString()]?.mime ?: d.type

    /** Liệt kê thư mục: một truy vấn lấy đủ thông tin; nếu lỗi thì rơi về cách cũ (chậm nhưng chắc chắn). */
    fun children(dir: DocumentFile): List<DocumentFile> {
        val treeUri = settings.workspaceUri.value?.let { Uri.parse(it) } ?: return childrenSlow(dir)
        return try {
            val listing = mutableListOf<Pair<DocumentFile, Meta>>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(dir.uri))
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            resolver.query(childrenUri, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    val doc = DocumentFile.fromTreeUri(ctx, DocumentsContract.buildDocumentUriUsingTree(treeUri, id)) ?: continue
                    val m = Meta(name, isDir, size, modified, mime)
                    meta[doc.uri.toString()] = m
                    listing += doc to m
                }
            }
            listing.sortedWith(compareBy({ !it.second.isDir }, { it.second.name.lowercase() })).map { it.first }
        } catch (e: Exception) {
            childrenSlow(dir)
        }
    }

    /** Cách cũ, dùng khi truy vấn nhanh không được. */
    private fun childrenSlow(dir: DocumentFile): List<DocumentFile> =
        dir.listFiles().filter { it.name != null }.sortedWith(compareBy({ !it.isDirectory }, { it.name!!.lowercase() }))

    /** Tìm mục con theo đúng tên (không phân biệt bằng cách hỏi từng mục như trước). */
    fun childByName(dir: DocumentFile, name: String): DocumentFile? = children(dir).firstOrNull { nameOf(it) == name }

    /**
     * Đảm bảo các thư mục tồn tại. [cache] (tùy chọn) nhớ thư mục đã xử lý theo đường dẫn,
     * để một lần chạy công cụ nhiều tệp không phải kiểm tra lại cùng một thư mục.
     * [root] mặc định là workspace; Session storage truyền root của nó vào.
     */
    fun ensureDir(
        segs: List<String>,
        cache: MutableMap<List<String>, DocumentFile>? = null,
        root: DocumentFile = root(),
    ): DocumentFile {
        var cur = root
        for (i in segs.indices) {
            val key = segs.subList(0, i + 1).toList()
            val cached = cache?.get(key)
            if (cached != null) {
                cur = cached
                continue
            }
            val seg = segs[i]
            val next = childByName(cur, seg)
            cur = when {
                next == null -> cur.createDirectory(seg) ?: throw ToolException("Could not create the folder '$seg'.")
                isDir(next) -> next
                else -> throw ToolException("'$seg' already exists and is a file, so it cannot be used as a folder.")
            }
            cache?.put(key, cur)
        }
        return cur
    }

    // ---------- Đọc / ghi ----------

    fun openIn(doc: DocumentFile): InputStream =
        resolver.openInputStream(doc.uri) ?: throw ToolException("Could not read '${doc.name}'.")

    fun openOut(doc: DocumentFile): OutputStream =
        resolver.openOutputStream(doc.uri, "wt") ?: throw ToolException("Could not write '${doc.name}'.")

    private fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8000)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return true
        return false
    }

    fun readDocText(doc: DocumentFile, label: String, maxBytes: Long = 5_000_000): String {
        val len = doc.length()
        if (len > maxBytes) throw ToolException("File '$label' is too large (${humanSize(len)}); the read limit is ${humanSize(maxBytes)}.")
        val bytes = openIn(doc).use { it.readBytes() }
        if (looksBinary(bytes)) throw ToolException("'$label' is not a text file.")
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    fun readText(path: String, maxBytes: Long = 5_000_000): String = readTextUnder(root(), path, maxBytes)

    fun readTextUnder(root: DocumentFile, path: String, maxBytes: Long = 5_000_000): String =
        readDocText(requireFileUnder(root, path), display(path), maxBytes)

    /** Đọc file trong Session storage (Đợt E). */
    fun readSessionText(path: String, maxBytes: Long = 5_000_000): String =
        readTextUnder(sessionRoot(), path, maxBytes)

    /** File có tồn tại trong Session storage không (Đợt E). */
    fun sessionExists(path: String): Boolean = findSession(path) != null

    /** Giờ sửa file trong Session storage, null nếu không có file (Đợt E). */
    fun sessionLastModified(path: String): Long? = findSession(path)?.let { modifiedOf(it) }

    /**
     * Đọc tệp văn bản chỉ MỘT lần. Trả về null nếu là tệp nhị phân, quá lớn, hoặc không đọc được.
     * Dùng cho tìm kiếm nội dung (trước đây phải mở tệp hai lần).
     */
    fun readTextIfText(doc: DocumentFile, label: String, maxBytes: Long): String? {
        if (sizeOf(doc) > maxBytes) return null
        val bytes = runCatching { openIn(doc).use { it.readBytes() } }.getOrNull() ?: return null
        if (looksBinary(bytes)) return null
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

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
        val created = parent.createFile(mimeFor(name), name) ?: throw ToolException("Could not create the file '$name'.")
        val result = if (created.name != name) {
            created.renameTo(name)
            parent.findFile(name) ?: created
        } else created
        clearMeta()
        return result
    }

    fun writeText(path: String, text: String, mustNotExist: Boolean = false, createParents: Boolean = true) =
        writeTextUnder(root(), path, text, mustNotExist, createParents)

    fun writeTextUnder(
        root: DocumentFile,
        path: String,
        text: String,
        mustNotExist: Boolean = false,
        createParents: Boolean = true,
    ) {
        val segs = segments(path)
        if (segs.isEmpty()) throw ToolException("Missing file name.")
        val parent = if (createParents) ensureDir(segs.dropLast(1), root = root) else requireDirUnder(root, segs.dropLast(1).joinToString("/"))
        val name = segs.last()
        var doc = parent.findFile(name)
        if (doc != null && doc.isDirectory) throw ToolException("'${display(path)}' is a folder.")
        if (doc != null && mustNotExist) throw ToolException("File '${display(path)}' already exists. Use the overwrite tool to replace it.")
        if (doc == null) doc = createFileNamed(parent, name)
        openOut(doc).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        clearMeta()
    }

    /** Ghi file trong Session storage (Đợt E). */
    fun writeSessionText(path: String, text: String) = writeTextUnder(sessionRoot(), path, text)

    // ---------- Duyệt cây ----------

    /** Duyệt đệ quy. visit trả về false để dừng sớm. */
    fun walk(dir: DocumentFile, dirPath: String, maxDepth: Int, visit: (DocumentFile, String, Int) -> Boolean): Boolean =
        walkInner(dir, dirPath, maxDepth, 0, visit)

    private fun walkInner(dir: DocumentFile, dirPath: String, maxDepth: Int, depth: Int, visit: (DocumentFile, String, Int) -> Boolean): Boolean {
        for (child in children(dir)) {
            stopIfCancelled()
            val name = child.name ?: continue
            val p = if (dirPath.isEmpty()) name else "$dirPath/$name"
            if (!visit(child, p, depth)) return false
            if (isDir(child) && depth < maxDepth) {
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
        if (srcSegs.isEmpty()) throw ToolException("Cannot operate on the workspace root folder.")
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
            throw ToolException("The destination '${finalSegs.joinToString("/")}' already exists.")
        }
        if (finalSegs.size >= srcSegs.size && finalSegs.take(srcSegs.size) == srcSegs) {
            throw ToolException("Cannot copy/move a folder into itself.")
        }
        return Triple(ensureDir(parentSegs), finalSegs.last(), finalSegs)
    }

    fun copyDoc(src: DocumentFile, destParent: DocumentFile, newName: String) {
        if (isDir(src)) {
            val d = destParent.createDirectory(newName) ?: throw ToolException("Could not create the folder '$newName'.")
            for (c in children(src)) {
                stopIfCancelled()
                copyDoc(c, d, nameOf(c))
            }
        } else {
            val out = createFileNamed(destParent, newName)
            openIn(src).use { ins -> openOut(out).use { outs -> ins.copyTo(outs) } }
        }
        clearMeta()
    }

    /** Ném CancellationException nếu người dùng vừa bấm Dừng. Các vòng lặp dài trong công cụ gọi hàm này. */
    fun stopIfCancelled() {
        cancelJob.get()?.ensureActive()
    }

    companion object {
        /**
         * Việc của công cụ đang chạy (ToolRegistry gắn vào luồng công việc). Đọc từ luồng công cụ để biết đã bị hủy chưa.
         */
        val cancelJob = ThreadLocal<Job?>()
    }
}
