package com.example.byokchat.tools

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.byokchat.chat.ModelFacingText
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
 * - chỉ dùng đường dẫn tương đối so với AI playground (gốc ảo chứa các
 *   folder user đã chọn); đường dẫn bắt đầu bằng tên một folder đã chọn,
 *   đường dẫn rỗng là gốc ảo
 * - không nhận ".." nên không thể ra ngoài playground
 * - folder đã chọn không xóa/sửa/đổi tên/di chuyển được; không tạo gì được
 *   ở cấp gốc ảo (xem ma trận thao tác trong Playground.kt)
 * - thông báo lỗi không bao giờ chứa đường dẫn đầy đủ của hệ thống
 * Các hàm ở đây chạy chặn (blocking), phải gọi từ luồng nền.
 */
class WorkspaceFs(private val ctx: Context, private val settings: SettingsRepository) {
    private val resolver: ContentResolver get() = ctx.contentResolver
    private val _accessible = MutableStateFlow(false)
    val accessible: StateFlow<Boolean> = _accessible.asStateFlow()

    // ---------- Playground roots ----------

    /** Một folder user đã chọn trong playground: URI cây, tên hiển thị, và DocumentFile (null khi không truy cập được). */
    data class RootEntry(val uri: Uri, val name: String, val doc: DocumentFile?) {
        fun requireDoc(): DocumentFile = doc
            ?: throw ToolException("The playground folder '$name' is not available. Remove it and add it again.")
    }

    /** Trạng thái truy cập của một folder đã chọn, để màn Storage nêu tên folder lỗi. */
    data class RootAccess(val uri: String, val name: String, val ok: Boolean)

    /** Kết quả thêm folder (handoff §4.2). */
    sealed interface AddFolderResult {
        data object Added : AddFolderResult
        data class NameConflict(val name: String) : AddFolderResult
        data class Failed(val message: String) : AddFolderResult
    }

    /** Các folder đã chọn, theo thứ tự thêm. Tên từ DocumentFile.name, fallback từ document ID như trước. */
    fun roots(): List<RootEntry> = settings.playgroundUris.value.map { raw ->
        val uri = Uri.parse(raw)
        val doc = runCatching { DocumentFile.fromTreeUri(ctx, uri) }.getOrNull()
        val name = doc?.name?.takeIf { it.isNotBlank() }
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/') }
                .getOrNull()?.takeIf { it.isNotBlank() }
            ?: ""
        RootEntry(uri, name, doc)
    }

    fun rootNames(): List<String> = roots().map { it.name }

    /**
     * Tên xuất hiện nhiều lần (không phân biệt hoa/thường) — folder bị đổi
     * tên ở ngoài app rồi trùng với folder khác. Folder đứng trước theo
     * thứ tự thêm được dùng; màn Storage nêu cảnh báo.
     */
    fun duplicateRootNames(): List<String> {
        val names = rootNames()
        return names.filter { n -> names.count { it.equals(n, ignoreCase = true) } > 1 }
            .distinctBy { it.lowercase() }
    }

    /** Tên các folder đã chọn dạng "./tên". Chạy chặn, gọi từ luồng nền. */
    fun playgroundDisplayPaths(): List<String> = rootNames().map { "./$it" }

    /** Trạng thái truy cập của từng folder đã chọn. */
    fun accessDetails(): List<RootAccess> = roots().map { root ->
        val granted = resolver.persistedUriPermissions.any {
            it.uri == root.uri && it.isReadPermission && it.isWritePermission
        }
        val ok = granted && root.doc != null &&
            runCatching { root.doc.exists() && root.doc.isDirectory && root.doc.canWrite() }.getOrDefault(false)
        RootAccess(root.uri.toString(), root.name, ok)
    }

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

    /** Truy cập được = có ít nhất 1 folder truy cập được (đọc + ghi). Công cụ tệp vẫn chạy với các folder còn tốt. */
    private fun computeAccess(): Boolean = accessDetails().any { it.ok }

