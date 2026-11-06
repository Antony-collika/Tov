package com.example.byokchat.tools

import androidx.documentfile.provider.DocumentFile
import com.example.byokchat.util.TextUtil
import com.example.byokchat.util.fmtTime
import com.example.byokchat.util.globToRegex
import com.example.byokchat.util.humanSize
import kotlinx.serialization.json.JsonObject
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val PATH_NOTE = " Đường dẫn tương đối so với gốc workspace (ví dụ: 'bao-cao/thang-11.md')."

fun fsTool(
    uid: String,
    risk: Risk,
    name: String,
    description: String,
    schema: JsonObject,
    preview: (suspend (ToolEnv, JsonObject) -> String)? = null,
    run: suspend (ToolEnv, JsonObject) -> String,
) = Tool(uid, ToolGroup.FILES, risk, name, description, schema, needsWorkspace = true, previewFn = preview, run = run)

/** Nhóm 1: công cụ tệp và thư mục. */
object FsTools {
    fun build(): List<Tool> = listOf(
        fsTool(
            "fs.list", Risk.READ, "list_dir",
            "Liệt kê các tệp và thư mục con trong một thư mục của workspace. Để trống path để xem thư mục gốc.",
            schema { str("path", "Đường dẫn thư mục (mặc định: gốc workspace).") },
        ) { env, a ->
            val path = a.str("path").orEmpty()
            val items = env.fs.children(env.fs.requireDir(path))
            if (items.isEmpty()) "Thư mục '${env.fs.display(path)}' trống."
            else {
                val sb = StringBuilder("Thư mục '${env.fs.display(path)}': ${items.size} mục\n")
                items.take(500).forEach { d ->
                    if (d.isDirectory) sb.append("📁 ${d.name}/\n")
                    else sb.append("📄 ${d.name}  (${humanSize(d.length())}, ${fmtTime(d.lastModified())})\n")
                }
                if (items.size > 500) sb.append("… còn ${items.size - 500} mục nữa\n")
                sb.toString().trimEnd()
            }
        },

        fsTool(
            "fs.tree", Risk.READ, "tree",
            "Xem cây thư mục (các cấp con lồng nhau) của workspace.",
            schema {
                str("path", "Thư mục gốc của cây (mặc định: gốc workspace).")
                int("max_depth", "Số cấp tối đa (mặc định 3).")
            },
        ) { env, a ->
            val path = a.str("path").orEmpty()
            val depth = (a.int("max_depth") ?: 3).coerceIn(0, 10)
            val dir = env.fs.requireDir(path)
            val sb = StringBuilder("${env.fs.display(path)}/\n")
            var count = 0
            var truncated = false
            env.fs.walk(dir, "", depth) { d, _, level ->
                sb.append("  ".repeat(level + 1)).append(d.name).append(if (d.isDirectory) "/" else "").append('\n')
                count++
                if (count >= 800) truncated = true
                count < 800
            }
            if (truncated) sb.append("… đã dừng ở 800 mục, hãy thu hẹp path hoặc giảm max_depth.\n")
            sb.toString().trimEnd()
        },

        fsTool(
            "fs.info", Risk.READ, "file_info",
            "Xem thông tin một tệp hoặc thư mục: loại, kích thước, ngày sửa, số dòng (với file văn bản).",
            schema { str("path", "Đường dẫn tệp hoặc thư mục.$PATH_NOTE", required = true) },
        ) { env, a ->
            val path = a.reqStr("path")
            val d = env.fs.require(path)
            val sb = StringBuilder()
            sb.append("Đường dẫn: ${env.fs.display(path)}\n")
            sb.append("Loại: ${if (d.isDirectory) "thư mục" else "tệp"}\n")
            sb.append("Sửa lần cuối: ${fmtTime(d.lastModified())}\n")
            if (d.isDirectory) {
                val kids = env.fs.children(d)
                sb.append("Chứa: ${kids.count { it.isDirectory }} thư mục, ${kids.count { !it.isDirectory }} tệp")
            } else {
                sb.append("Kích thước: ${humanSize(d.length())} (${d.length()} byte)\n")
                sb.append("Kiểu: ${d.type ?: "không rõ"}")
                if (env.fs.isTextFile(d)) {
                    val text = env.fs.readDocText(d, path)
                    sb.append("\nSố dòng: ${TextDoc.parse(text).lines.size}")
                }
            }
            sb.toString()
        },

        fsTool(
            "fs.mkdir", Risk.WRITE, "make_dir",
            "Tạo thư mục (tạo luôn các thư mục cha nếu chưa có).",
            schema { str("path", "Đường dẫn thư mục cần tạo.$PATH_NOTE", required = true) },
            preview = { env, a -> "Tạo thư mục: ${env.fs.display(a.reqStr("path"))}" },
        ) { env, a ->
            val path = a.reqStr("path")
            if (env.fs.segments(path).isEmpty()) throw ToolException("Thiếu tên thư mục.")
            env.fs.ensureDir(env.fs.segments(path))
            "Đã tạo thư mục '${env.fs.display(path)}'."
        },

        fsTool(
            "fs.copy", Risk.WRITE, "copy",
            "Sao chép tệp hoặc cả thư mục. Nếu dest là thư mục có sẵn thì bản sao được đặt bên trong nó; nếu không, dest là đường dẫn mới. Không ghi đè.",
            schema {
                str("source", "Đường dẫn nguồn.", required = true)
                str("dest", "Đường dẫn đích.", required = true)
            },
            preview = { env, a -> "Sao chép '${env.fs.display(a.reqStr("source"))}' → '${env.fs.display(a.reqStr("dest"))}'" },
        ) { env, a ->
            val src = env.fs.require(a.reqStr("source"))
            val (parent, name, finalSegs) = env.fs.resolveTarget(a.reqStr("source"), a.reqStr("dest"))
            env.fs.copyDoc(src, parent, name)
            "Đã sao chép '${env.fs.display(a.reqStr("source"))}' → '${finalSegs.joinToString("/")}'."
        },

        fsTool(
            "fs.move", Risk.WRITE, "move",
            "Di chuyển tệp hoặc thư mục sang nơi khác (có thể kèm đổi tên). Nếu dest là thư mục có sẵn thì đặt bên trong nó. Không ghi đè.",
            schema {
                str("source", "Đường dẫn nguồn.", required = true)
                str("dest", "Đường dẫn đích.", required = true)
            },
            preview = { env, a -> "Di chuyển '${env.fs.display(a.reqStr("source"))}' → '${env.fs.display(a.reqStr("dest"))}'" },
        ) { env, a ->
            val src = env.fs.require(a.reqStr("source"))
            val (parent, name, finalSegs) = env.fs.resolveTarget(a.reqStr("source"), a.reqStr("dest"))
            env.fs.copyDoc(src, parent, name)
            if (!src.delete()) throw ToolException("Đã sao chép nhưng không xóa được bản gốc '${env.fs.display(a.reqStr("source"))}'. Bản sao nằm ở '${finalSegs.joinToString("/")}'.")
            "Đã di chuyển '${env.fs.display(a.reqStr("source"))}' → '${finalSegs.joinToString("/")}'."
        },

        fsTool(
            "fs.rename", Risk.WRITE, "rename",
            "Đổi tên tệp hoặc thư mục (giữ nguyên vị trí).",
            schema {
                str("path", "Đường dẫn hiện tại.", required = true)
                str("new_name", "Tên mới (chỉ tên, không có dấu '/').", required = true)
            },
            preview = { env, a -> "Đổi tên '${env.fs.display(a.reqStr("path"))}' thành '${a.reqStr("new_name")}'" },
        ) { env, a ->
            val path = a.reqStr("path")
            val newName = a.reqStr("new_name").trim()
            if (newName.isEmpty() || newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
                throw ToolException("Tên mới không hợp lệ.")
            }
            val doc = env.fs.require(path)
            if (env.fs.segments(path).isEmpty()) throw ToolException("Không thể đổi tên thư mục gốc.")
            val parentPath = env.fs.segments(path).dropLast(1).joinToString("/")
            if (env.fs.find(if (parentPath.isEmpty()) newName else "$parentPath/$newName") != null) {
                throw ToolException("Đã có mục tên '$newName' trong thư mục này.")
            }
            if (!doc.renameTo(newName)) throw ToolException("Không đổi tên được.")
            "Đã đổi tên '${env.fs.display(path)}' thành '$newName'."
        },

        fsTool(
            "fs.delete", Risk.DELETE, "delete",
            "Xóa tệp hoặc thư mục (xóa luôn mọi thứ bên trong). Người dùng luôn phải xác nhận. Không thể hoàn tác.",
            schema { str("path", "Đường dẫn cần xóa.", required = true) },
            preview = { env, a ->
                val path = a.reqStr("path")
                val d = env.fs.require(path)
                if (d.isDirectory) "XÓA thư mục '${env.fs.display(path)}' cùng ${env.fs.countItems(d)} mục bên trong."
                else "XÓA tệp '${env.fs.display(path)}' (${humanSize(d.length())})."
            },
        ) { env, a ->
            val path = a.reqStr("path")
            if (env.fs.segments(path).isEmpty()) throw ToolException("Không thể xóa thư mục gốc của workspace.")
            val d = env.fs.require(path)
            if (!d.delete()) throw ToolException("Không xóa được '${env.fs.display(path)}'.")
            "Đã xóa '${env.fs.display(path)}'."
        },

        fsTool(
            "fs.find", Risk.READ, "find_files",
            "Tìm tệp/thư mục theo tên trong workspace. pattern có thể là một phần tên (không phân biệt hoa thường, bỏ dấu) hoặc mẫu có * và ? (ví dụ '*.csv').",
            schema {
                str("pattern", "Phần tên hoặc mẫu cần tìm.", required = true)
                str("path", "Thư mục bắt đầu tìm (mặc định: gốc workspace).")
                int("max_results", "Số kết quả tối đa (mặc định 100).")
            },
        ) { env, a ->
            val pattern = a.reqStr("pattern")
            val path = a.str("path").orEmpty()
            val max = (a.int("max_results") ?: 100).coerceIn(1, 500)
            val dir = env.fs.requireDir(path)
            val isGlob = pattern.contains('*') || pattern.contains('?')
            val rx = if (isGlob) globToRegex(pattern) else null
            val needle = TextUtil.fold(pattern)
            val found = mutableListOf<String>()
            var visited = 0
            val base = env.fs.segments(path).joinToString("/")
            env.fs.walk(dir, base, Int.MAX_VALUE) { d, p, _ ->
                visited++
                val n = d.name ?: ""
                val hit = if (rx != null) rx.matches(n) else TextUtil.fold(n).contains(needle)
                if (hit) found.add(if (d.isDirectory) "📁 $p/" else "📄 $p")
                found.size < max && visited < 20000
            }
            if (found.isEmpty()) "Không tìm thấy mục nào khớp '$pattern'."
            else "Tìm thấy ${found.size} mục:\n" + found.joinToString("\n")
        },

        fsTool(
            "fs.grep", Risk.READ, "search_in_files",
            "Tìm nội dung trong nhiều tệp văn bản của workspace. Trả về 'đường-dẫn:số-dòng: nội dung dòng'.",
            schema {
                str("query", "Chuỗi (hoặc biểu thức chính quy nếu regex=true) cần tìm.", required = true)
                str("path", "Thư mục bắt đầu tìm (mặc định: gốc workspace).")
                str("file_pattern", "Chỉ tìm trong tệp có tên khớp mẫu, ví dụ '*.md'.")
                bool("regex", "Coi query là biểu thức chính quy (mặc định false).")
                bool("case_sensitive", "Phân biệt hoa thường (mặc định false).")
                int("max_results", "Số dòng khớp tối đa (mặc định 100).")
            },
        ) { env, a ->
            val query = a.reqStr("query")
            val path = a.str("path").orEmpty()
            val max = (a.int("max_results") ?: 100).coerceIn(1, 500)
            val caseSensitive = a.bool("case_sensitive") ?: false
            val rx = if (a.bool("regex") == true) {
                try {
                    Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
                } catch (e: Exception) {
                    throw ToolException("Biểu thức chính quy không hợp lệ: ${e.message}")
                }
            } else null
            val fileRx = a.str("file_pattern")?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
            val needle = if (caseSensitive) query else TextUtil.fold(query)
            val dir = env.fs.requireDir(path)
            val base = env.fs.segments(path).joinToString("/")
            val out = StringBuilder()
            var hits = 0
            var filesScanned = 0
            env.fs.walk(dir, base, Int.MAX_VALUE) { d, p, _ ->
                if (!d.isDirectory && (fileRx == null || fileRx.matches(d.name ?: "")) && env.fs.isTextFile(d)) {
                    filesScanned++
                    val text = runCatching { env.fs.readDocText(d, p, 2_000_000) }.getOrNull()
                    if (text != null) {
                        text.lines().forEachIndexed { i, line ->
                            if (hits < max) {
                                val match = when {
                                    rx != null -> rx.containsMatchIn(line)
                                    caseSensitive -> line.contains(needle)
                                    else -> TextUtil.fold(line).contains(needle)
                                }
                                if (match) {
                                    hits++
                                    out.append("$p:${i + 1}: ${line.trim().take(300)}\n")
                                }
                            }
                        }
                    }
                }
                hits < max && filesScanned < 3000
            }
            if (hits == 0) "Không có dòng nào khớp (đã quét $filesScanned tệp văn bản)."
            else "Tìm thấy $hits dòng khớp (đã quét $filesScanned tệp):\n" + out.toString().trimEnd()
        },

        fsTool(
            "fs.zip", Risk.WRITE, "zip",
            "Nén tệp/thư mục thành một file .zip trong workspace.",
            schema {
                strArray("sources", "Danh sách đường dẫn tệp/thư mục cần nén.", required = true)
                str("dest", "Đường dẫn file zip đầu ra (ví dụ 'sao-luu.zip').", required = true)
            },
            preview = { env, a ->
                "Nén ${a.strList("sources").orEmpty().joinToString(", ") { env.fs.display(it) }}\nthành '${env.fs.display(a.reqStr("dest"))}'"
            },
        ) { env, a ->
            val sources = a.strList("sources").orEmpty()
            if (sources.isEmpty()) throw ToolException("Chưa có nguồn nào để nén.")
            val dest = a.reqStr("dest")
            if (!dest.lowercase().endsWith(".zip")) throw ToolException("Tên file đích phải kết thúc bằng .zip")
            val docs = sources.map { env.fs.require(it) to env.fs.segments(it) }
            if (docs.any { it.second.isEmpty() }) throw ToolException("Không thể nén cả thư mục gốc; hãy chọn từng mục.")
            val segs = env.fs.segments(dest)
            val parent = env.fs.ensureDir(segs.dropLast(1))
            if (parent.findFile(segs.last()) != null) throw ToolException("File '${env.fs.display(dest)}' đã tồn tại.")
            val out = env.fs.createFileNamed(parent, segs.last())
            var files = 0
            ZipOutputStream(env.fs.openOut(out).buffered()).use { zos ->
                fun add(d: DocumentFile, entry: String) {
                    if (d.uri == out.uri) return
                    if (d.isDirectory) {
                        zos.putNextEntry(ZipEntry("$entry/"))
                        zos.closeEntry()
                        for (c in env.fs.children(d)) add(c, "$entry/${c.name}")
                    } else {
                        zos.putNextEntry(ZipEntry(entry))
                        env.fs.openIn(d).use { it.copyTo(zos) }
                        zos.closeEntry()
                        files++
                    }
                }
                docs.forEach { (d, s) -> add(d, s.last()) }
            }
            "Đã nén $files tệp vào '${env.fs.display(dest)}' (${humanSize(out.length())})."
        },

        fsTool(
            "fs.unzip", Risk.WRITE, "unzip",
            "Giải nén file .zip vào một thư mục trong workspace (ghi đè tệp trùng tên).",
            schema {
                str("source", "Đường dẫn file zip.", required = true)
                str("dest_dir", "Thư mục đích (mặc định: gốc workspace).")
            },
            preview = { env, a ->
                "Giải nén '${env.fs.display(a.reqStr("source"))}' vào '${env.fs.display(a.str("dest_dir").orEmpty())}' (tệp trùng tên sẽ bị ghi đè)"
            },
        ) { env, a ->
            val zip = env.fs.requireFile(a.reqStr("source"))
            val destSegs = env.fs.segments(a.str("dest_dir").orEmpty())
            val destRoot = env.fs.ensureDir(destSegs)
            var files = 0
            var skipped = 0
            var total = 0L
            ZipInputStream(env.fs.openIn(zip).buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val rel = e.name.replace('\\', '/')
                    val segs = rel.split('/').filter { it.isNotEmpty() && it != "." }
                    if (segs.isEmpty() || segs.any { it == ".." } || rel.startsWith("/") || segs.any { it.contains(':') }) {
                        skipped++
                        continue
                    }
                    if (e.isDirectory) {
                        env.fs.ensureDir(destSegs + segs)
                    } else {
                        val parent = env.fs.ensureDir(destSegs + segs.dropLast(1))
                        val name = segs.last()
                        val doc = parent.findFile(name)?.takeIf { !it.isDirectory } ?: env.fs.createFileNamed(parent, name)
                        env.fs.openOut(doc).use { o ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > 500L * 1024 * 1024) throw ToolException("Dừng giải nén: dung lượng vượt quá 500 MB.")
                                o.write(buf, 0, n)
                            }
                        }
                        files++
                    }
                }
            }
            "Đã giải nén $files tệp vào '${env.fs.display(a.str("dest_dir").orEmpty())}'." +
                if (skipped > 0) " Bỏ qua $skipped mục có đường dẫn không an toàn." else ""
        },
    )
}
