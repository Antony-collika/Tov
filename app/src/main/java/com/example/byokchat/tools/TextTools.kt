package com.example.byokchat.tools

import com.example.byokchat.util.globToRegex
import kotlinx.serialization.json.JsonObject

fun textTool(
    uid: String,
    risk: Risk,
    name: String,
    description: String,
    schema: JsonObject,
    preview: (suspend (ToolEnv, JsonObject) -> String)? = null,
    run: suspend (ToolEnv, JsonObject) -> String,
) = Tool(uid, ToolGroup.TEXT, risk, name, description, schema, needsWorkspace = true, previewFn = preview, run = run)

private const val FILE_NOTE = "Supports text files: txt, md, csv, json, xml, html, yaml, log and similar."

private fun numbered(lines: List<String>, from: Int): String {
    val width = (from + lines.size).toString().length
    return lines.mapIndexed { i, l -> (from + i).toString().padStart(width) + "| " + l }.joinToString("\n")
}

private fun lineRange(a: JsonObject, total: Int): Pair<Int, Int> {
    val s = (a.int("start_line") ?: 1)
    val e = (a.int("end_line") ?: total)
    if (s < 1) throw ToolException("start_line must be 1 or greater.")
    if (total > 0 && s > total) throw ToolException("start_line ($s) is beyond the number of lines in the file ($total).")
    if (e < s) throw ToolException("end_line ($e) must be greater than or equal to start_line ($s).")
    return s to minOf(e, total)
}