    /**
     * Thêm một folder vào playground (handoff §4.2). Chạy chặn, gọi từ luồng nền (đọc tên folder).
     * Thứ tự bắt buộc: (1) tính tên folder mới; (2) so với tên các folder đã có
     * (không phân biệt hoa/thường); (3) trùng → trả [AddFolderResult.NameConflict],
     * KHÔNG gọi takePersistableUriPermission; (4) ổn → giữ quyền, thêm vào danh sách, refresh().
     * Chọn lại đúng URI cũ cũng là trùng tên — và tuyệt đối không nhả quyền của URI đó.
     */
    fun addFolder(uri: Uri): AddFolderResult {
        val name = runCatching { DocumentFile.fromTreeUri(ctx, uri)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/') }
                .getOrNull()?.takeIf { it.isNotBlank() }
            ?: return AddFolderResult.Failed("Could not read the folder name.")
        if (playgroundNameConflict(rootNames(), name)) return AddFolderResult.NameConflict(name)
        return runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            settings.setPlaygroundUris(settings.playgroundUris.value + uri.toString())
            refresh()
            AddFolderResult.Added
        }.getOrElse { AddFolderResult.Failed(it.message ?: "Could not keep access to this folder.") }
    }

    /** Gỡ một folder khỏi playground: nhả quyền SAF của đúng folder đó, xóa khỏi danh sách, refresh(). */
    fun removeFolder(uriString: String) {
        runCatching {
            resolver.releasePersistableUriPermission(Uri.parse(uriString), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        settings.setPlaygroundUris(settings.playgroundUris.value - uriString)
        refresh()
    }

    // ---------- Thư mục Session storage (Đợt 7) ----------
    // Gương của addFolder/removeFolder/playgroundDisplayPaths ở trên, nhưng cho thư mục
    // Session storage riêng (setting session_storage_uri, Đợt E). Chưa chọn
    // thì KHÔNG có session root — không tự lấy playground làm thay (đợt fix
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

    /** Thử tạo, ghi, đọc, xóa một file tạm trong MỘT folder playground để chắc chắn công cụ ghi đúng chỗ. */
    fun selfTestFolder(uriString: String): String {
        val root = roots().firstOrNull { it.uri.toString() == uriString } ?: return "Folder not found."
        val doc = root.doc ?: return "Check failed for './${root.name}': the folder is not available."
        val granted = resolver.persistedUriPermissions.any {
            it.uri == root.uri && it.isReadPermission && it.isWritePermission
        }
        if (!granted) return "Check failed for './${root.name}': access was lost."
        return try {
            val name = ".byok_test.txt"
            writeTextUnder(doc, name, "ok-" + System.currentTimeMillis(), createParents = false)
            val back = readTextUnder(doc, name)
            requireFileUnder(doc, name).delete()
            if (back.startsWith("ok-")) "Check passed: the app can read and write in the folder './${root.name}'."
            else "Check failed for './${root.name}': the content read back does not match."
        } catch (e: Exception) {
            "Check failed for './${root.name}': ${e.message}"
        }
    }

    // ---------- Đường dẫn ----------

    fun segments(path: String): List<String> = parsePlaygroundSegments(path)

    fun display(path: String): String = segments(path).joinToString("/").ifEmpty { "." }

    /** Vị trí của đường dẫn trong mô hình gốc ảo (không chạm file, chỉ cần tên các folder). */
    fun locate(path: String): PlaygroundPath = resolvePlaygroundPath(rootNames(), segments(path))

    /** Folder playground mà đường dẫn bắt đầu bằng. Ném lỗi khi là gốc ảo hoặc tên folder không có. */
    fun rootForPath(path: String): RootEntry = rootForSegs(segments(path))

    private fun rootForSegs(segs: List<String>): RootEntry =
        when (val loc = resolvePlaygroundPath(rootNames(), segs)) {
            is PlaygroundPath.VirtualRoot -> throw ToolException(ModelFacingText.topLevelChangeDenied(rootNames()))
            is PlaygroundPath.PlaygroundFolder -> entryForName(loc.rootName)
            is PlaygroundPath.InsideFolder -> entryForName(loc.rootName)
        }

    private fun entryForName(name: String): RootEntry =
        roots().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw ToolException(ModelFacingText.unknownPlaygroundFolder(name, rootNames()))

    /** Ném lỗi (câu báo cho AI) khi thao tác không được phép ở vị trí này — xem ma trận §4.5. */
    fun checkOp(op: PlaygroundOp, path: String) {
        val loc = resolvePlaygroundPath(rootNames(), segments(path))
        if (!isPlaygroundOpAllowed(op, loc)) throw ToolException(deniedMessage(op, loc))
    }

    private fun deniedMessage(op: PlaygroundOp, loc: PlaygroundPath): String = when (loc) {
        is PlaygroundPath.VirtualRoot -> ModelFacingText.topLevelChangeDenied(rootNames())
        is PlaygroundPath.PlaygroundFolder ->
            if (op == PlaygroundOp.READ_FILE || op == PlaygroundOp.WRITE_FILE) {
                ModelFacingText.writeToPlaygroundFolder(loc.rootName)
            } else {
                ModelFacingText.playgroundFolderProtected(loc.rootName)
            }
        is PlaygroundPath.InsideFolder -> "Operation not allowed."
    }

    /** Đường dẫn đã phân giải: gốc ảo, hoặc một folder playground + phần còn lại bên trong nó. */
    private sealed interface ResolvedPath {
        data object VirtualRoot : ResolvedPath
        data class Node(val root: RootEntry, val rest: List<String>) : ResolvedPath
    }

    private fun resolve(path: String): ResolvedPath {
        val segs = segments(path)
        return when (val loc = resolvePlaygroundPath(rootNames(), segs)) {
            is PlaygroundPath.VirtualRoot -> ResolvedPath.VirtualRoot
            is PlaygroundPath.PlaygroundFolder -> ResolvedPath.Node(entryForName(loc.rootName), emptyList())
            is PlaygroundPath.InsideFolder -> ResolvedPath.Node(entryForName(loc.rootName), loc.rest)
        }
    }

    /** Tìm file/thư mục theo đường dẫn playground; null khi là gốc ảo hoặc không thấy. */
    fun find(path: String): DocumentFile? {
        return when (val r = resolve(path)) {
            is ResolvedPath.VirtualRoot -> null
            is ResolvedPath.Node -> {
                val doc = r.root.requireDoc()
                if (r.rest.isEmpty()) doc else findUnder(doc, r.rest.joinToString("/"))
            }
        }
    }

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

    fun require(path: String): DocumentFile = find(path) ?: throw ToolException("Not found: '${display(path)}'.")

    fun requireDirUnder(root: DocumentFile, path: String): DocumentFile {
        val d = requireUnder(root, path)
        if (!d.isDirectory) throw ToolException("'${display(path)}' is not a folder.")
        return d
    }

    fun requireDir(path: String): DocumentFile {
        val d = require(path)
        if (!d.isDirectory) throw ToolException("'${display(path)}' is not a folder.")
        return d
    }

    fun requireFileUnder(root: DocumentFile, path: String): DocumentFile {
        val d = requireUnder(root, path)
        if (d.isDirectory) throw ToolException("'${display(path)}' is a folder, not a file.")
        return d
    }

    fun requireFile(path: String): DocumentFile {
        val d = require(path)
        if (d.isDirectory) throw ToolException("'${display(path)}' is a folder, not a file.")
        return d
    }

    /** Một thư mục AI có thể liệt kê: gốc ảo, hoặc một thư mục thật. */
    sealed interface FsDir {
        data object VirtualRoot : FsDir
        data class Real(val doc: DocumentFile) : FsDir
    }

    /** Thư mục cho đường dẫn: rỗng = gốc ảo, còn lại là thư mục thật (ném lỗi nếu không phải thư mục). */
    fun dirFor(path: String): FsDir {
        return when (val r = resolve(path)) {
            is ResolvedPath.VirtualRoot -> FsDir.VirtualRoot
            is ResolvedPath.Node -> {
                val doc = r.root.requireDoc()
                FsDir.Real(if (r.rest.isEmpty()) doc else requireDirUnder(doc, r.rest.joinToString("/")))
            }
        }
    }

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

    /**
     * Liệt kê thư mục: một truy vấn lấy đủ thông tin; nếu lỗi thì rơi về cách cũ (chậm nhưng chắc chắn).
     * Tree URI được suy từ chính thư mục đang liệt kê (handoff §5.2) — không
     * còn giả định "một tree" từ cài đặt workspace cũ; nhờ vậy liệt kê đúng
     * với từng folder playground (và cả thư mục Session storage).
     */
    fun children(dir: DocumentFile): List<DocumentFile> {
        return try {
            val treeUri = DocumentsContract.buildTreeDocumentUri(
                dir.uri.authority,
                DocumentsContract.getTreeDocumentId(dir.uri),
            )
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

    /** Liệt kê một [FsDir]: gốc ảo trả các folder đã chọn (sắp theo tên); thư mục thật thì như [children]. */
    fun children(dir: FsDir): List<DocumentFile> = when (dir) {
        is FsDir.VirtualRoot -> roots().mapNotNull { it.doc }.sortedBy { it.name?.lowercase() ?: "" }
        is FsDir.Real -> children(dir.doc)
    }

    /** Cách cũ, dùng khi truy vấn nhanh không được. */
    private fun childrenSlow(dir: DocumentFile): List<DocumentFile> =
        dir.listFiles().filter { it.name != null }.sortedWith(compareBy({ !it.isDirectory }, { it.name!!.lowercase() }))

    /** Tìm mục con theo đúng tên (không phân biệt bằng cách hỏi từng mục như trước). */
    fun childByName(dir: DocumentFile, name: String): DocumentFile? = children(dir).firstOrNull { nameOf(it) == name }

    /**
     * Đảm bảo các thư mục tồn tại. [cache] (tùy chọn) nhớ thư mục đã xử lý theo đường dẫn,
     * để một lần chạy công cụ nhiều tệp không phải kiểm tra lại cùng một thư mục.
     * [root] là thư mục thật mà các đoạn này nằm dưới — hàm chỉ tạo thư mục
     * DƯỚI root đó, không bao giờ tạo gì ở cấp gốc ảo (lớp phòng thủ cuối:
     * mọi đường dẫn playground đều được phân giải thành root + phần còn lại
     * trước khi tới đây; xem [writeText] và [resolveTarget]).
     */
    fun ensureDir(
        segs: List<String>,
        cache: MutableMap<List<String>, DocumentFile>? = null,
        root: DocumentFile,
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

    /** Đọc file trong playground. Gốc ảo và chính folder đã chọn không phải file nên báo lỗi rõ. */
    fun readText(path: String, maxBytes: Long = 5_000_000): String {
        val r = resolve(path)
        if (r is ResolvedPath.VirtualRoot) throw ToolException("Missing file name.")
        r as ResolvedPath.Node
        if (r.rest.isEmpty()) throw ToolException("'${r.root.name}' is a folder, not a file.")
        return readTextUnder(r.root.requireDoc(), r.rest.joinToString("/"), maxBytes)
    }

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

    /**
     * Ghi file trong playground. File phải nằm BÊN TRONG một folder đã chọn
     * (ít nhất 2 đoạn); gốc ảo và chính folder đã chọn bị từ chối với câu
     * báo rõ cho AI (ma trận §4.5).
     */
    fun writeText(path: String, text: String, mustNotExist: Boolean = false, createParents: Boolean = true) {
        val r = resolve(path)
        if (r is ResolvedPath.VirtualRoot) throw ToolException(ModelFacingText.topLevelChangeDenied(rootNames()))
        r as ResolvedPath.Node
        if (r.rest.isEmpty()) throw ToolException(ModelFacingText.writeToPlaygroundFolder(r.root.name))
        writeTextUnder(r.root.requireDoc(), r.rest.joinToString("/"), text, mustNotExist, createParents)
    }

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

    // ---------- Thư mục Session storage (tiếp) ----------

    /**
     * Thư mục Session storage (Đợt E): chỉ dùng thư mục riêng đã chọn.
     * Chưa chọn thì ném lỗi (không tự lấy playground làm thay — đợt fix
     * 11/11/2026, C2). Dùng [sessionRootOrNull] ở những chỗ chỉ cần biết
     * có hay không.
     */
    fun sessionRoot(): DocumentFile =
        rootFrom(settings.sessionStorageUri.value, "session storage")

    fun sessionRootOrNull(): DocumentFile? = runCatching { sessionRoot() }.getOrNull()

    private fun rootFrom(uriString: String?, label: String): DocumentFile {
        val uri = uriString ?: throw ToolException("No $label folder has been chosen.")
        return DocumentFile.fromTreeUri(ctx, Uri.parse(uri)) ?: throw ToolException("Could not open the $label folder.")
    }

    // ---------- Thẻ trỏ file ----------

    /**
     * Đợt 8: đường dẫn tương đối trong playground của một document user vừa
     * chọn bằng hộp chọn file của hệ thống (SAF OpenDocument). So bằng
     * document ID: document nằm trong một folder playground thì ID của nó
     * bắt đầu bằng ID của cây đó + "/". Trả "<tên-folder>/phần/còn/lại",
     * null = file nằm ngoài mọi folder playground. Đợt fix 11/11/2026 (I1):
     * chỉ còn dùng để ƯU TIÊN lưu đường dẫn tương đối cho file trong
     * playground (dễ đọc trong bố cục đã lưu, quyền đọc đã có sẵn từ quyền
     * của cả cây) — KHÔNG còn là điều kiện bắt buộc: file ngoài playground
     * thì lưu URI (xem readCardFile).
     */
    fun relativePathForDocument(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        for (root in roots()) {
            if (root.name.isBlank()) continue
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(root.uri) }.getOrNull() ?: continue
            val prefix = "$treeId/"
            if (docId.startsWith(prefix)) {
                val rest = docId.removePrefix(prefix)
                if (rest.isNotEmpty()) return "${root.name}/$rest"
            }
        }
        return null
    }

    /**
     * File thẻ trỏ tới có phải URI tài liệu ngoài playground không (đợt fix
     * 11/11/2026, I1): thẻ lưu URI dạng "content://..." khi user chọn file ở
     * BẤT CỨ ĐÂU trên máy (không chỉ trong playground); đường dẫn tương đối
     * (không có scheme content) là file trong playground — thẻ cũ vẫn dùng
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
     * tương đối đọc trong playground như cũ (thẻ cũ). Chạy chặn, gọi từ
     * luồng nền.
     */
    fun readCardFile(source: String, maxBytes: Long = 5_000_000): String {
        if (isExternalCardFile(source)) {
            val uri = Uri.parse(source)
            val doc = DocumentFile.fromSingleUri(ctx, uri)
                ?: throw ToolException("Could not read the file.")
            return readDocText(doc, doc.name ?: source, maxBytes)
        }
        return readText(source, maxBytes)
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

    // ---------- Duyệt cây ----------

    /** Duyệt đệ quy. visit trả về false để dừng sớm. */
    fun walk(dir: DocumentFile, dirPath: String, maxDepth: Int, visit: (DocumentFile, String, Int) -> Boolean): Boolean =
        walkInner(dir, dirPath, maxDepth, 0, visit)

    /** Duyệt đệ quy một [FsDir]: gốc ảo duyệt từng folder đã chọn rồi đi sâu vào từng folder. */
    fun walk(dir: FsDir, dirPath: String, maxDepth: Int, visit: (DocumentFile, String, Int) -> Boolean): Boolean {
        return when (dir) {
            is FsDir.VirtualRoot -> {
                for (root in roots().mapNotNull { it.doc }.sortedBy { it.name?.lowercase() ?: "" }) {
                    stopIfCancelled()
                    val name = root.name ?: continue
                    if (!visit(root, name, 0)) return false
                    if (maxDepth > 0 && !walkInner(root, name, maxDepth, 1, visit)) return false
                }
                true
            }
            is FsDir.Real -> walk(dir.doc, dirPath, maxDepth, visit)
        }
    }

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

    /**
     * Xác định nơi đặt đích: nếu dest là thư mục có sẵn thì đặt bên trong, ngược lại dest là đường dẫn mới.
     * Đích cuối cùng phải nằm BÊN TRONG một folder playground (ít nhất 2 đoạn) — không bao giờ
     * đặt ở cấp gốc ảo; nguồn là chính một folder playground thì cũng bị từ chối ở tầng công cụ
     * (xem checkOp trước khi gọi hàm này).
     */
    fun resolveTarget(srcPath: String, destPath: String): Triple<DocumentFile, String, List<String>> {
        val srcSegs = segments(srcPath)
        if (srcSegs.isEmpty()) throw ToolException("Cannot operate on the virtual root.")
        val destSegs = segments(destPath)
        val existing = find(destPath)
        val finalSegs: List<String>
        if (existing != null && existing.isDirectory && destSegs.isNotEmpty()) {
            finalSegs = destSegs + srcSegs.last()
        } else if (destSegs.isEmpty()) {
            finalSegs = listOf(srcSegs.last())
        } else {
            finalSegs = destSegs
        }
        // Đích phải nằm BÊN TRONG một folder đã chọn: ít hơn 2 đoạn là ở
        // cấp gốc ảo (bị từ chối); rồi đoạn đầu phải là tên folder đã chọn.
        if (finalSegs.size < 2) throw ToolException(ModelFacingText.topLevelChangeDenied(rootNames()))
        val destRoot = rootForSegs(finalSegs)
        if (find(finalSegs.joinToString("/")) != null) {
            throw ToolException("The destination '${finalSegs.joinToString("/")}' already exists.")
        }
        if (finalSegs.size >= srcSegs.size && finalSegs.take(srcSegs.size) == srcSegs) {
            throw ToolException("Cannot copy/move a folder into itself.")
        }
        return Triple(ensureDir(finalSegs.drop(1).dropLast(1), root = destRoot.requireDoc()), finalSegs.last(), finalSegs)
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
