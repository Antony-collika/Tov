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

private const val FILE_NOTE = "Hỗ trợ file chữ: txt, md, csv, json, xml, html, yaml, log và tương tự."

private fun numbered(lines: List<String>, from: Int): String {
    val width = (from + lines.size).toString().length
    return lines.mapIndexed { i, l -> (from + i).toString().padStart(width) + "| " + l }.joinToString("\n")
}

private fun lineRange(a: JsonObject, total: Int): Pair<Int, Int> {
    val s = (a.int("start_line") ?: 1)
    val e = (a.int("end_line") ?: total)
    if (s < 1) throw ToolException("start_line phải từ 1 trở lên.")
    if (total > 0 && s > total) throw ToolException("start_line ($s) vượt quá số dòng của file ($total).")
    if (e < s) throw ToolException("end_line ($e) phải lớn hơn hoặc bằng start_line ($s).")
    return s to minOf(e, total)
}

/** Nhóm 2 (phần văn bản): đọc, ghi, sửa, so sánh, thống kê. */
object TextTools {
    fun build(): List<Tool> = listOf(
        textTool(
            "txt.read", Risk.READ, "read_file",
            "Đọc nội dung file văn bản, cả file hoặc theo khoảng dòng. $FILE_NOTE Khi chỉ định khoảng dòng, kết quả có đánh số dòng.",
            schema {
                str("path", "Đường dẫn file.", required = true)
                int("start_line", "Dòng bắt đầu (tính từ 1).")
                int("end_line", "Dòng kết thúc (gồm cả dòng này).")
                bool("line_numbers", "Có đánh số dòng không (mặc định: có nếu chỉ định khoảng dòng).")
            },
        ) { env, a ->
            val path = a.reqStr("path")
            val doc = TextDoc.parse(env.fs.readText(path))
            val total = doc.lines.size
            if (total == 0) return@textTool "File '${env.fs.display(path)}' trống (0 dòng)."
            val (s, e) = lineRange(a, total)
            val ranged = a.int("start_line") != null || a.int("end_line") != null
            val withNumbers = a.bool("line_numbers") ?: ranged
            val slice = doc.lines.subList(s - 1, e)
            val header = "File '${env.fs.display(path)}' — $total dòng" + if (ranged) ", hiển thị dòng $s–$e" else ""
            header + "\n" + if (withNumbers) numbered(slice, s) else slice.joinToString("\n")
        },

        textTool(
            "txt.read_many", Risk.READ, "read_many_files",
            "Đọc nhiều file văn bản cùng lúc (mỗi file đọc toàn bộ, tự cắt bớt nếu quá dài). $FILE_NOTE",
            schema { strArray("paths", "Danh sách đường dẫn file.", required = true) },
        ) { env, a ->
            val paths = a.strList("paths").orEmpty()
            if (paths.isEmpty()) throw ToolException("Chưa có đường dẫn nào.")
            if (paths.size > 20) throw ToolException("Tối đa 20 file mỗi lần.")
            paths.joinToString("\n\n") { p ->
                try {
                    val text = env.fs.readText(p)
                    val body = if (text.length > 15000) text.take(15000) + "\n…[đã cắt bớt, dùng read_file với khoảng dòng để đọc tiếp]" else text
                    "===== ${env.fs.display(p)} =====\n$body"
                } catch (e: ToolException) {
                    "===== ${env.fs.display(p)} =====\n[Lỗi] ${e.message}"
                }
            }
        },

        textTool(
            "txt.create", Risk.WRITE, "create_file",
            "Tạo file văn bản MỚI (báo lỗi nếu file đã tồn tại; tự tạo thư mục cha).",
            schema {
                str("path", "Đường dẫn file mới.", required = true)
                str("content", "Nội dung file.", required = true)
            },
            preview = { env, a ->
                "Tạo file mới '${env.fs.display(a.reqStr("path"))}' (${a.reqStr("content").lines().size} dòng)\n--- xem trước ---\n" + previewText(a.reqStr("content"))
            },
        ) { env, a ->
            env.fs.writeText(a.reqStr("path"), a.reqStr("content"), mustNotExist = true)
            "Đã tạo '${env.fs.display(a.reqStr("path"))}' (${a.reqStr("content").lines().size} dòng)."
        },

        textTool(
            "txt.write", Risk.WRITE, "write_file",
            "Ghi đè toàn bộ nội dung file (tạo mới nếu chưa có; tự tạo thư mục cha). Cẩn thận: nội dung cũ sẽ mất.",
            schema {
                str("path", "Đường dẫn file.", required = true)
                str("content", "Nội dung mới của file.", required = true)
            },
            preview = { env, a ->
                val p = a.reqStr("path")
                val existing = env.fs.find(p)
                val head = if (existing != null && !existing.isDirectory) {
                    val old = runCatching { TextDoc.parse(env.fs.readText(p)).lines.size }.getOrNull()
                    "GHI ĐÈ '${env.fs.display(p)}'" + (old?.let { " (hiện có $it dòng)" } ?: "")
                } else "Tạo file '${env.fs.display(p)}'"
                "$head\nNội dung mới: ${a.reqStr("content").lines().size} dòng\n--- xem trước ---\n" + previewText(a.reqStr("content"))
            },
        ) { env, a ->
            env.fs.writeText(a.reqStr("path"), a.reqStr("content"))
            "Đã ghi '${env.fs.display(a.reqStr("path"))}' (${a.reqStr("content").lines().size} dòng)."
        },

        textTool(
            "txt.append", Risk.WRITE, "append_to_file",
            "Thêm nội dung vào CUỐI file (tạo file nếu chưa có).",
            schema {
                str("path", "Đường dẫn file.", required = true)
                str("content", "Nội dung cần thêm.", required = true)
            },
            preview = { env, a -> "Thêm vào cuối '${env.fs.display(a.reqStr("path"))}':\n" + previewText(a.reqStr("content")) },
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
            "Đã thêm nội dung vào cuối '${env.fs.display(path)}'."
        },

        textTool(
            "txt.insert_lines", Risk.WRITE, "insert_lines",
            "Chèn một hoặc nhiều dòng vào vị trí cụ thể. Nội dung được chèn TRƯỚC dòng 'line'; đặt line = số dòng + 1 để chèn vào cuối.",
            schema {
                str("path", "Đường dẫn file.", required = true)
                int("line", "Số dòng (từ 1) mà nội dung sẽ nằm trước nó.", required = true)
                str("text", "Nội dung cần chèn (có thể nhiều dòng).", required = true)
            },
            preview = { env, a ->
                "Chèn vào trước dòng ${a.int("line")} của '${env.fs.display(a.reqStr("path"))}':\n" + previewText(a.reqStr("text"))
            },
        ) { env, a ->
            val path = a.reqStr("path")
            val doc = TextDoc.parse(env.fs.readText(path))
            val line = a.int("line") ?: throw ToolException("Thiếu tham số 'line'.")
            if (line < 1 || line > doc.lines.size + 1) throw ToolException("line phải từ 1 đến ${doc.lines.size + 1}.")
            val ins = a.reqStr("text").removeSuffix("\n").split("\n")
            doc.lines.addAll(line - 1, ins)
            if (doc.lines.size == ins.size) doc.trailing = true
            env.fs.writeText(path, doc.render())
            "Đã chèn ${ins.size} dòng vào trước dòng $line của '${env.fs.display(path)}' (giờ có ${doc.lines.size} dòng)."
        },

        textTool(
            "txt.delete_lines", Risk.WRITE, "delete_lines",
            "Xóa một khoảng dòng (từ start_line đến end_line, gồm cả hai đầu) khỏi file.",
            schema {
                str("path", "Đường dẫn file.", required = true)
                int("start_line", "Dòng đầu (từ 1).", required = true)
                int("end_line", "Dòng cuối.", required = true)
            },
            preview = { env, a ->
                val p = a.reqStr("path")
                val doc = TextDoc.parse(env.fs.readText(p))
                val (s, e) = lineRange(a, doc.lines.size)
                "Xóa dòng $s–$e của '${env.fs.display(p)}':\n" + previewText(doc.lines.subList(s - 1, e).joinToString("\n"))
            },
        ) { env, a ->
            val path = a.reqStr("path")
            val doc = TextDoc.parse(env.fs.readText(path))
            val (s, e) = lineRange(a, doc.lines.size)
            for (i in e downTo s) doc.lines.removeAt(i - 1)
            env.fs.writeText(path, doc.render())
            "Đã xóa ${e - s + 1} dòng ($s–$e) khỏi '${env.fs.display(path)}' (còn ${doc.lines.size} dòng)."
        },

        textTool(
            "txt.replace", Risk.WRITE, "replace_in_file",
            "Thay đúng một đoạn văn bản trong file. old_text phải khớp CHÍNH XÁC (kể cả khoảng trắng) và xuất hiện đúng 1 lần, trừ khi replace_all=true.",
            schema {
                str("path", "Đường dẫn file.", required = true)
                str("old_text", "Đoạn cần thay (khớp chính xác).", required = true)
                str("new_text", "Đoạn thay thế (có thể rỗng để xóa).", required = true)
                bool("replace_all", "Thay mọi chỗ xuất hiện (mặc định false).")
            },
            preview = { env, a ->
                val text = env.fs.readText(a.reqStr("path"))
                val count = text.split(a.reqStr("old_text")).size - 1
                "Sửa '${env.fs.display(a.reqStr("path"))}' ($count chỗ khớp)\n--- THAY ---\n${previewText(a.reqStr("old_text"), 8)}\n--- BẰNG ---\n${previewText(a.reqStr("new_text"), 8)}"
            },
        ) { env, a ->
            val path = a.reqStr("path")
            val old = a.reqStr("old_text")
            val new = a.str("new_text") ?: throw ToolException("Thiếu tham số 'new_text'.")
            if (old.isEmpty()) throw ToolException("old_text không được rỗng.")
            val text = env.fs.readText(path)
            val count = text.split(old).size - 1
            if (count == 0) throw ToolException("Không tìm thấy old_text trong '${env.fs.display(path)}'. Hãy đọc lại file và sao chép chính xác đoạn cần sửa.")
            val all = a.bool("replace_all") == true
            if (count > 1 && !all) throw ToolException("old_text xuất hiện $count lần. Hãy đưa thêm ngữ cảnh xung quanh để nó duy nhất, hoặc đặt replace_all=true.")
            env.fs.writeText(path, if (all) text.replace(old, new) else text.replaceFirst(old, new))
            "Đã thay ${if (all) count else 1} chỗ trong '${env.fs.display(path)}'."
        },

        textTool(
            "txt.replace_many", Risk.WRITE, "find_replace_many",
            "Tìm và thay thế hàng loạt trong nhiều file văn bản. Chỉ định danh sách 'paths' hoặc thư mục 'directory' (kèm 'file_pattern'). Dùng dry_run=true để xem trước số chỗ sẽ đổi.",
            schema {
                str("find", "Chuỗi (hoặc biểu thức chính quy nếu regex=true) cần tìm.", required = true)
                str("replace", "Chuỗi thay thế.", required = true)
                strArray("paths", "Danh sách file cụ thể.")
                str("directory", "Hoặc: thư mục để thay trong mọi file văn bản bên trong (đệ quy).")
                str("file_pattern", "Với directory: chỉ các file có tên khớp mẫu, ví dụ '*.md'.")
                bool("regex", "Coi 'find' là biểu thức chính quy (mặc định false).")
                bool("case_sensitive", "Phân biệt hoa thường (mặc định true).")
                bool("dry_run", "Chỉ báo cáo, không sửa file (mặc định false).")
            },
            preview = { env, a -> findReplaceMany(env, a, forceDry = true) },
        ) { env, a -> findReplaceMany(env, a, forceDry = false) },

        textTool(
            "txt.diff", Risk.READ, "compare_files",
            "So sánh hai file văn bản theo từng dòng và liệt kê các dòng khác nhau ('-' chỉ có ở file A, '+' chỉ có ở file B).",
            schema {
                str("path_a", "File thứ nhất.", required = true)
                str("path_b", "File thứ hai.", required = true)
            },
        ) { env, a ->
            val la = TextDoc.parse(env.fs.readText(a.reqStr("path_a"))).lines
            val lb = TextDoc.parse(env.fs.readText(a.reqStr("path_b"))).lines
            if (la.size.toLong() * lb.size > 6_000_000L) throw ToolException("Hai file quá lớn để so sánh chi tiết.")
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
            if (diffs == 0) "Hai file giống hệt nhau."
            else "Có $diffs dòng khác nhau (A=${env.fs.display(a.reqStr("path_a"))}, B=${env.fs.display(a.reqStr("path_b"))}):\n" + out.toString().trimEnd()
        },

        textTool(
            "txt.count", Risk.READ, "count_text",
            "Đếm số từ, số dòng, số ký tự và số byte của một file văn bản.",
            schema { str("path", "Đường dẫn file.", required = true) },
        ) { env, a ->
            val text = env.fs.readText(a.reqStr("path"))
            val lines = TextDoc.parse(text).lines.size
            val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
            "File '${env.fs.display(a.reqStr("path"))}': $lines dòng, $words từ, ${text.length} ký tự, ${text.toByteArray().size} byte."
        },
    )