/** Nhóm 2 (phần văn bản): đọc, ghi, sửa, so sánh, thống kê. */
object TextTools {
    fun build(): List<Tool> = listOf(
        textTool(
            "txt.read", Risk.READ, "read_file",
            "Read a text file, either the whole file or a range of lines. $FILE_NOTE When a line range is given, the result includes line numbers.",
            schema {
                str("path", "File path.", required = true)
                int("start_line", "First line (1-based).")
                int("end_line", "Last line (inclusive).")
                bool("line_numbers", "Whether to number the lines (default: yes when a line range is given).")
            },
        ) { env, a ->
            val path = a.reqStr("path")
            val doc = TextDoc.parse(env.fs.readText(path))
            val total = doc.lines.size
            if (total == 0) return@textTool "File '${env.fs.display(path)}' is empty (0 lines)."
            val (s, e) = lineRange(a, total)
            val ranged = a.int("start_line") != null || a.int("end_line") != null
            val withNumbers = a.bool("line_numbers") ?: ranged
            val slice = doc.lines.subList(s - 1, e)
            val header = "File '${env.fs.display(path)}' — $total lines" + if (ranged) ", showing lines $s–$e" else ""
            header + "\n" + if (withNumbers) numbered(slice, s) else slice.joinToString("\n")
        },

        textTool(
            "txt.read_many", Risk.READ, "read_many_files",
            "Read several text files at once (each file is read in full and truncated if too long). $FILE_NOTE",
            schema { strArray("paths", "List of file paths.", required = true) },
        ) { env, a ->
            val paths = a.strList("paths").orEmpty()
            if (paths.isEmpty()) throw ToolException("No paths given.")
            if (paths.size > 20) throw ToolException("At most 20 files per call.")
            paths.joinToString("\n\n") { p ->
                try {
                    val text = env.fs.readText(p)
                    val body = if (text.length > 15000) text.take(15000) + "\n…[truncated; use read_file with a line range to continue]" else text
                    "===== ${env.fs.display(p)} =====\n$body"
                } catch (e: ToolException) {
                    "===== ${env.fs.display(p)} =====\n[Error] ${e.message}"
                }
            }
        },

        textTool(
            "txt.create", Risk.WRITE, "create_file",
            "Create a NEW text file (fails if the file already exists; parent folders are created automatically).",
            schema {
                str("path", "New file path.", required = true)
                str("content", "File content.", required = true)
            },
            preview = { env, a ->
                "Create new file '${env.fs.display(a.reqStr("path"))}' (${a.reqStr("content").lines().size} lines)\n--- preview ---\n" + previewText(a.reqStr("content"))
            },
        ) { env, a ->
            env.fs.writeText(a.reqStr("path"), a.reqStr("content"), mustNotExist = true)
            "Created '${env.fs.display(a.reqStr("path"))}' (${a.reqStr("content").lines().size} lines)."
        },

        textTool(
            "txt.write", Risk.WRITE, "write_file",
            "Overwrite the entire content of a file (creates it if missing; parent folders are created automatically). Careful: the old content is lost.",
            schema {
                str("path", "File path.", required = true)
                str("content", "New content of the file.", required = true)
            },
            preview = { env, a ->
                val p = a.reqStr("path")
                val existing = env.fs.find(p)
                val head = if (existing != null && !env.fs.isDir(existing)) {
                    val old = runCatching { TextDoc.parse(env.fs.readText(p)).lines.size }.getOrNull()
                    "OVERWRITE '${env.fs.display(p)}'" + (old?.let { " (currently $it lines)" } ?: "")
                } else "Create file '${env.fs.display(p)}'"
                "$head\nNew content: ${a.reqStr("content").lines().size} lines\n--- preview ---\n" + previewText(a.reqStr("content"))
            },
        ) { env, a ->
            env.fs.writeText(a.reqStr("path"), a.reqStr("content"))
            "Wrote '${env.fs.display(a.reqStr("path"))}' (${a.reqStr("content").lines().size} lines)."
        },

        textTool(
            "txt.append", Risk.WRITE, "append_to_file",
            "Append content to the END of a file (creates the file if missing).",
            schema {
                str("path", "File path.", required = true)
                str("content", "Content to append.", required = true)
            },
            preview = { env, a -> "Append to the end of '${env.fs.display(a.reqStr("path"))}':\n" + previewText(a.reqStr("content")) },
        ) { env, a ->
            val path = a.reqStr("path")
            val content = a.reqStr("content")
            val existing = env.fs.find(path)
            if (existing == null) {
                env.fs.writeText(path, content)
            } else {
                val doc = TextDoc.parse(env.fs.readText(path))
                val add = TextDoc.parse(content)
                doc.lines.addAll(add.lines)
                doc.trailing = true
                env.fs.writeText(path, doc.render())
            }
            "Appended content to the end of '${env.fs.display(path)}'."
        },

        textTool(
            "txt.insert_lines", Risk.WRITE, "insert_lines",
            "Insert one or more lines at a specific position. The text is inserted BEFORE line 'line'; set line = line count + 1 to insert at the end.",
            schema {
                str("path", "File path.", required = true)
                int("line", "Line number (1-based) that the new text will be placed before.", required = true)
                str("text", "Text to insert (may span several lines).", required = true)
            },
            preview = { env, a ->
                "Insert before line ${a.int("line")} of '${env.fs.display(a.reqStr("path"))}':\n" + previewText(a.reqStr("text"))
            },
        ) { env, a ->
            val path = a.reqStr("path")
            val doc = TextDoc.parse(env.fs.readText(path))
            val line = a.int("line") ?: throw ToolException("Missing parameter 'line'.")
            if (line < 1 || line > doc.lines.size + 1) throw ToolException("line must be between 1 and ${doc.lines.size + 1}.")
            val ins = a.reqStr("text").removeSuffix("\n").split("\n")
            doc.lines.addAll(line - 1, ins)
            if (doc.lines.size == ins.size) doc.trailing = true
            env.fs.writeText(path, doc.render())
            "Inserted ${ins.size} lines before line $line of '${env.fs.display(path)}' (now ${doc.lines.size} lines)."
        },

        textTool(
            "txt.delete_lines", Risk.WRITE, "delete_lines",
            "Delete a range of lines (from start_line to end_line, both inclusive) from the file.",
            schema {
                str("path", "File path.", required = true)
                int("start_line", "First line (1-based).", required = true)
                int("end_line", "Last line.", required = true)
            },
            preview = { env, a ->
                val p = a.reqStr("path")
                val doc = TextDoc.parse(env.fs.readText(p))
                val (s, e) = lineRange(a, doc.lines.size)
                "Delete lines $s–$e of '${env.fs.display(p)}':\n" + previewText(doc.lines.subList(s - 1, e).joinToString("\n"))
            },
        ) { env, a ->
            val path = a.reqStr("path")
            val doc = TextDoc.parse(env.fs.readText(path))
            val (s, e) = lineRange(a, doc.lines.size)
            for (i in e downTo s) doc.lines.removeAt(i - 1)
            env.fs.writeText(path, doc.render())
            "Deleted ${e - s + 1} lines ($s–$e) from '${env.fs.display(path)}' (${doc.lines.size} lines remain)."
        },

        textTool(
            "txt.replace", Risk.WRITE, "replace_in_file",
            "Replace an exact piece of text in a file. old_text must match EXACTLY (including whitespace) and appear exactly once, unless replace_all=true.",
            schema {
                str("path", "File path.", required = true)
                str("old_text", "Text to replace (exact match).", required = true)
                str("new_text", "Replacement text (may be empty to delete).", required = true)
                bool("replace_all", "Replace every occurrence (default false).")
            },
            preview = { env, a ->
                val text = env.fs.readText(a.reqStr("path"))
                val count = text.split(a.reqStr("old_text")).size - 1
                "Edit '${env.fs.display(a.reqStr("path"))}' ($count matches)\n--- REPLACE ---\n${previewText(a.reqStr("old_text"), 8)}\n--- WITH ---\n${previewText(a.reqStr("new_text"), 8)}"
            },
        ) { env, a ->
            val path = a.reqStr("path")
            val old = a.reqStr("old_text")
            val new = a.str("new_text") ?: throw ToolException("Missing parameter 'new_text'.")
            if (old.isEmpty()) throw ToolException("old_text must not be empty.")
            val text = env.fs.readText(path)
            val count = text.split(old).size - 1
            if (count == 0) throw ToolException("old_text not found in '${env.fs.display(path)}'. Re-read the file and copy the exact text to change.")
            val all = a.bool("replace_all") == true
            if (count > 1 && !all) throw ToolException("old_text appears $count times. Add more surrounding context to make it unique, or set replace_all=true.")
            env.fs.writeText(path, if (all) text.replace(old, new) else text.replaceFirst(old, new))
            "Replaced ${if (all) count else 1} occurrence(s) in '${env.fs.display(path)}'."
        },

        textTool(
            "txt.replace_many", Risk.WRITE, "find_replace_many",
            "Find and replace in bulk across several text files. Give a list of 'paths' or a 'directory' (with 'file_pattern'). Use dry_run=true to preview how many replacements would be made.",
            schema {
                str("find", "Text (or regular expression if regex=true) to look for.", required = true)
                str("replace", "Replacement text.", required = true)
                strArray("paths", "List of specific files.")
                str("directory", "Or: a folder whose text files (recursively) are all processed.")
                str("file_pattern", "With directory: only files whose name matches this pattern, e.g. '*.md'.")
                bool("regex", "Treat 'find' as a regular expression (default false).")
                bool("case_sensitive", "Case-sensitive (default true).")
                bool("dry_run", "Only report, do not modify files (default false).")
            },
            preview = { env, a -> findReplaceMany(env, a, forceDry = true) },
        ) { env, a -> findReplaceMany(env, a, forceDry = false) },

        textTool(
            "txt.diff", Risk.READ, "compare_files",
            "Compare two text files line by line and list the differing lines ('-' only in file A, '+' only in file B).",
            schema {
                str("path_a", "First file.", required = true)
                str("path_b", "Second file.", required = true)
            },
        ) { env, a ->
            val la = TextDoc.parse(env.fs.readText(a.reqStr("path_a"))).lines
            val lb = TextDoc.parse(env.fs.readText(a.reqStr("path_b"))).lines
            if (la.size.toLong() * lb.size > 6_000_000L) throw ToolException("The two files are too large to compare in detail.")
            // LCS bằng quy hoạch động
            val n = la.size
            val m = lb.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                dp[i][j] = if (la[i] == lb[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
            val out = StringBuilder()
            var i = 0
            var j = 0
            var diffs = 0
            while (i < n || j < m) {
                when {
                    i < n && j < m && la[i] == lb[j] -> { i++; j++ }
                    j >= m || (i < n && dp[i + 1][j] >= dp[i][j + 1]) -> { out.append("- (A:${i + 1}) ${la[i]}\n"); i++; diffs++ }
                    else -> { out.append("+ (B:${j + 1}) ${lb[j]}\n"); j++; diffs++ }
                }
            }
            if (diffs == 0) "The two files are identical."
            else "$diffs lines differ (A=${env.fs.display(a.reqStr("path_a"))}, B=${env.fs.display(a.reqStr("path_b"))}):\n" + out.toString().trimEnd()
        },

        textTool(
            "txt.count", Risk.READ, "count_text",
            "Count the words, lines, characters and bytes of a text file.",
            schema { str("path", "File path.", required = true) },
        ) { env, a ->
            val text = env.fs.readText(a.reqStr("path"))
            val lines = TextDoc.parse(text).lines.size
            val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
            "File '${env.fs.display(a.reqStr("path"))}': $lines lines, $words words, ${text.length} characters, ${text.toByteArray().size} bytes."
        },
    )

    private fun findReplaceMany(env: ToolEnv, a: JsonObject, forceDry: Boolean): String {
        val find = a.reqStr("find")
        val replace = a.str("replace") ?: throw ToolException("Missing parameter 'replace'.")
        if (find.isEmpty()) throw ToolException("'find' must not be empty.")
        val dry = forceDry || a.bool("dry_run") == true
        val caseSensitive = a.bool("case_sensitive") ?: true
        val useRegex = a.bool("regex") == true
        val rx = try {
            Regex(if (useRegex) find else Regex.escape(find), if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            throw ToolException("Invalid regular expression: ${e.message}")
        }
        val targets = mutableListOf<String>()
        val explicit = a.strList("paths").orEmpty()
        if (explicit.isNotEmpty()) {
            targets.addAll(explicit)
        } else {
            val dirPath = a.str("directory") ?: throw ToolException("Either 'paths' or 'directory' is required.")
            val fileRx = a.str("file_pattern")?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
            val base = env.fs.segments(dirPath).joinToString("/")
            env.fs.walk(env.fs.dirFor(dirPath), base, Int.MAX_VALUE) { d, p, _ ->
                env.fs.stopIfCancelled()
                if (!env.fs.isDir(d) && (fileRx == null || fileRx.matches(env.fs.nameOf(d))) && env.fs.isTextFile(d)) targets.add(p)
                targets.size < 2000
            }
        }
        if (targets.isEmpty()) return "No files to process."
        val report = StringBuilder()
        var totalHits = 0
        var changedFiles = 0
        for (p in targets) {
            try {
                val text = env.fs.readText(p)
                val hits = rx.findAll(text).count()
                if (hits > 0) {
                    totalHits += hits
                    changedFiles++
                    report.append("• ${env.fs.display(p)}: $hits match(es)\n")
                    if (!dry) env.fs.writeText(p, if (useRegex) rx.replace(text, replace) else rx.replace(text, java.util.regex.Matcher.quoteReplacement(replace)))
                }
            } catch (e: ToolException) {
                report.append("• ${env.fs.display(p)}: skipped (${e.message})\n")
            }
        }
        val head = if (dry) "PREVIEW: would replace $totalHits occurrence(s) in $changedFiles/${targets.size} files." else "Replaced $totalHits occurrence(s) in $changedFiles/${targets.size} files."
        return head + "\n" + report.toString().trimEnd()
    }
}
