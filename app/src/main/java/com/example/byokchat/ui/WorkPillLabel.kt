package com.example.byokchat.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private val lenient = Json { ignoreUnknownKeys = true }

private fun parseArgs(argsJson: String): JsonObject =
    runCatching { lenient.parseToJsonElement(argsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.n(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.arr(k: String): List<String>? =
    (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }

/** Shortens a concrete object (file name, keyword, place) for the one-line pill; never a file's content. */
private fun short(s: String?, max: Int = 40): String {
    val t = (s ?: "").replace("\n", " ").trim()
    return if (t.length > max) t.take(max - 1) + "…" else t
}

private fun q(s: String?, max: Int = 40): String = "“" + short(s, max) + "”"

private fun baseName(path: String?): String = short(path?.substringAfterLast('/'), 40)

/**
 * The bare phrase for one work step, e.g. "Đọc file cách tìm kiếm.md" —
 * what the step is doing, with the concrete object from the call's own
 * arguments. Tools in the same group share one sentence pattern. This
 * lives in the UI layer only: tool descriptions sent to the model are
 * untouched. It NEVER uses file contents or tool results — only the
 * call's arguments — so the pill can stream it while the tool runs.
 * [agentNameFor] maps a sub-agent id to its display name (the id itself
 * when unknown); Main never goes through it.
 */
internal fun stepPhrase(step: WorkStep, agentNameFor: (String) -> String = { it }): String {
    if (step is WorkStep.Reasoning) return "Suy nghĩ"
    step as WorkStep.Tool
    val a = parseArgs(step.call.argsJson)
    val path = a.s("path")
    return when (step.call.uid) {
        // Files & folders.
        "fs.list" -> if (path == null) "Xem danh sách file" else "Xem danh sách file trong thư mục ${short(path)}"
        "fs.tree" -> if (path == null) "Xem cây thư mục" else "Xem cây thư mục ${short(path)}"
        "fs.info" -> "Xem thông tin file ${short(path)}"
        "fs.mkdir" -> "Tạo thư mục ${short(path)}"
        "fs.copy" -> "Sao chép ${short(a.s("source"))} sang ${short(a.s("dest"))}"
        "fs.move" -> "Di chuyển ${short(a.s("source"))} sang ${short(a.s("dest"))}"
        "fs.rename" -> "Đổi tên ${short(path)} thành ${short(a.s("new_name"))}"
        "fs.delete" -> "Xóa ${short(path)}"
        "fs.find" -> "Tìm file khớp ${short(a.s("pattern"))}"
        "fs.grep" -> "Tìm ${q(a.s("query"))} trong file"
        "fs.zip" -> "Nén ${a.arr("sources")?.size ?: 0} mục thành ${short(a.s("dest"))}"
        "fs.unzip" -> "Giải nén ${short(a.s("source"))}"
        // Text files.
        "txt.read" -> "Đọc file ${short(path)}"
        "txt.read_many" -> "Đọc ${a.arr("paths")?.size ?: 0} file"
        "txt.create", "txt.write" -> "Ghi file ${short(path)}"
        "txt.append" -> "Ghi thêm vào file ${short(path)}"
        "txt.insert_lines" -> "Chèn chữ vào file ${short(path)}"
        "txt.delete_lines" -> "Xóa dòng trong file ${short(path)}"
        "txt.replace" -> "Sửa file ${short(path)}"
        "txt.replace_many" -> "Tìm và thay ${q(a.s("find"))} trong file"
        "txt.diff" -> "So sánh ${short(a.s("path_a"))} và ${short(a.s("path_b"))}"
        "txt.count" -> "Đếm chữ trong ${short(path ?: a.s("directory"))}"
        // Data files.
        "csv.read" -> "Đọc bảng ${short(path)}"
        "csv.filter" -> "Lọc bảng ${short(path)}"
        "csv.sort" -> "Sắp xếp bảng ${short(path)}"
        "csv.stats" -> "Xem thống kê của ${short(path)}"
        "csv.append_row" -> "Thêm dòng vào ${short(path)}"
        "csv.update_cell" -> "Sửa ô trong ${short(path)}"
        "json.validate" -> "Kiểm tra file JSON ${short(path)}"
        "json.format" -> "Định dạng lại file JSON ${short(path)}"
        "json.get" -> "Đọc ${short(a.s("pointer"))} trong ${short(path)}"
        "json.set" -> "Ghi ${short(a.s("pointer"))} vào ${short(path)}"
        "convert.csv_to_json" -> "Chuyển ${short(path)} sang JSON"
        "convert.json_to_csv" -> "Chuyển ${short(path)} sang CSV"
        "convert.md_to_html" -> "Chuyển ${short(path)} sang HTML"
        // Daily tools.
        "calc.eval" -> "Tính ${short(a.s("expression"))}"
        "time.now" -> "Xem giờ hiện tại"
        "time.diff" -> "Tính số ngày giữa ${short(a.s("start"), 20)} và ${short(a.s("end"), 20)}"
        "web.fetch" -> "Mở trang ${short(a.s("url"))}"
        "web.search" -> "Tìm kiếm ${q(a.s("query"))} trên web"
        "note.save" -> "Lưu ghi chú ${q(a.s("title"))}"
        "note.list" -> "Xem danh sách ghi chú"
        "note.get" -> "Đọc ghi chú"
        "note.update" -> "Sửa ghi chú"
        "note.delete" -> "Xóa ghi chú"
        "todo.add" -> "Thêm việc ${q(a.s("text"))}"
        "todo.list" -> "Xem danh sách việc cần làm"
        "todo.complete" -> "Cập nhật việc cần làm"
        "todo.delete" -> "Xóa việc cần làm"
        "alarm.set" -> {
            val h = a.n("hour")
            val m = a.n("minute")
            if (h != null && m != null) "Đặt báo thức " + String.format(java.util.Locale.ROOT, "%02d:%02d", h, m) else "Đặt báo thức"
        }
        "calendar.create" -> "Tạo sự kiện ${q(a.s("title"))}"
        "clipboard.write" -> "Sao chép chữ vào bộ nhớ tạm"
        // Weather.
        "weather_forecast" -> "Xem thời tiết ${short(a.s("place"))}"
        // Media.
        "youtube_search" -> {
            val query = a.s("q")
            val channel = a.s("channel_id")
            when {
                !query.isNullOrBlank() -> "Tìm ${q(query)} trên YouTube"
                !channel.isNullOrBlank() -> "Tìm video mới trong kênh ${short(channel)}"
                else -> "Tìm trên YouTube"
            }
        }
        "youtube_comments" -> "Xem bình luận của video ${short(a.s("video"))}"
        "youtube_download" -> {
            val kind = when (a.s("kind")) {
                "audio" -> "audio"
                "video" -> "video"
                "transcript" -> "phụ đề"
                else -> "file"
            }
            "Tải $kind về ${short(path)}"
        }
        "local.media.play" -> {
            val paths = a.arr("paths").orEmpty()
            if (paths.size == 1) "Phát ${baseName(paths[0])}" else "Phát ${paths.size} bài nhạc"
        }
        "youtube.media.play" -> "Phát nhạc trên YouTube"
        // Agents.
        "sub.delegate" -> "Giao việc cho ${agentNameFor(a.s("agent") ?: "")}"
        "sub.recall" -> "Xem lại kết quả của ${agentNameFor(a.s("agent") ?: "")}"
        else -> "Gọi ${step.call.uid ?: step.call.name}"
    }
}

/**
 * The one-line pill text for a step: present tense while it runs
 * ("Đang đọc file abc.md…"), past tense once it is done
 * ("Đã đọc file abc.md"). A step that has not started yet (it sits
 * after the running one) reads as a plain phrase with no tense.
 */
internal fun pillLabel(step: WorkStep, running: Boolean, done: Boolean, agentNameFor: (String) -> String = { it }): String {
    if (step is WorkStep.Reasoning) {
        return when {
            running -> "Đang suy nghĩ…"
            done -> "Đã suy nghĩ xong"
            else -> "Suy nghĩ"
        }
    }
    val phrase = stepPhrase(step, agentNameFor)
    return when {
        running -> "Đang " + phrase.replaceFirstChar { it.lowercaseChar() } + "…"
        done -> "Đã " + phrase.replaceFirstChar { it.lowercaseChar() }
        else -> phrase
    }
}

/**
 * The concrete object a step acts on (file, folder, keyword, place,
 * agent), for the step detail page's "Nhắm vào" row. Null when the
 * step has no single object (reasoning, "check the time").
 */
internal fun stepTarget(step: WorkStep, agentNameFor: (String) -> String = { it }): String? {
    if (step is WorkStep.Reasoning) return null
    step as WorkStep.Tool
    val a = parseArgs(step.call.argsJson)
    return when (step.call.uid) {
        "fs.list", "fs.tree", "fs.info", "fs.mkdir", "fs.delete",
        "txt.read", "txt.create", "txt.write", "txt.append", "txt.insert_lines",
        "txt.delete_lines", "txt.replace", "txt.count",
        "csv.read", "csv.filter", "csv.sort", "csv.stats", "csv.append_row", "csv.update_cell",
        "json.validate", "json.format", "json.get", "json.set",
        "convert.csv_to_json", "convert.json_to_csv", "convert.md_to_html" ->
            a.s("path") ?: a.s("directory")
        "fs.copy", "fs.move" -> listOfNotNull(a.s("source"), a.s("dest")).joinToString(" → ").ifBlank { null }
        "fs.rename" -> a.s("path")
        "fs.find" -> a.s("pattern")
        "fs.grep", "web.search" -> a.s("query")
        "txt.read_many" -> a.arr("paths")?.joinToString(", ")
        "txt.replace_many" -> a.s("find")
        "txt.diff" -> listOfNotNull(a.s("path_a"), a.s("path_b")).joinToString(" và ").ifBlank { null }
        "calc.eval" -> a.s("expression")
        "web.fetch" -> a.s("url")
        "weather_forecast" -> a.s("place")
        "youtube_search" -> a.s("q") ?: a.s("channel_id")
        "youtube_comments" -> a.s("video")
        "youtube_download" -> a.s("path")
        "local.media.play" -> a.arr("paths")?.joinToString(", ")
        "youtube.media.play" -> a.arr("videos")?.joinToString(", ")
        "sub.delegate", "sub.recall" -> a.s("agent")?.let(agentNameFor)
        else -> null
    }
}
