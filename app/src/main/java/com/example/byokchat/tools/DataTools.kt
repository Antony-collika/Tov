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
        if (i < 0) throw ToolException("No column '$col'. Available columns: ${header.joinToString(", ")}")
        return i
    }

    fun save(env: ToolEnv, path: String) = env.fs.writeText(path, Csv.render(rows, delim, eol))
}

private fun loadCsv(env: ToolEnv, path: String, delimArg: String?): CsvFile {
    val text = env.fs.readText(path)
    val delim = delimArg?.let { if (it == "\\t" || it.equals("tab", true)) '\t' else it.firstOrNull() } ?: Csv.detectDelimiter(text)
    val rows = Csv.parse(text, delim)
    if (rows.isEmpty()) throw ToolException("CSV file '${env.fs.display(path)}' is empty.")
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
            t is String && cur is JsonObject -> cur[t] ?: throw ToolException("No key '$t'.")
            t is Int && cur is JsonArray -> cur.getOrNull(t) ?: throw ToolException("Index [$t] is beyond the array length (${cur.size}).")
            else -> throw ToolException("Cannot access '$t' in a value of type ${cur::class.simpleName}.")
        }
    }
    return cur
}

private fun jsonSet(cur: JsonElement?, tokens: List<Any>, idx: Int, value: JsonElement): JsonElement {
    if (idx == tokens.size) return value
    val t = tokens[idx]
    return when (t) {
        is String -> {
            val obj = (cur as? JsonObject) ?: if (cur == null || cur is JsonNull) JsonObject(emptyMap()) else throw ToolException("'${tokens.take(idx).joinToString(".")}' is not an object, so key '$t' cannot be set.")
            JsonObject(obj + (t to jsonSet(obj[t], tokens, idx + 1, value)))
        }
        else -> {
            val i = t as Int
            val arr = (cur as? JsonArray) ?: if (cur == null || cur is JsonNull) JsonArray(emptyList()) else throw ToolException("The value at this position is not an array, so index [$i] cannot be used.")
            if (i > arr.size) throw ToolException("Index [$i] is beyond the array length (${arr.size}).")
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
        throw ToolException("Invalid JSON in '$label': ${e.message?.lineSequence()?.firstOrNull()}")
    }

private const val CSV_NOTE = "The first row is the column header. A column can be given by name or by position (1-based). Data row numbers start at 1 (the header row is not counted)."

/** Nhóm 2 (phần dữ liệu): CSV, JSON, chuyển đổi định dạng. */
object DataTools {
    fun build(): List<Tool> = listOf(
        textTool("csv.read", Risk.READ, "csv_read", "Read a CSV file as a table. $CSV_NOTE",
            schema {
                str("path", "CSV file path.", required = true)
                int("offset", "Skip this many data rows from the start (default 0).")
                int("limit", "Maximum number of data rows (default 50).")
                str("delimiter", "Delimiter character (default: auto-detect).")
            }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), a.str("delimiter"))
            val off = (a.int("offset") ?: 0).coerceAtLeast(0)
            val lim = (a.int("limit") ?: 50).coerceIn(1, 500)
            val slice = f.data.drop(off).take(lim).mapIndexed { i, r -> (off + i + 1) to r }
            "CSV '${env.fs.display(a.reqStr("path"))}': ${f.header.size} columns, ${f.data.size} data rows; showing ${slice.size} rows starting at row ${off + 1}\n" + table(f.header, slice)
        },

        textTool("csv.filter", Risk.READ, "csv_filter", "Filter CSV rows by a condition on one column. $CSV_NOTE",
            schema {
                str("path", "CSV file path.", required = true)
                str("column", "Column name or position.", required = true)
                str("op", "Comparison operator.", required = true, enum = listOf("eq", "ne", "contains", "gt", "ge", "lt", "le", "starts_with", "ends_with", "empty", "not_empty"))
                str("value", "Value to compare with (not needed for empty/not_empty).")
                int("limit", "Maximum number of result rows (default 50).")
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
                    else -> throw ToolException("Invalid operator '$op'.")
                }
            }
            "${matches.size} rows match (showing ${minOf(lim, matches.size)}):\n" + table(f.header, matches.take(lim))
        },

        textTool("csv.sort", Risk.READ, "csv_sort", "Sort CSV rows by one column and show the result (does NOT modify the file). Columns containing only numbers are sorted numerically. $CSV_NOTE",
            schema {
                str("path", "CSV file path.", required = true)
                str("column", "Column name or position.", required = true)
                bool("descending", "Sort descending (default false).")
                int("limit", "Number of rows to show (default 50).")
            }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), null)
            val ci = f.colIndex(a.reqStr("column"))
            val numeric = f.data.all { r -> r.getOrElse(ci) { "" }.let { it.isBlank() || it.replace(",", ".").toDoubleOrNull() != null } }
            val indexed = f.data.mapIndexed { i, r -> (i + 1) to r }
            var sorted = if (numeric) indexed.sortedBy { (_, r) -> r.getOrElse(ci) { "" }.replace(",", ".").toDoubleOrNull() ?: Double.NEGATIVE_INFINITY }
            else indexed.sortedBy { (_, r) -> r.getOrElse(ci) { "" }.lowercase() }
            if (a.bool("descending") == true) sorted = sorted.reversed()
            val lim = (a.int("limit") ?: 50).coerceIn(1, 500)
            "Sorted by '${f.header[ci]}' (${if (numeric) "numeric" else "text"}, ${if (a.bool("descending") == true) "descending" else "ascending"}), showing ${minOf(lim, sorted.size)}/${sorted.size} rows (column # is the original row number):\n" + table(f.header, sorted.take(lim))
        },

        textTool("csv.stats", Risk.READ, "csv_stats", "CSV statistics: for numeric columns gives count/min/max/sum/average, for text columns gives the number of distinct values and the most common ones. Leave 'column' empty to cover every column. $CSV_NOTE",
            schema {
                str("path", "CSV file path.", required = true)
                str("column", "Column name or position (empty = all columns).")
            }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), null)
            val cols = a.str("column")?.let { listOf(f.colIndex(it)) } ?: f.header.indices.toList()
            val sb = StringBuilder("CSV '${env.fs.display(a.reqStr("path"))}': ${f.data.size} rows, ${f.header.size} columns\n")
            for (ci in cols) {
                val vals = f.data.map { it.getOrElse(ci) { "" } }
                val nonEmpty = vals.filter { it.isNotBlank() }
                val nums = nonEmpty.mapNotNull { it.replace(",", ".").toDoubleOrNull() }
                sb.append("\n[${f.header[ci]}] values: ${nonEmpty.size}, empty: ${vals.size - nonEmpty.size}, distinct: ${nonEmpty.toSet().size}\n")
                if (nonEmpty.isNotEmpty() && nums.size == nonEmpty.size) {
                    sb.append("  numeric: min ${fmtNum(nums.min())}, max ${fmtNum(nums.max())}, sum ${fmtNum(nums.sum())}, average ${fmtNum(nums.average())}\n")
                } else if (nonEmpty.isNotEmpty()) {
                    val top = nonEmpty.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
                    sb.append("  most common: " + top.joinToString("; ") { "${it.key} (${it.value})" } + "\n")
                }
            }
            sb.toString().trimEnd()
        },

        textTool("csv.append_row", Risk.WRITE, "csv_add_row", "Append a row to the end of a CSV file. 'values' are the cell values in column order. $CSV_NOTE",
            schema {
                str("path", "CSV file path.", required = true)
                strArray("values", "Cell values in column order.", required = true)
            },
            preview = { env, a -> "Add row to '${env.fs.display(a.reqStr("path"))}':\n" + a.strList("values").orEmpty().joinToString(" | ") }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), null)
            val vals = a.strList("values").orEmpty().toMutableList()
            if (vals.size > f.header.size) throw ToolException("${vals.size} values were given but the file has only ${f.header.size} columns.")
            while (vals.size < f.header.size) vals.add("")
            f.rows.add(vals)
            f.save(env, a.reqStr("path"))
            "Added data row ${f.data.size} to '${env.fs.display(a.reqStr("path"))}'."
        },

        textTool("csv.update_cell", Risk.WRITE, "csv_set_cell", "Edit one cell in a CSV file. $CSV_NOTE",
            schema {
                str("path", "CSV file path.", required = true)
                int("row", "Data row number (1-based, header not counted).", required = true)
                str("column", "Column name or position.", required = true)
                str("value", "New value.", required = true)
            },
            preview = { env, a ->
                val f = loadCsv(env, a.reqStr("path"), null)
                val r = a.int("row") ?: 0
                val ci = f.colIndex(a.reqStr("column"))
                val old = f.data.getOrNull(r - 1)?.getOrElse(ci) { "" } ?: "(no such row)"
                "Edit cell [row $r, column ${f.header[ci]}] of '${env.fs.display(a.reqStr("path"))}':\n$old  →  ${a.str("value").orEmpty()}"
            }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), null)
            val r = a.int("row") ?: throw ToolException("Missing parameter 'row'.")
            if (r < 1 || r > f.data.size) throw ToolException("Row $r does not exist (the file has ${f.data.size} data rows).")
            val ci = f.colIndex(a.reqStr("column"))
            val row = f.rows[r]
            while (row.size <= ci) row.add("")
            val old = row[ci]
            row[ci] = a.str("value").orEmpty()
            f.save(env, a.reqStr("path"))
            "Edited cell [row $r, column ${f.header[ci]}]: '$old' → '${row[ci]}'."
        },

        textTool("json.validate", Risk.READ, "json_validate", "Check whether a file is valid JSON.",
            schema { str("path", "JSON file path.", required = true) }) { env, a ->
            val el = parseJson(env.fs.readText(a.reqStr("path")), env.fs.display(a.reqStr("path")))
            val kind = when (el) { is JsonObject -> "object (${el.size} keys)"; is JsonArray -> "array (${el.size} elements)"; else -> "single value" }
            "Valid JSON. The root is $kind."
        },

        textTool("json.format", Risk.WRITE, "json_format", "Reformat (pretty-print with 2-space indentation) a JSON file and overwrite it in place.",
            schema { str("path", "JSON file path.", required = true) },
            preview = { env, a -> "Reformat and overwrite '${env.fs.display(a.reqStr("path"))}'" }) { env, a ->
            val el = parseJson(env.fs.readText(a.reqStr("path")), env.fs.display(a.reqStr("path")))
            env.fs.writeText(a.reqStr("path"), jsonPretty.encodeToString(JsonElement.serializer(), el) + "\n")
            "Reformatted '${env.fs.display(a.reqStr("path"))}'."
        },

        textTool("json.get", Risk.READ, "json_get", "Get a value from a JSON file by key path, e.g. 'users[0].name'. Leave empty to get the whole document.",
            schema {
                str("path", "JSON file path.", required = true)
                str("pointer", "Key path, e.g. 'a.b[2].c'.")
            }) { env, a ->
            val root = parseJson(env.fs.readText(a.reqStr("path")), env.fs.display(a.reqStr("path")))
            val v = jsonGet(root, jsonTokens(a.str("pointer").orEmpty()))
            jsonPretty.encodeToString(JsonElement.serializer(), v)
        },

        textTool("json.set", Risk.WRITE, "json_set", "Set/edit a value in a JSON file by key path (missing keys are created). 'value_json' is the value as JSON, e.g. \"abc\" (with quotes), 42, true, {\"a\":1}. The file is written back with 2-space indentation.",
            schema {
                str("path", "JSON file path.", required = true)
                str("pointer", "Key path, e.g. 'a.b[2].c'.", required = true)
                str("value_json", "New value as JSON.", required = true)
            },
            preview = { env, a -> "Edit '${env.fs.display(a.reqStr("path"))}':\n${a.reqStr("pointer")} = ${a.reqStr("value_json").take(300)}" }) { env, a ->
            val path = a.reqStr("path")
            val root = parseJson(env.fs.readText(path), env.fs.display(path))
            val raw = a.reqStr("value_json")
            val value = runCatching { Json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
            val tokens = jsonTokens(a.reqStr("pointer"))
            val updated = jsonSet(root, tokens, 0, value)
            env.fs.writeText(path, jsonPretty.encodeToString(JsonElement.serializer(), updated) + "\n")
            "Set '${a.reqStr("pointer")}' in '${env.fs.display(path)}'."
        },

        textTool("convert.csv_to_json", Risk.WRITE, "csv_to_json", "Convert a CSV file into a JSON file (array of objects, keys taken from the header row, all values are strings).",
            schema {
                str("path", "Source CSV file.", required = true)
                str("output_path", "Output JSON file (default: same name with a .json extension).")
            },
            preview = { env, a -> "Convert '${env.fs.display(a.reqStr("path"))}' to JSON → '${env.fs.display(a.str("output_path") ?: a.reqStr("path").substringBeforeLast('.') + ".json")}'" }) { env, a ->
            val f = loadCsv(env, a.reqStr("path"), null)
            val arr = JsonArray(f.data.map { r -> JsonObject(f.header.indices.associate { f.header[it] to JsonPrimitive(r.getOrElse(it) { "" }) }) })
            val out = a.str("output_path") ?: (a.reqStr("path").substringBeforeLast('.') + ".json")
            env.fs.writeText(out, jsonPretty.encodeToString(JsonElement.serializer(), arr) + "\n")
            "Converted ${f.data.size} rows to '${env.fs.display(out)}'."
        },

        textTool("convert.json_to_csv", Risk.WRITE, "json_to_csv", "Convert a JSON file (array of flat objects) into a CSV file.",
            schema {
                str("path", "Source JSON file.", required = true)
                str("output_path", "Output CSV file (default: same name with a .csv extension).")
            },
            preview = { env, a -> "Convert '${env.fs.display(a.reqStr("path"))}' to CSV → '${env.fs.display(a.str("output_path") ?: a.reqStr("path").substringBeforeLast('.') + ".csv")}'" }) { env, a ->
            val root = parseJson(env.fs.readText(a.reqStr("path")), env.fs.display(a.reqStr("path")))
            val arr = root as? JsonArray ?: throw ToolException("The JSON root must be an array of objects.")
            val objs = arr.map { it as? JsonObject ?: throw ToolException("Every element of the array must be an object.") }
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
            "Converted ${objs.size} objects (${keys.size} columns) to '${env.fs.display(out)}'."
        },

        textTool("convert.md_to_html", Risk.WRITE, "markdown_to_html", "Convert a Markdown file into a complete HTML page (supports tables and strikethrough).",
            schema {
                str("path", "Source Markdown file.", required = true)
                str("output_path", "Output HTML file (default: same name with a .html extension).")
            },
            preview = { env, a -> "Convert '${env.fs.display(a.reqStr("path"))}' to HTML → '${env.fs.display(a.str("output_path") ?: a.reqStr("path").substringBeforeLast('.') + ".html")}'" }) { env, a ->
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
            "Created '${env.fs.display(out)}'."
        },
    )
}
