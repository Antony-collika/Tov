package com.example.byokchat.tools

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.WorkspaceDocumentFile
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

    private fun documentRoot(uriString: String): DocumentFile? =
        runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(uriString)) }.getOrNull()

    private fun folderName(uriString: String): String {
        val uri = Uri.parse(uriString)
        return runCatching { documentRoot(uriString)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/') }
                .getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Folder"
    }

    private fun hasAccess(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        val granted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!granted) return false
        val root = documentRoot(uriString) ?: return false
        return runCatching { root.exists() && root.isDirectory && root.canWrite() }.getOrDefault(false)
    }

    private fun computeAccess(): Boolean {
        val names = settings.workspaceUris.value.map(::folderName)
        if (names.map { it.lowercase() }.distinct().size != names.size) return false
        return settings.workspaceUris.value.any(::hasAccess)
    }

    fun isFolderAccessible(uriString: String): Boolean = hasAccess(uriString)

    fun rootNames(): List<String> = settings.workspaceUris.value.map(::folderName)

    /** Remove the selected mount name before applying a path policy relative to each physical folder. */
    fun pathInsideSelectedFolder(path: String): String {
        // Do not validate here: write-policy checks run before the tool's
        // exception boundary. Unsafe segments are rejected by the filesystem
        // resolver when the tool actually executes.
        val parts = path.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        val first = parts.firstOrNull()
        return (if (first != null && first in rootNames()) parts.drop(1) else parts).joinToString("/")
    }

    fun rootName(): String? = settings.workspaceUris.value.firstOrNull()?.let(::folderName)

    /** Paths shown to the user for all selected playground folders. */
    fun displayPaths(): List<String> = settings.workspaceUris.value.map { "./" + folderName(it) }

    /** Backward-compatible display helper for callers that only need one path. */
    fun displayPath(): String? = displayPaths().firstOrNull()

    /**
     * Đợt 8: đường dẫn tương đối trong workspace của một document user vừa
     * chọn bằng hộp chọn file của hệ thống (SAF OpenDocument). So bằng
     * document ID: document nằm trong cây workspace thì ID của nó bắt đầu
     * bằng ID của cây + "/". Null = file nằm ngoài workspace, hoặc chưa
     * chọn workspace. Đợt fix 11/11/2026 (I1): chỉ còn dùng để ƯU TIÊN lưu
     * đường dẫn tương đối cho file trong workspace (dễ đọc trong bố cục đã
     * lưu, quyền đọc đã có sẵn từ quyền của cả cây) — KHÔNG còn là điều
     * kiện bắt buộc: file ngoài workspace thì lưu URI (xem readCardFile).
     */
    fun relativePathForDocument(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        for (raw in settings.workspaceUris.value) {
            val treeUri = Uri.parse(raw)
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
            if (docId == treeId) return folderName(raw)
            val prefix = "$treeId/"
            if (docId.startsWith(prefix)) {
                val relative = docId.removePrefix(prefix)
                return folderName(raw) + if (relative.isEmpty()) "" else "/$relative"
            }
        }
        return null
    }

    /**
     * File thẻ trỏ tới có phải URI tài liệu ngoài workspace không (đợt fix
     * 11/11/2026, I1): thẻ lưu URI dạng "content://..." khi user chọn file ở
     * BẤT CỨ ĐÂU trên máy (không chỉ trong workspace); đường dẫn tương đối
     * (không có scheme content) là file trong workspace — thẻ cũ vẫn dùng
     * dạng này, không migrate, không đổi.
     */
    fun isExternalCardFile(source: String): Boolean {
        val uri = runCatching { Uri.parse(source) }.getOrNull() ?: return false
        return uri.scheme == "content"
    }

    /**
     * Giữ quyền đọc lâu dài cho file user vừa chọn bằng hộp chọn hệ thống
     * (I1). Trả về false khi nhà cung cấp không cho giữ quyền — lúc đó
     * không nên lưu URI (quyền sẽ mất khi khởi động lại app).
     */
    fun takeCardFilePermission(uri: Uri): Boolean = runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrDefault(false)

    /** Tên hiển thị đã tra được của file ngoài, để không hỏi lại nhà cung cấp. */
    private val cardFileNames = ConcurrentHashMap<String, String>()

    /**
     * Đọc nội dung file thẻ trỏ tới (I1): URI ngoài đọc qua ContentResolver
     * (xem readDocText — file nhị phân / quá lớn thì ném ToolException rõ
     * ràng, đường gửi chặn lại và báo user, không tự fallback); đường dẫn
     * tương đối đọc trong workspace như cũ (thẻ cũ). Chạy chặn, gọi từ
     * luồng nền.
     */
    fun readCardFile(source: String, maxBytes: Long = 5_000_000): String {
        if (isExternalCardFile(source)) {
            val uri = Uri.parse(source)
            val doc = DocumentFile.fromSingleUri(ctx, uri)
                ?: throw ToolException("Could not read the file.")
            return readDocText(doc, doc.name ?: source, maxBytes)
        }
        // Older Tools/Prompt card files stored paths relative to the former
        // single workspace root. Keep those paths working by resolving an
        // unqualified path against the first selected mount; new paths should
        // include their mount name explicitly.
        val parts = segments(source)
        val roots = rootNames()
        val first = parts.firstOrNull()
        val path = if (roots.isEmpty() || (first != null && first in roots)) {
            source
        } else {
            (listOfNotNull(roots.firstOrNull()) + parts).joinToString("/")
        }
        return readText(path, maxBytes)
    }

    /**
     * Tên hiển thị của file thẻ trỏ tới (I1): URI ngoài thì lấy tên file
     * thật từ nhà cung cấp (đã tra được thì dùng lại); không lấy được thì
     * lấy đoạn cuối của URI đã giải mã; đường dẫn tương đối thì lấy tên
     * file. Chạy chặn (hỏi nhà cung cấp), gọi từ luồng nền.
     */
    fun cardFileDisplayName(source: String): String {
        cardFileNames[source]?.let { return it }
        if (isExternalCardFile(source)) {
            val uri = Uri.parse(source)
            val name = runCatching { DocumentFile.fromSingleUri(ctx, uri)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
            if (name != null) {
                cardFileNames[source] = name
                return name
            }
            return (Uri.decode(uri.lastPathSegment ?: source) ?: source).substringAfterLast('/').ifEmpty { source }
        }
        return source.substringAfterLast('/').ifEmpty { source }
    }

    /** Add a SAF tree as a direct child of the virtual workspace root. */
    fun onFolderPicked(uri: Uri) {
        val raw = uri.toString()
        if (raw in settings.workspaceUris.value) {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            refresh()
            return
        }
        val selectedName = folderName(raw)
        val conflict = hasWorkspaceFolderNameConflict(
            settings.workspaceUris.value.map(::folderName),
            selectedName,
        )
        if (conflict) {
            throw IllegalArgumentException(
                "A folder named '$selectedName' already exists in Select AI playground. Choose a folder with a different name."
            )
        }
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        settings.addWorkspaceUri(raw)
        refresh()
    }

    fun removeWorkspaceFolder(uriString: String) {
        settings.removeWorkspaceUri(uriString)
        runCatching {
            resolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        refresh()
    }

    /** Clear all selected roots. Retained for callers from older screens. */
    fun clear() {
        settings.workspaceUris.value.toList().forEach(::removeWorkspaceFolder)
        refresh()
    }

    // ---------- Thư mục Session storage (Đợt 7) ----------
    // Gương của onFolderPicked/clear/displayPath ở trên, nhưng cho thư mục
    // Session storage riêng (setting session_storage_uri, Đợt E). Chưa chọn
    // thì KHÔNG có session root — không tự lấy workspace làm thay (đợt fix
    // 11/11/2026, C2: Duy chưa bao giờ nói chưa chọn thì lấy cùng thư mục;
    // history.md mà mặc định nằm trong Playground thì mâu thuẫn nguyên tắc
    // tách vai trò). Các chỗ đọc/ghi session đã bọc runCatching nên thiếu
    // thư mục chỉ nghĩa là không có file session — chat vẫn chạy.

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
        if (!refresh()) return "No access to any selected AI playground folder."
        val accessibleNames = settings.workspaceUris.value.filter(::hasAccess).map(::folderName)
        for (name in accessibleNames) {
            val path = "$name/.byok_test.txt"
            try {
                val content = "ok-" + System.currentTimeMillis()
                writeText(path, content, createParents = false)
                val back = readText(path)
                requireFile(path).delete()
                if (back == content) return "Check passed: the app can read and write in './$name'."
            } catch (e: Exception) {
                return "Check failed in './$name': ${e.message}"
            }
        }
        return "Check failed: no selected folder could be tested."
    }

    // ---------- Đường dẫn ----------

    fun root(): DocumentFile {
        val selectedNames = settings.workspaceUris.value.map(::folderName)
        if (selectedNames.map { it.lowercase() }.distinct().size != selectedNames.size) {
            throw ToolException("Two selected AI playground folders have the same name. Remove one before using file tools.")
        }
        val mounts = settings.workspaceUris.value.mapNotNull { raw ->
            if (!hasAccess(raw)) return@mapNotNull null
            val treeUri = Uri.parse(raw)
            val delegate = documentRoot(raw) ?: return@mapNotNull null
            WorkspaceDocumentFile.mount(folderName(raw), treeUri, delegate)
        }
        if (mounts.isEmpty()) throw ToolException("No accessible AI playground folder has been chosen.")
        return WorkspaceDocumentFile.root(mounts)
    }

    fun isMountedRoot(doc: DocumentFile): Boolean = doc is WorkspaceDocumentFile && doc.isWorkspaceMount

    private fun rootFrom(uriString: String?, label: String): DocumentFile {
        val uri = uriString ?: throw ToolException("No $label folder has been chosen.")
        return DocumentFile.fromTreeUri(ctx, Uri.parse(uri)) ?: throw ToolException("Could not open the $label folder.")
    }

    /**
     * Thư mục Session storage (Đợt E): chỉ dùng thư mục riêng đã chọn.
     * Chưa chọn thì ném lỗi (không tự lấy workspace làm thay — đợt fix
     * 11/11/2026, C2). Dùng [sessionRootOrNull] ở những chỗ chỉ cần biết
     * có hay không.
     */
    fun sessionRoot(): DocumentFile =
        rootFrom(settings.sessionStorageUri.value, "session storage")

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

    fun nameOf(d: DocumentFile): String =
        if (d is WorkspaceDocumentFile && d.isWorkspaceMount) d.mountName else meta[d.uri.toString()]?.name ?: d.name.orEmpty()
    fun isDir(d: DocumentFile): Boolean =
        d is WorkspaceRootDocumentFile || d is WorkspaceMountDocumentFile || (meta[d.uri.toString()]?.isDir ?: d.isDirectory)
    fun sizeOf(d: DocumentFile): Long = meta[d.uri.toString()]?.size ?: d.length()
    fun modifiedOf(d: DocumentFile): Long = meta[d.uri.toString()]?.modified ?: d.lastModified()
    fun mimeOf(d: DocumentFile): String? = meta[d.uri.toString()]?.mime ?: d.type

    private fun findMountFor(documentUri: Uri): WorkspaceDocumentFile? {
        val docId = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull() ?: return null
        return settings.workspaceUris.value.firstNotNullOfOrNull { raw ->
            val treeUri = Uri.parse(raw)
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return@firstNotNullOfOrNull null
            if (docId == treeId || docId.startsWith("$treeId/")) {
                val delegate = documentRoot(raw) ?: return@firstNotNullOfOrNull null
                WorkspaceDocumentFile.mount(folderName(raw), treeUri, delegate)
            } else null
        }
    }

    /** Liệt kê thư mục: một truy vấn lấy đủ thông tin; nếu lỗi thì rơi về cách cũ (chậm nhưng chắc chắn). */
    fun children(dir: DocumentFile): List<DocumentFile> {
        if (dir is WorkspaceDocumentFile && dir.isWorkspaceRoot) return dir.listFiles().toList()
        val mount = when (dir) {
            is WorkspaceDocumentFile -> dir
            else -> findMountFor(dir.uri)
        } ?: return childrenSlow(dir)
        val treeUri = mount.treeUri
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

    /** Mở file để ghi thêm vào cuối ("wa"): dùng cho append history.md/meta. */
    fun openAppend(doc: DocumentFile): OutputStream =
        resolver.openOutputStream(doc.uri, "wa") ?: throw ToolException("Could not write '${doc.name}'.")

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

    /**
     * Ghi thêm vào cuối file (tạo file nếu chưa có). Dùng cho append history
     * (Đợt fix 12/11/2026): chỉ ghi phần mới, không ghi lại toàn bộ.
     */
    fun appendTextUnder(root: DocumentFile, path: String, text: String) {
        val segs = segments(path)
        if (segs.isEmpty()) throw ToolException("Missing file name.")
        val parent = ensureDir(segs.dropLast(1), root = root)
        val name = segs.last()
        var doc = parent.findFile(name)
        if (doc != null && doc.isDirectory) throw ToolException("'${display(path)}' is a folder.")
        if (doc == null) doc = createFileNamed(parent, name)
        openAppend(doc).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        clearMeta()
    }

    /** Ghi thêm vào cuối file trong Session storage (Đợt E). */
    fun appendSessionText(path: String, text: String) = appendTextUnder(sessionRoot(), path, text)

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
        /** Current tool job, used by long-running filesystem operations for cancellation. */
        val cancelJob = ThreadLocal<Job?>()
    }
}

/** Folder aliases in the virtual workspace must be unique regardless of case. */
internal fun hasWorkspaceFolderNameConflict(existingNames: List<String>, candidate: String): Boolean =
    existingNames.any { it.equals(candidate, ignoreCase = true) }
