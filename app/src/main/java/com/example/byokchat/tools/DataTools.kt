package com.example.byokchat.tools

import kotlinx.serialization.json.*
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.util.Locale

/** Bộ đọc/ghi CSV có xử lý dấu ngoặc kép. */
object Csv {
    fun detectDelimiter(text: String): Char {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        return listOf(',', ';', '\t', '|').maxByOrNull { d -> first.count { it == d } }
            ?.takeIf { d -> first.contains(d) } ?: ','
    }

    fun parse(text: String, delim: Char): MutableList<MutableList<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        var any = false
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else inQuotes = false
                } else cell.append(c)
            } else when {
                c == '"' -> { inQuotes = true; any = true }
                c == delim -> { row.add(cell.toString()); cell.clear(); any = true }
                c == '\r' -> {}
                c == '\n' -> {
                    if (any || cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
                    row = mutableListOf(); cell.clear(); any = false
                }
                else -> { cell.append(c); any = true }
            }
            i++
        }
        if (any || cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    fun render(rows: List<List<String>>, delim: Char, eol: String): String =
        rows.joinToString(eol) { r -> r.joinToString(delim.toString()) { quote(it, delim) } } + eol

    private fun quote(s: String, delim: Char): String =
        if (s.contains(delim) || s.contains('"') || s.contains('\n') || s.contains('\r')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

private class CsvFile(val rows: MutableList<MutableList<String>>, val delim: Char, val eol: String) {
    val header: List<String> get() = rows.firstOrNull() ?: emptyList()
    val data: List<List<String>> get() = if (rows.size > 1) rows.subList(1, rows.size) else emptyList()

    fun colIndex(col: String): Int {
        col.toIntOrNull()?.let { if (it in 1..header.size) return it - 1 }
        val i = header.indexOfFirst { it.equals(col.trim(), ignoreCase = true) }
        if (i < 0) throw ToolException("Không có cột '$col'. Các cột hiện có: ${header.joinToString(", ")}")
        return i
    }

    fun save(env: ToolEnv, path: String) = env.fs.writeText(path, Csv.render(rows, delim, eol))
}

private fun loadCsv(env: ToolEnv, path: String, delimArg: String?): CsvFile {
    val text = env.fs.readText(path)
    val delim = delimArg?.let { if (it == "\\t" || it.equals("tab", true)) '\t' else it.firstOrNull() } ?: Csv.detectDelimiter(text)
    val rows = Csv.parse(text, delim)
    if (rows.isEmpty()) throw ToolException("File CSV '${env.fs.display(path)}' trống.")
    return CsvFile(rows, delim, if (text.contains("\r\n")) "\r\n" else "\n")
}

private fun table(header: List<String>, rows: List<Pair<Int, List<String>>>): String {
    val sb = StringBuilder("# | " + header.joinToString(" | ") + "\n")
    rows.forEach { (n, r) -> sb.append("$n | ").append(header.indices.joinToString(" | ") { r.getOrElse(it) { "" }.replace("\n", "⏎") }).append('\n') }
    return sb.toString().trimEnd()
}

private fun fmtNum(d: Double): String =
    if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else String.format(Locale.US, "%.6g", d).trimEnd('0').trimEnd('.')

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val jsonPretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

// ----- Đường dẫn JSON: a.b[0].c -----
private fun jsonTokens(path: String): List<Any> {
    val out = mutableListOf<Any>()
    val p = path.trim().removePrefix("$").removePrefix(".")
    if (p.isEmpty()) return out
    val rx = Regex("""([^.\[\]]+)|\[(\d+)]""")
    for (m in rx.findAll(p)) {
        val key = m.groupValues[1]
        if (key.isNotEmpty()) out.add(key) else out.add(m.groupValues[2].toInt())
    }
    return out
}

private fun jsonGet(root: JsonElement, tokens: List<Any>): JsonElement {
    var cur = root
    for (t in tokens) {
        cur = when {
            t is String && cur is JsonObject -> cur[t] ?: throw ToolException("Không có khóa '$t'.")
            t is Int && cur is JsonArray -> cur.getOrNull(t) ?: throw ToolException("Chỉ số [$t] vượt quá độ dài mảng (${cur.size}).")
            else -> throw ToolException("Không thể truy cập '$t' trong giá trị kiểu ${cur::class.simpleName}.")
        }
    }
    return cur
}

private fun jsonSet(cur: JsonElement?, tokens: List<Any>, idx: Int, value: JsonElement): JsonElement {
    if (idx == tokens.size) return value
    val t = tokens[idx]
    return when (t) {
        is String -> {
            val obj = (cur as? JsonObject) ?: if (cur == null || cur is JsonNull) JsonObject(emptyMap()) else throw ToolException("'${tokens.take(idx).joinToString(".")}' không phải object nên không thể đặt khóa '$t'.")
            JsonObject(obj + (t to jsonSet(obj[t], tokens, idx + 1, value)))
        }
        else -> {
            val i = t as Int
            val arr = (cur as? JsonArray) ?: if (cur == null || cur is JsonNull) JsonArray(emptyList()) else throw ToolException("Giá trị tại vị trí này không phải mảng nên không thể dùng chỉ số [$i].")
            if (i > arr.size) throw ToolException("Chỉ số [$i] vượt quá độ dài mảng (${arr.size}).")
            val list = arr.toMutableList()
            if (i == list.size) list.add(jsonSet(null, tokens, idx + 1, value)) else list[i] = jsonSet(list[i], tokens, idx + 1, value)
            JsonArray(list)
        }
    }
}

private fun parseJson(text: String, label: String): JsonElement =
    try {
        Json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw ToolException("JSON không hợp lệ trong '$label': ${e.message?.lineSequence()?.firstOrNull()}")
    }

private const val CSV_NOTE = "Dòng đầu tiên là tiêu đề cột. Cột có thể chỉ định bằng tên hoặc số thứ tự (từ 1). Số dòng dữ liệu tính từ 1 (không tính dòng tiêu đề)."

/** Nhóm 2 (phần dữ liệu): CSV, JSON, chuyển đổi định dạng. */
object DataTools {
    fun build(): List<Tool> = listOf(
        textTool("csv.read", Risk.READ, "csv_read", "Đọc file CSV dạng bảng. $CSV_NOTE",
            schema {
                str("path", "Đường dẫn file CSV.", required = true)
                int("offset", "Bỏ qua bấy nhiêu dòng dữ liệu đầu (mặc định 0).")
                int("limit", "Số dòng dữ liệu tối đa (mặc định 50).")
                str("delimiter", "Ký tự phân cách (mặc định: tự nhận).")
            }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), a.str("delimiter"))
            val off = (a.int("offset") ?: 0).coerceAtLeast(0)
            val lim = (a.int("limit") ?: 50).coerceIn(1, 500)
            val slice = f.data.drop(off).take(lim).mapIndexed { i, r -> (off + i + 1) to r }
            "CSV '${env.fs.display(a.reqStr("path"))}': ${f.header.size} cột, ${f.data.size} dòng dữ liệu; hiển thị ${slice.size} dòng từ dòng ${off + 1}\n" + table(f.header, slice)
        },

        textTool("csv.filter", Risk.READ, "csv_filter", "Lọc các dòng CSV theo điều kiện trên một cột. $CSV_NOTE",
            schema {
                str("path", "Đường dẫn file CSV.", required = true)
                str("column", "Tên hoặc số thứ tự cột.", required = true)
                str("op", "Phép so sánh.", required = true, enum = listOf("eq", "ne", "contains", "gt", "ge", "lt", "le", "starts_with", "ends_with", "empty", "not_empty"))
                str("value", "Giá trị so sánh (không cần với empty/not_empty).")
                int("limit", "Số dòng kết quả tối đa (mặc định 50).")
            }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), null)
            val ci = f.colIndex(a.reqStr("column"))
            val op = a.reqStr("op")
            val v = a.str("value").orEmpty()
            val vn = v.replace(",", ".").toDoubleOrNull()
            val lim = (a.int("limit") ?: 50).coerceIn(1, 500)
            val matches = f.data.mapIndexed { i, r -> (i + 1) to r }.filter { (_, r) ->
                val cell = r.getOrElse(ci) { "" }
                val cn = cell.replace(",", ".").toDoubleOrNull()
                fun cmp(): Int? = if (cn != null && vn != null) cn.compareTo(vn) else if (op in setOf("gt", "ge", "lt", "le")) cell.compareTo(v) else null
                when (op) {
                    "eq" -> if (cn != null && vn != null) cn == vn else cell.equals(v, true)
                    "ne" -> if (cn != null && vn != null) cn != vn else !cell.equals(v, true)
                    "contains" -> cell.contains(v, true)
                    "starts_with" -> cell.startsWith(v, true)
                    "ends_with" -> cell.endsWith(v, true)
                    "empty" -> cell.isBlank()
                    "not_empty" -> cell.isNotBlank()
                    "gt" -> (cmp() ?: 0) > 0
                    "ge" -> (cmp() ?: 0) >= 0
                    "lt" -> (cmp() ?: 0) < 0
                    "le" -> (cmp() ?: 0) <= 0
                    else -> throw ToolException("Phép so sánh '$op' không hợp lệ.")
                }
            }
            "Có ${matches.size} dòng khớp (hiển thị ${minOf(lim, matches.size)}):\n" + table(f.header, matches.take(lim))
        },

        textTool("csv.sort", Risk.READ, "csv_sort", "Sắp xếp dòng CSV theo một cột và hiển thị kết quả (KHÔNG sửa file). Cột toàn số được sắp theo giá trị số. $CSV_NOTE",
            schema {
                str("path", "Đường dẫn file CSV.", required = true)
                str("column", "Tên hoặc số thứ tự cột.", required = true)
                bool("descending", "Sắp giảm dần (mặc định false).")
                int("limit", "Số dòng hiển thị (mặc định 50).")
            }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), null)
            val ci = f.colIndex(a.reqStr("column"))
            val numeric = f.data.all { r -> r.getOrElse(ci) { "" }.let { it.isBlank() || it.replace(",", ".").toDoubleOrNull() != null } }
            val indexed = f.data.mapIndexed { i, r -> (i + 1) to r }
            var sorted = if (numeric) indexed.sortedBy { (_, r) -> r.getOrElse(ci) { "" }.replace(",", ".").toDoubleOrNull() ?: Double.NEGATIVE_INFINITY }
            else indexed.sortedBy { (_, r) -> r.getOrElse(ci) { "" }.lowercase() }
            if (a.bool("descending") == true) sorted = sorted.reversed()
            val lim = (a.int("limit") ?: 50).coerceIn(1, 500)
            "Sắp xếp theo '${f.header[ci]}' (${if (numeric) "số" else "chữ"}, ${if (a.bool("descending") == true) "giảm" else "tăng"} dần), hiển thị ${minOf(lim, sorted.size)}/${sorted.size} dòng (cột # là số dòng gốc):\n" + table(f.header, sorted.take(lim))
        },

        textTool("csv.stats", Risk.READ, "csv_stats", "Thống kê CSV: với cột số cho đếm/nhỏ nhất/lớn nhất/tổng/trung bình, với cột chữ cho số giá trị khác nhau và giá trị phổ biến. Bỏ trống 'column' để thống kê mọi cột. $CSV_NOTE",
            schema {
                str("path", "Đường dẫn file CSV.", required = true)
                str("column", "Tên hoặc số thứ tự cột (bỏ trống = tất cả).")
            }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), null)
            val cols = a.str("column")?.let { listOf(f.colIndex(it)) } ?: f.header.indices.toList()
            val sb = StringBuilder("CSV '${env.fs.display(a.reqStr("path"))}': ${f.data.size} dòng, ${f.header.size} cột\n")
            for (ci in cols) {
                val vals = f.data.map { it.getOrElse(ci) { "" } }
                val nonEmpty = vals.filter { it.isNotBlank() }
                val nums = nonEmpty.mapNotNull { it.replace(",", ".").toDoubleOrNull() }
                sb.append("\n[${f.header[ci]}] có giá trị: ${nonEmpty.size}, trống: ${vals.size - nonEmpty.size}, khác nhau: ${nonEmpty.toSet().size}\n")
                if (nonEmpty.isNotEmpty() && nums.size == nonEmpty.size) {
                    sb.append("  số: nhỏ nhất ${fmtNum(nums.min())}, lớn nhất ${fmtNum(nums.max())}, tổng ${fmtNum(nums.sum())}, trung bình ${fmtNum(nums.average())}\n")
                } else if (nonEmpty.isNotEmpty()) {
                    val top = nonEmpty.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
                    sb.append("  phổ biến: " + top.joinToString("; ") { "${it.key} (${it.value})" } + "\n")
                }
            }
            sb.toString().trimEnd()
        },

        textTool("csv.append_row", Risk.WRITE, "csv_add_row", "Thêm một dòng vào cuối file CSV. 'values' là các giá trị theo thứ tự cột. $CSV_NOTE",
            schema {
                str("path", "Đường dẫn file CSV.", required = true)
                strArray("values", "Giá trị các ô theo thứ tự cột.", required = true)
            },
            preview = { env, a -> "Thêm dòng vào '${env.fs.display(a.reqStr("path"))}':\n" + a.strList("values").orEmpty().joinToString(" | ") }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), null)
            val vals = a.strList("values").orEmpty().toMutableList()
            if (vals.size > f.header.size) throw ToolException("Có ${vals.size} giá trị nhưng file chỉ có ${f.header.size} cột.")
            while (vals.size < f.header.size) vals.add("")
            f.rows.add(vals)
            f.save(env, a.reqStr("path"))
            "Đã thêm dòng dữ liệu số ${f.data.size} vào '${env.fs.display(a.reqStr("path"))}'."
        },

        textTool("csv.update_cell", Risk.WRITE, "csv_set_cell", "Sửa một ô trong file CSV. $CSV_NOTE",
            schema {
                str("path", "Đường dẫn file CSV.", required = true)
                int("row", "Số dòng dữ liệu (từ 1, không tính tiêu đề).", required = true)
                str("column", "Tên hoặc số thứ tự cột.", required = true)
                str("value", "Giá trị mới.", required = true)
            },
            preview = { env, a ->
                val f = loadCsv(env, a.reqStr("path"), null)
                val r = a.int("row") ?: 0
                val ci = f.colIndex(a.reqStr("column"))
                val old = f.data.getOrNull(r - 1)?.getOrElse(ci) { "" } ?: "(không có dòng này)"
                "Sửa ô [dòng $r, cột ${f.header[ci]}] của '${env.fs.display(a.reqStr("path"))}':\n$old  →  ${a.str("value").orEmpty()}"
            }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), null)
            val r = a.int("row") ?: throw ToolException("Thiếu tham số 'row'.")
            if (r < 1 || r > f.data.size) throw ToolException("Dòng $r không tồn tại (file có ${f.data.size} dòng dữ liệu).")
            val ci = f.colIndex(a.reqStr("column"))
            val row = f.rows[r]
            while (row.size <= ci) row.add("")
            val old = row[ci]
            row[ci] = a.str("value").orEmpty()
            f.save(env, a.reqStr("path"))
            "Đã sửa ô [dòng $r, cột ${f.header[ci]}]: '$old' → '${row[ci]}'."
        },

        textTool("json.validate", Risk.READ, "json_validate", "Kiểm tra file có phải JSON hợp lệ không.",
            schema { str("path", "Đường dẫn file JSON.", required = true) }) { env, a ->
            val el = parseJson(env.fs.readText(a.reqStr("path")), env.fs.display(a.reqStr("path")))
            val kind = when (el) { is JsonObject -> "object (${el.size} khóa)"; is JsonArray -> "mảng (${el.size} phần tử)"; else -> "giá trị đơn" }
            "JSON hợp lệ. Gốc là $kind."
        },

        textTool("json.format", Risk.WRITE, "json_format", "Định dạng lại (làm đẹp, thụt lề 2 khoảng trắng) file JSON và ghi đè lên chính file đó.",
            schema { str("path", "Đường dẫn file JSON.", required = true) },
            preview = { env, a -> "Định dạng lại và ghi đè '${env.fs.display(a.reqStr("path"))}'" }) { env, a ->
            val el = parseJson(env.fs.readText(a.reqStr("path")), env.fs.display(a.reqStr("path")))
            env.fs.writeText(a.reqStr("path"), jsonPretty.encodeToString(JsonElement.serializer(), el) + "\n")
            "Đã định dạng lại '${env.fs.display(a.reqStr("path"))}'."
        },

        textTool("json.get", Risk.READ, "json_get", "Lấy một giá trị trong file JSON theo đường dẫn khóa, ví dụ 'users[0].name'. Bỏ trống để lấy toàn bộ.",
            schema {
                str("path", "Đường dẫn file JSON.", required = true)
                str("pointer", "Đường dẫn khóa, ví dụ 'a.b[2].c'.")
            }) { env, a ->
            val root = parseJson(env.fs.readText(a.reqStr("path")), env.fs.display(a.reqStr("path")))
            val v = jsonGet(root, jsonTokens(a.str("pointer").orEmpty()))
            jsonPretty.encodeToString(JsonElement.serializer(), v)
        },

        textTool("json.set", Risk.WRITE, "json_set", "Đặt/sửa một giá trị trong file JSON theo đường dẫn khóa (tự tạo khóa còn thiếu). 'value_json' là giá trị dạng JSON, ví dụ \"abc\" (có ngoặc kép), 42, true, {\"a\":1}. File được ghi lại với thụt lề 2 khoảng trắng.",
            schema {
                str("path", "Đường dẫn file JSON.", required = true)
                str("pointer", "Đường dẫn khóa, ví dụ 'a.b[2].c'.", required = true)
                str("value_json", "Giá trị mới dạng JSON.", required = true)
            },
            preview = { env, a -> "Sửa '${env.fs.display(a.reqStr("path"))}':\n${a.reqStr("pointer")} = ${a.reqStr("value_json").take(300)}" }) { env, a ->
            val path = a.reqStr("path")
            val root = parseJson(env.fs.readText(path), env.fs.display(path))
            val raw = a.reqStr("value_json")
            val value = runCatching { Json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
            val tokens = jsonTokens(a.reqStr("pointer"))
            val updated = jsonSet(root, tokens, 0, value)
            env.fs.writeText(path, jsonPretty.encodeToString(JsonElement.serializer(), updated) + "\n")
            "Đã đặt '${a.reqStr("pointer")}' trong '${env.fs.display(path)}'."
        },

        textTool("convert.csv_to_json", Risk.WRITE, "csv_to_json", "Chuyển file CSV thành file JSON (mảng các object, khóa lấy từ dòng tiêu đề, mọi giá trị là chuỗi).",
            schema {
                str("path", "File CSV nguồn.", required = true)
                str("output_path", "File JSON đầu ra (mặc định: cùng tên, đuôi .json).")
            },
            preview = { env, a -> "Chuyển '${env.fs.display(a.reqStr("path"))}' thành JSON → '${env.fs.display(a.str("output_path") ?: a.reqStr("path").substringBeforeLast('.') + ".json")}'" }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), null)
            val arr = JsonArray(f.data.map { r -> JsonObject(f.header.indices.associate { f.header[it] to JsonPrimitive(r.getOrElse(it) { "" }) }) })
            val out = a.str("output_path") ?: (a.reqStr("path").substringBeforeLast('.') + ".json")
            env.fs.writeText(out, jsonPretty.encodeToString(JsonElement.serializer(), arr) + "\n")
            "Đã chuyển ${f.data.size} dòng sang '${env.fs.display(out)}'."
        },

        textTool("convert.json_to_csv", Risk.WRITE, "json_to_csv", "Chuyển file JSON (mảng các object phẳng) thành file CSV.",
            schema {
                str("path", "File JSON nguồn.", required = true)
                str("output_path", "File CSV đầu ra (mặc định: cùng tên, đuôi .csv).")
            },
            preview = { env, a -> "Chuyển '${env.fs.display(a.reqStr("path"))}' thành CSV → '${env.fs.display(a.str("output_path") ?: a.reqStr("path").substringBeforeLast('.') + ".csv")}'" }) { env, a ->
            val root = parseJson(env.fs.readText(a.reqStr("path")), env.fs.display(a.reqStr("path")))
            val arr = root as? JsonArray ?: throw ToolException("Gốc JSON phải là một mảng các object.")
            val objs = arr.map { it as? JsonObject ?: throw ToolException("Mỗi phần tử trong mảng phải là một object.") }
            val keys = LinkedHashSet<String>().apply { objs.forEach { addAll(it.keys) } }.toList()
            fun cell(e: JsonElement?): String = when (e) {
                null, is JsonNull -> ""
                is JsonPrimitive -> e.content
                else -> e.toString()
            }
            val rows = mutableListOf<List<String>>(keys)
            objs.forEach { o -> rows.add(keys.map { cell(o[it]) }) }
            val out = a.str("output_path") ?: (a.reqStr("path").substringBeforeLast('.') + ".csv")
            env.fs.writeText(out, Csv.render(rows, ',', "\n"))
            "Đã chuyển ${objs.size} object (${keys.size} cột) sang '${env.fs.display(out)}'."
        },

        textTool("convert.md_to_html", Risk.WRITE, "markdown_to_html", "Chuyển file Markdown thành trang HTML hoàn chỉnh (hỗ trợ bảng, gạch ngang chữ).",
            schema {
                str("path", "File Markdown nguồn.", required = true)
                str("output_path", "File HTML đầu ra (mặc định: cùng tên, đuôi .html).")
            },
            preview = { env, a -> "Chuyển '${env.fs.display(a.reqStr("path"))}' thành HTML → '${env.fs.display(a.str("output_path") ?: a.reqStr("path").substringBeforeLast('.') + ".html")}'" }) { env, a ->
            val md = env.fs.readText(a.reqStr("path"))
            val exts = listOf(TablesExtension.create(), StrikethroughExtension.create())
            val parser = Parser.builder().extensions(exts).build()
            val body = HtmlRenderer.builder().extensions(exts).build().render(parser.parse(md))
            val title = md.lineSequence().firstOrNull { it.startsWith("#") }?.trimStart('#', ' ') ?: a.reqStr("path").substringAfterLast('/')
            val html = "<!DOCTYPE html>\n<html lang=\"vi\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>" +
                title.replace("<", "&lt;") + "</title>\n<style>body{font-family:sans-serif;max-width:780px;margin:2rem auto;padding:0 1rem;line-height:1.6}" +
                "pre,code{background:#f3f3f3;border-radius:4px}pre{padding:.8rem;overflow:auto}code{padding:.1rem .3rem}table{border-collapse:collapse}" +
                "td,th{border:1px solid #ccc;padding:.3rem .6rem}blockquote{border-left:4px solid #ccc;margin-left:0;padding-left:1rem;color:#555}</style>\n</head>\n<body>\n" +
                body + "</body>\n</html>\n"
            val out = a.str("output_path") ?: (a.reqStr("path").substringBeforeLast('.') + ".html")
            env.fs.writeText(out, html)
            "Đã tạo '${env.fs.display(out)}'."
        },
    )
}
