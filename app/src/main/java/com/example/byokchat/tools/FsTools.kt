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

private const val PATH_NOTE = " Path relative to the workspace root (e.g. 'reports/november.md')."

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
            "List the files and subfolders inside a workspace folder. Leave path empty to list the workspace root.",
            schema { str("path", "Folder path (default: workspace root).") },
        ) { env, a ->
            val path = a.str("path").orEmpty()
            val items = env.fs.children(env.fs.requireDir(path))
            if (items.isEmpty()) "Folder '${env.fs.display(path)}' is empty."
            else {
                val sb = StringBuilder("Folder '${env.fs.display(path)}': ${items.size} items\n")
                items.take(500).forEach { d ->
                    if (d.isDirectory) sb.append("📁 ${d.name}/\n")
                    else sb.append("📄 ${d.name}  (${humanSize(d.length())}, ${fmtTime(d.lastModified())})\n")
                }
                if (items.size > 500) sb.append("… ${items.size - 500} more items\n")
                sb.toString().trimEnd()
            }
        },

        fsTool(
            "fs.tree", Risk.READ, "tree",
            "Show the workspace folder tree (nested subfolders).",
            schema {
                str("path", "Root folder of the tree (default: workspace root).")
                int("max_depth", "Maximum depth (default 3).")
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
            if (truncated) sb.append("… stopped at 800 entries; narrow the path or lower max_depth.\n")
            sb.toString().trimEnd()
        },

        fsTool(
            "fs.info", Risk.READ, "file_info",
            "Show information about a file or folder: type, size, modified date, line count (for text files).",
            schema { str("path", "File or folder path.$PATH_NOTE", required = true) },
        ) { env, a ->
            val path = a.reqStr("path")
            val d = env.fs.require(path)
            val sb = StringBuilder()
            sb.append("Path: ${env.fs.display(path)}\n")
            sb.append("Type: ${if (d.isDirectory) "folder" else "file"}\n")
            sb.append("Last modified: ${fmtTime(d.lastModified())}\n")
            if (d.isDirectory) {
                val kids = env.fs.children(d)
                sb.append("Contains: ${kids.count { it.isDirectory }} folders, ${kids.count { !it.isDirectory }} files")
            } else {
                sb.append("Size: ${humanSize(d.length())} (${d.length()} bytes)\n")
                sb.append("MIME type: ${d.type ?: "unknown"}")
                if (env.fs.isTextFile(d)) {
                    val text = env.fs.readDocText(d, path)
                    sb.append("\nLines: ${TextDoc.parse(text).lines.size}")
                }
            }
            sb.toString()
        },

        fsTool(
            "fs.mkdir", Risk.WRITE, "make_dir",
            "Create a folder (parent folders are created too if missing).",
            schema { str("path", "Path of the folder to create.$PATH_NOTE", required = true) },
            preview = { env, a -> "Create folder: ${env.fs.display(a.reqStr("path"))}" },
        ) { env, a ->
            val path = a.reqStr("path")
            if (env.fs.segments(path).isEmpty()) throw ToolException("Missing folder name.")
            env.fs.ensureDir(env.fs.segments(path))
            "Created folder '${env.fs.display(path)}'."
        },

        fsTool(
            "fs.copy", Risk.WRITE, "copy",
            "Copy a file or a whole folder. If dest is an existing folder the copy is placed inside it; otherwise dest is the new path. Never overwrites.",
            schema {
                str("source", "Source path.", required = true)
                str("dest", "Destination path.", required = true)
            },
            preview = { env, a -> "Copy '${env.fs.display(a.reqStr("source"))}' → '${env.fs.display(a.reqStr("dest"))}'" },
        ) { env, a ->
            val src = env.fs.require(a.reqStr("source"))
            val (parent, name, finalSegs) = env.fs.resolveTarget(a.reqStr("source"), a.reqStr("dest"))
            env.fs.copyDoc(src, parent, name)
            "Copied '${env.fs.display(a.reqStr("source"))}' → '${finalSegs.joinToString("/")}'."
        },

        fsTool(
            "fs.move", Risk.WRITE, "move",
            "Move a file or folder elsewhere (optionally renaming it). If dest is an existing folder the item is placed inside it. Never overwrites.",
            schema {
                str("source", "Source path.", required = true)
                str("dest", "Destination path.", required = true)
            },
            preview = { env, a -> "Move '${env.fs.display(a.reqStr("source"))}' → '${env.fs.display(a.reqStr("dest"))}'" },
        ) { env, a ->
            val src = env.fs.require(a.reqStr("source"))
            val (parent, name, finalSegs) = env.fs.resolveTarget(a.reqStr("source"), a.reqStr("dest"))
            env.fs.copyDoc(src, parent, name)
            if (!src.delete()) throw ToolException("Copied, but could not delete the original '${env.fs.display(a.reqStr("source"))}'. The copy is at '${finalSegs.joinToString("/")}'.")
            "Moved '${env.fs.display(a.reqStr("source"))}' → '${finalSegs.joinToString("/")}'."
        },

        fsTool(
            "fs.rename", Risk.WRITE, "rename",
            "Rename a file or folder (it stays in the same place).",
            schema {
                str("path", "Current path.", required = true)
                str("new_name", "New name (name only, no '/').", required = true)
            },
            preview = { env, a -> "Rename '${env.fs.display(a.reqStr("path"))}' to '${a.reqStr("new_name")}'" },
        ) { env, a ->
            val path = a.reqStr("path")
            val newName = a.reqStr("new_name").trim()
            if (newName.isEmpty() || newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
                throw ToolException("Invalid new name.")
            }
            val doc = env.fs.require(path)
            if (env.fs.segments(path).isEmpty()) throw ToolException("Cannot rename the root folder.")
            val parentPath = env.fs.segments(path).dropLast(1).joinToString("/")
            if (env.fs.find(if (parentPath.isEmpty()) newName else "$parentPath/$newName") != null) {
                throw ToolException("An item named '$newName' already exists in this folder.")
            }
            if (!doc.renameTo(newName)) throw ToolException("Could not rename.")
            "Renamed '${env.fs.display(path)}' to '$newName'."
        },

        fsTool(
            "fs.delete", Risk.DELETE, "delete",
            "Delete a file or folder (including everything inside it). The user must always confirm. Cannot be undone.",
            schema { str("path", "Path to delete.", required = true) },
            preview = { env, a ->
                val path = a.reqStr("path")
                val d = env.fs.require(path)
                if (d.isDirectory) "DELETE folder '${env.fs.display(path)}' and the ${env.fs.countItems(d)} items inside it."
                else "DELETE file '${env.fs.display(path)}' (${humanSize(d.length())})."
            },
        ) { env, a ->
            val path = a.reqStr("path")
            if (env.fs.segments(path).isEmpty()) throw ToolException("Cannot delete the workspace root folder.")
            val d = env.fs.require(path)
            if (!d.delete()) throw ToolException("Could not delete '${env.fs.display(path)}'.")
            "Deleted '${env.fs.display(path)}'."
        },

        fsTool(
            "fs.find", Risk.READ, "find_files",
            "Find files/folders by name in the workspace. pattern can be part of a name (case- and accent-insensitive) or a glob using * and ? (e.g. '*.csv').",
            schema {
                str("pattern", "Name fragment or pattern to look for.", required = true)
                str("path", "Folder to start searching from (default: workspace root).")
                int("max_results", "Maximum number of results (default 100).")
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
            if (found.isEmpty()) "No items match '$pattern'."
            else "Found ${found.size} items:\n" + found.joinToString("\n")
        },

        fsTool(
            "fs.grep", Risk.READ, "search_in_files",
            "Search inside the text files of the workspace. Returns 'path:line-number: line content'.",
            schema {
                str("query", "Text (or regular expression if regex=true) to look for.", required = true)
                str("path", "Folder to start searching from (default: workspace root).")
                str("file_pattern", "Only search files whose name matches this pattern, e.g. '*.md'.")
                bool("regex", "Treat query as a regular expression (default false).")
                bool("case_sensitive", "Case-sensitive (default false).")
                int("max_results", "Maximum number of matching lines (default 100).")
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
                    throw ToolException("Invalid regular expression: ${e.message}")
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
            if (hits == 0) "No matching lines (scanned $filesScanned text files)."
            else "Found $hits matching lines (scanned $filesScanned files):\n" + out.toString().trimEnd()
        },

        fsTool(
            "fs.zip", Risk.WRITE, "zip",
            "Compress files/folders into a .zip file in the workspace.",
            schema {
                strArray("sources", "List of file/folder paths to compress.", required = true)
                str("dest", "Output zip file path (e.g. 'backup.zip').", required = true)
            },
            preview = { env, a ->
                "Compress ${a.strList("sources").orEmpty().joinToString(", ") { env.fs.display(it) }}\ninto '${env.fs.display(a.reqStr("dest"))}'"
            },
        ) { env, a ->
            val sources = a.strList("sources").orEmpty()
            if (sources.isEmpty()) throw ToolException("No sources to compress.")
            val dest = a.reqStr("dest")
            if (!dest.lowercase().endsWith(".zip")) throw ToolException("The destination file name must end with .zip")
            val docs = sources.map { env.fs.require(it) to env.fs.segments(it) }
            if (docs.any { it.second.isEmpty() }) throw ToolException("Cannot compress the whole root folder; pick individual items.")
            val segs = env.fs.segments(dest)
            val parent = env.fs.ensureDir(segs.dropLast(1))
            if (parent.findFile(segs.last()) != null) throw ToolException("File '${env.fs.display(dest)}' already exists.")
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
            "Compressed $files files into '${env.fs.display(dest)}' (${humanSize(out.length())})."
        },

        fsTool(
            "fs.unzip", Risk.WRITE, "unzip",
            "Extract a .zip file into a workspace folder (overwrites files with the same name).",
            schema {
                str("source", "Zip file path.", required = true)
                str("dest_dir", "Destination folder (default: workspace root).")
            },
            preview = { env, a ->
                "Extract '${env.fs.display(a.reqStr("source"))}' into '${env.fs.display(a.str("dest_dir").orEmpty())}' (files with the same name will be overwritten)"
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
                                if (total > 500L * 1024 * 1024) throw ToolException("Extraction stopped: size exceeds 500 MB.")
                                o.write(buf, 0, n)
                            }
                        }
                        files++
                    }
                }
            }
            "Extracted $files files into '${env.fs.display(a.str("dest_dir").orEmpty())}'." +
                if (skipped > 0) " Skipped $skipped items with unsafe paths." else ""
        },
    )
}
