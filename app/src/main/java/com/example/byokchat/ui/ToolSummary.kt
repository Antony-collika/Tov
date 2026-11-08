package com.example.byokchat.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private val lenient = Json { ignoreUnknownKeys = true }
private val pretty = Json { prettyPrint = true }

private fun parseArgs(argsJson: String): JsonObject =
    runCatching { lenient.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.n(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.arrSize(k: String): Int? = (this[k] as? JsonArray)?.size

private fun q(s: String?, max: Int = 40): String {
    val t = (s ?: "").replace("\n", " ").trim()
    return "\u201C" + (if (t.length > max) t.take(max) + "\u2026" else t) + "\u201D"
}

private fun short(s: String?, max: Int = 60): String {
    val t = (s ?: "").replace("\n", " ").trim()
    return if (t.length > max) t.take(max - 1) + "\u2026" else t
}

/**
 * One readable sentence for a tool call, e.g. "Read file notes/todo.md". Falls back to the tool's name.
 * [uid] is the tool's fixed UID (stays the same even if the user renamed the tool).
 */
internal fun toolSummary(uid: String?, name: String, argsJson: String): String {
    val a = parseArgs(argsJson)
    val path = a.s("path")
    return when (uid) {
        "fs.list" -> if (path == null) "List workspace folder" else "List folder ${short(path)}"
        "fs.tree" -> if (path == null) "Show workspace folder tree" else "Show folder tree of ${short(path)}"
        "fs.info" -> "Get info for ${short(path)}"
        "fs.mkdir" -> "Create folder ${short(path)}"
        "fs.copy" -> "Copy ${short(a.s("source"))} \u2192 ${short(a.s("dest"))}"
        "fs.move" -> "Move ${short(a.s("source"))} \u2192 ${short(a.s("dest"))}"
        "fs.rename" -> "Rename ${short(path)} to ${short(a.s("new_name"))}"
        "fs.delete" -> "Delete ${short(path)}"
        "fs.find" -> "Find files matching ${short(a.s("pattern"))}"
        "fs.grep" -> "Search files for ${q(a.s("query"))}"
        "fs.zip" -> "Zip ${a.arrSize("sources") ?: 0} item(s) into ${short(a.s("dest"))}"
        "fs.unzip" -> "Unzip ${short(a.s("source"))}"
        "txt.read" -> {
            val from = a.n("start_line")
            val to = a.n("end_line")
            val range = when {
                from != null && to != null -> " (lines $from\u2013$to)"
                from != null -> " (from line $from)"
                to != null -> " (up to line $to)"
                else -> ""
            }
            "Read file ${short(path)}$range"
        }
        "txt.read_many" -> "Read ${a.arrSize("paths") ?: 0} file(s)"
        "txt.create" -> "Create file ${short(path)}"
        "txt.write" -> "Overwrite file ${short(path)}"
        "txt.append" -> "Append to ${short(path)}"
        "txt.insert_lines" -> "Insert text into ${short(path)}" + (a.n("line")?.let { " at line $it" } ?: "")
        "txt.delete_lines" -> "Delete lines ${a.n("start_line") ?: "?"}\u2013${a.n("end_line") ?: "?"} of ${short(path)}"
        "txt.replace" -> "Edit ${short(path)}"
        "txt.replace_many" -> "Find and replace ${q(a.s("find"))} in files"
        "txt.diff" -> "Compare ${short(a.s("path_a"))} and ${short(a.s("path_b"))}"
        "txt.count" -> "Count words and lines in ${short(path ?: a.s("directory") ?: "files")}"
        "csv.read" -> "Read table ${short(path)}"
        "csv.filter" -> "Filter ${short(path)} by ${short(a.s("column"))}"
        "csv.sort" -> "Sort ${short(path)} by ${short(a.s("column"))}"
        "csv.stats" -> "Get statistics of ${short(path)}"
        "csv.append_row" -> "Add a row to ${short(path)}"
        "csv.update_cell" -> "Change a cell in ${short(path)}"
        "json.validate" -> "Validate JSON file ${short(path)}"
        "json.format" -> "Format JSON file ${short(path)}"
        "json.get" -> "Read ${short(a.s("pointer") ?: "value")} from ${short(path)}"
        "json.set" -> "Set ${short(a.s("pointer") ?: "value")} in ${short(path)}"
        "convert.csv_to_json" -> "Convert ${short(path)} to JSON"
        "convert.json_to_csv" -> "Convert ${short(path)} to CSV"
        "convert.md_to_html" -> "Convert ${short(path)} to HTML"
        "calc.eval" -> "Calculate ${short(a.s("expression"))}"
        "time.now" -> "Check the current date and time"
        "time.diff" -> "Calculate the days between ${short(a.s("start"), 20)} and ${short(a.s("end"), 20)}"
        "web.fetch" -> "Open web page ${short(a.s("url"))}"
        "web.search" -> "Search the web for ${q(a.s("query"))}"
        "note.save" -> "Save note ${q(a.s("title"))}"
        "note.list" -> "List notes"
        "note.get" -> "Read a note"
        "note.update" -> "Update a note"
        "note.delete" -> "Delete a note"
        "todo.add" -> "Add to-do ${q(a.s("text"))}"
        "todo.list" -> "List to-dos"
        "todo.complete" -> "Update a to-do"
        "todo.delete" -> "Delete a to-do"
        "alarm.set" -> {
            val h = a.n("hour")
            val m = a.n("minute")
            if (h != null && m != null) "Set an alarm for " + String.format(java.util.Locale.ROOT, "%02d:%02d", h, m) else "Set an alarm"
        }
        "calendar.create" -> "Create calendar event ${q(a.s("title"))}"
        "clipboard.write" -> "Copy text to the clipboard"
        else -> name
    }
}

/** Arguments as indented JSON for the expanded card (falls back to the raw text if it is not valid JSON). */
internal fun prettyArgs(argsJson: String): String {
    if (argsJson.isBlank()) return "(no arguments)"
    return runCatching { pretty.encodeToString(JsonElement.serializer(), lenient.parseToJsonElement(argsJson)) }
        .getOrDefault(argsJson)
}