    private fun findReplaceMany(env: ToolEnv, a: JsonObject, forceDry: Boolean): String {
        val find = a.reqStr("find")
        val replace = a.str("replace") ?: throw ToolException("Thiếu tham số 'replace'.")
        if (find.isEmpty()) throw ToolException("'find' không được rỗng.")
        val dry = forceDry || a.bool("dry_run") == true
        val caseSensitive = a.bool("case_sensitive") ?: true
        val useRegex = a.bool("regex") == true
        val rx = try {
            Regex(if (useRegex) find else Regex.escape(find), if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            throw ToolException("Biểu thức chính quy không hợp lệ: ${e.message}")
        }
        val targets = mutableListOf<String>()
        val explicit = a.strList("paths").orEmpty()
        if (explicit.isNotEmpty()) {
            targets.addAll(explicit)
        } else {
            val dirPath = a.str("directory") ?: throw ToolException("Cần 'paths' hoặc 'directory'.")
            val fileRx = a.str("file_pattern")?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
            val base = env.fs.segments(dirPath).joinToString("/")
            env.fs.walk(env.fs.requireDir(dirPath), base, Int.MAX_VALUE) { d, p, _ ->
                if (!d.isDirectory && (fileRx == null || fileRx.matches(d.name ?: "")) && env.fs.isTextFile(d)) targets.add(p)
                targets.size < 2000
            }
        }
        if (targets.isEmpty()) return "Không có file nào để xử lý."
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
                    report.append("• ${env.fs.display(p)}: $hits chỗ\n")
                    if (!dry) env.fs.writeText(p, if (useRegex) rx.replace(text, replace) else rx.replace(text, java.util.regex.Matcher.quoteReplacement(replace)))
                }
            } catch (e: ToolException) {
                report.append("• ${env.fs.display(p)}: bỏ qua (${e.message})\n")
            }
        }
        val head = if (dry) "XEM TRƯỚC: sẽ thay $totalHits chỗ trong $changedFiles/${targets.size} file." else "Đã thay $totalHits chỗ trong $changedFiles/${targets.size} file."
        return head + "\n" + report.toString().trimEnd()
    }
}
