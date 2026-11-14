package com.example.byokchat.tools

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.Html
import com.example.byokchat.ai.Http
import com.example.byokchat.ai.executeCancellable
import com.example.byokchat.ai.getJson
import com.example.byokchat.chat.ModelFacingText
import com.example.byokchat.data.NoteEntity
import com.example.byokchat.data.TodoEntity
import com.example.byokchat.util.TextUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.math.MathContext
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.UUID

fun dailyTool(
    uid: String,
    risk: Risk,
    name: String,
    description: String,
    schema: JsonObject,
    unavailableHint: String? = null,
    isAvailable: (ToolEnv) -> Boolean = { true },
    preview: (suspend (ToolEnv, JsonObject) -> String)? = null,
    run: suspend (ToolEnv, JsonObject) -> String,
) = Tool(uid, ToolGroup.DAILY, risk, name, description, schema, false, unavailableHint, isAvailable, preview, run)

// ---------- Máy tính ----------
private class Calc(private val s: String) {
    private var i = 0

    fun eval(): Double {
        val v = expr()
        skip()
        if (i < s.length) fail()
        return v
    }

    private fun fail(): Nothing = throw ToolException("Invalid expression near position ${i + 1}: '${s.drop(i).take(10)}'")
    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun peek(): Char { skip(); return if (i < s.length) s[i] else '\u0000' }

    private fun expr(): Double {
        var v = term()
        while (true) {
            when (peek()) {
                '+' -> { i++; v += term() }
                '-' -> { i++; v -= term() }
                else -> return v
            }
        }
    }

    private fun term(): Double {
        var v = power()
        while (true) {
            when (peek()) {
                '*' -> { i++; v *= power() }
                '/' -> { i++; val d = power(); if (d == 0.0) throw ToolException("Cannot divide by zero."); v /= d }
                '%' -> { i++; val d = power(); if (d == 0.0) throw ToolException("Cannot take a remainder by zero."); v %= d }
                else -> return v
            }
        }
    }

    private fun power(): Double {
        val base = unary()
        return if (peek() == '^') { i++; Math.pow(base, power()) } else base
    }

    private fun unary(): Double = when (peek()) {
        '-' -> { i++; -unary() }
        '+' -> { i++; unary() }
        else -> primary()
    }

    private fun primary(): Double {
        val c = peek()
        if (c == '(') {
            i++
            val v = expr()
            if (peek() != ')') fail()
            i++
            return v
        }
        if (c.isDigit() || c == '.') {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')) {
                i += 2
                while (i < s.length && s[i].isDigit()) i++
            }
            return s.substring(st, i).toDoubleOrNull() ?: run { i = st; fail() }
        }
        if (c.isLetter()) {
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            val name = s.substring(st, i).lowercase()
            if (peek() == '(') {
                i++
                val args = mutableListOf<Double>()
                if (peek() != ')') {
                    args.add(expr())
                    while (peek() == ',') { i++; args.add(expr()) }
                }
                if (peek() != ')') fail()
                i++
                return call(name, args)
            }
            return when (name) {
                "pi" -> Math.PI
                "e" -> Math.E
                else -> throw ToolException("Unknown name '$name'.")
            }
        }
        fail()
    }

    private fun call(name: String, a: List<Double>): Double {
        fun one(): Double { if (a.size != 1) throw ToolException("Function $name takes exactly 1 argument."); return a[0] }
        return when (name) {
            "sqrt" -> Math.sqrt(one()); "abs" -> Math.abs(one())
            "sin" -> Math.sin(one()); "cos" -> Math.cos(one()); "tan" -> Math.tan(one())
            "asin" -> Math.asin(one()); "acos" -> Math.acos(one()); "atan" -> Math.atan(one())
            "ln" -> Math.log(one()); "log" -> Math.log10(one()); "exp" -> Math.exp(one())
            "floor" -> Math.floor(one()); "ceil" -> Math.ceil(one()); "round" -> Math.rint(one())
            "pow" -> { if (a.size != 2) throw ToolException("pow takes 2 arguments."); Math.pow(a[0], a[1]) }
            "min" -> a.minOrNull() ?: throw ToolException("min needs at least 1 argument.")
            "max" -> a.maxOrNull() ?: throw ToolException("max needs at least 1 argument.")
            else -> throw ToolException("Function '$name' is not supported.")
        }
    }
}

private fun fmtResult(d: Double): String {
    if (d.isNaN() || d.isInfinite()) throw ToolException("Undefined result (NaN or infinity).")
    if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
    val bd = BigDecimal(d).round(MathContext(12)).stripTrailingZeros()
    return if (Math.abs(d) >= 1e15 || Math.abs(d) < 1e-6) bd.toString() else bd.toPlainString()
}

// ---------- Ngày giờ ----------
private val weekdays = mapOf(
    DayOfWeek.MONDAY to "Monday", DayOfWeek.TUESDAY to "Tuesday", DayOfWeek.WEDNESDAY to "Wednesday",
    DayOfWeek.THURSDAY to "Thursday", DayOfWeek.FRIDAY to "Friday", DayOfWeek.SATURDAY to "Saturday", DayOfWeek.SUNDAY to "Sunday",
)

private fun parseDateTime(s: String, zone: ZoneId): ZonedDateTime {
    val t = s.trim()
    runCatching { return ZonedDateTime.parse(t) }
    runCatching { return OffsetDateTime.parse(t).atZoneSameInstant(zone) }
    runCatching { return LocalDateTime.parse(t.replace(' ', 'T')).atZone(zone) }
    runCatching { return LocalDate.parse(t).atStartOfDay(zone) }
    for (p in listOf("dd/MM/yyyy HH:mm", "dd/MM/yyyy", "d/M/yyyy")) {
        runCatching {
            val f = DateTimeFormatter.ofPattern(p)
            return if (p.contains("HH")) LocalDateTime.parse(t, f).atZone(zone) else LocalDate.parse(t, f).atStartOfDay(zone)
        }
    }
    throw ToolException("Cannot parse the date/time '$s'. Use a format like 2026-11-06, 2026-11-06T14:30 or 06/11/2026.")
}

// ---------- Web ----------
// Biểu thức chính quy dựng một lần (không tạo lại mỗi lần đọc trang).
private val HTML_HIDDEN_BLOCK = Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>")
private val HTML_LINE_BREAK = Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>")
private val HTML_TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")

/** Trang dài hơn mức này thì chỉ xử lý phần đầu (công cụ chỉ trả tối đa 40 000 ký tự). */
private const val MAX_PAGE_CHARS = 600_000

private fun htmlToText(html: String): String {
    val cleaned = html
        .replace(HTML_HIDDEN_BLOCK, " ")
        .replace(HTML_LINE_BREAK, "\n")
    val text = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
    return text.replace('\u00A0', ' ').lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

private fun noteId() = UUID.randomUUID().toString().take(8)

// ---------- Web search (Brave / Tavily / Serper / DuckDuckGo) ----------

/** Một kết quả tìm kiếm web, dùng chung cho mọi engine. */
data class WebSearchResult(val title: String, val url: String, val snippet: String)

/** Engine nào cần key thì key trống là lỗi rõ ràng, không tự đổi engine khác. */
private fun requireSearchKey(provider: String, key: String) {
    if (key.isBlank()) throw ToolException("No API key for $provider. Enter it in Settings → Web search.")
}

/** Định dạng chung "• title\n  url\n  snippet" cho mọi engine. */
fun formatWebSearchResults(query: String, results: List<WebSearchResult>): String =
    if (results.isEmpty()) "No results for '$query'."
    else "Search results for '$query':\n" + results.joinToString("\n") {
        "• ${it.title}\n  ${it.url}\n  ${it.snippet.take(300)}"
    }

/** Brave: web.results[] với title/url/description (description có thể chứa HTML). */
fun parseBraveResults(root: JsonObject): List<WebSearchResult> =
    root["web"]?.jsonObject?.get("results")?.jsonArray?.mapNotNull { r ->
        val o = r.jsonObject
        val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val desc = o["description"]?.jsonPrimitive?.contentOrNull?.let { decodeHtmlEntities(stripHtmlTags(it)) } ?: ""
        WebSearchResult(title, o["url"]?.jsonPrimitive?.contentOrNull ?: "", desc)
    } ?: emptyList()

/** Tavily: results[] với title/url/content. */
fun parseTavilyResults(root: JsonObject): List<WebSearchResult> =
    root["results"]?.jsonArray?.mapNotNull { r ->
        val o = r.jsonObject
        val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        WebSearchResult(title, o["url"]?.jsonPrimitive?.contentOrNull ?: "", o["content"]?.jsonPrimitive?.contentOrNull ?: "")
    } ?: emptyList()

/** Serper (google.serper.dev): organic[] với title/link/snippet. */
fun parseSerperResults(root: JsonObject): List<WebSearchResult> =
    root["organic"]?.jsonArray?.mapNotNull { r ->
        val o = r.jsonObject
        val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        WebSearchResult(title, o["link"]?.jsonPrimitive?.contentOrNull ?: "", o["snippet"]?.jsonPrimitive?.contentOrNull ?: "")
    } ?: emptyList()

private val DDG_ANCHOR = Regex("""(?is)<a\b([^>]*)>(.*?)</a>""")
private val DDG_HREF = Regex("""href="([^"]+)"""")
private val DDG_UDDG = Regex("""[?&]uddg=([^&]+)""")
private val HTML_TAG = Regex("<[^>]+>")
private val HTML_ENTITY_DEC = Regex("&#(\\d+);")
private val HTML_ENTITY_HEX = Regex("&#x([0-9a-fA-F]+);")

/** Bỏ thẻ HTML, giữ lại text. */
fun stripHtmlTags(html: String): String = html.replace(HTML_TAG, "")

/** Giải mã HTML entities thường gặp (&amp; &lt; &gt; &quot; &#39; &#123; &#x7B;). Thuần Kotlin, không cần Android. */
fun decodeHtmlEntities(text: String): String {
    var out = text
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ")
    out = HTML_ENTITY_DEC.replace(out) { m ->
        m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
    }
    out = HTML_ENTITY_HEX.replace(out) { m ->
        m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
    }
    return out
}

/**
 * DuckDuckGo (html.duckduckgo.com/html/): mỗi kết quả là một link
 * class="result__a" (tiêu đề + href) kèm một link class="result__snippet"
 * (mô tả), theo đúng thứ tự xuất hiện. Href thường là link chuyển hướng
 * //duckduckgo.com/l/?uddg=<url đã encode> nên giải mã uddg để lấy URL thật.
 */
fun parseDuckDuckGoResults(html: String): List<WebSearchResult> {
    val anchors = DDG_ANCHOR.findAll(html).toList()
    val titles = anchors.filter { it.groupValues[1].contains("result__a") }
    val snippets = anchors.filter { it.groupValues[1].contains("result__snippet") }
    return titles.mapIndexed { i, m ->
        val rawHref = DDG_HREF.find(m.groupValues[1])?.groupValues?.get(1) ?: ""
        val realUrl = DDG_UDDG.find(rawHref)?.groupValues?.get(1)?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull()
        } ?: rawHref.let { if (it.startsWith("//")) "https:$it" else it }
        val title = decodeHtmlEntities(stripHtmlTags(m.groupValues[2])).trim()
        val snippet = snippets.getOrNull(i)?.let { decodeHtmlEntities(stripHtmlTags(it.groupValues[2])).trim() } ?: ""
        WebSearchResult(title, realUrl, snippet)
    }.filter { it.title.isNotEmpty() }
}

/** Xuất một note ra Storage/notes/<id>.md. Best-effort: Storage chưa
 * chọn hay lỗi ghi đều bỏ qua, không bao giờ làm lỗi tool. */
private fun exportNote(env: ToolEnv, note: NoteEntity) {
    runCatching {
        env.fs.writeSessionText("notes/${note.id}.md", "# ${note.title}\n\n${note.content}")
    }
}

/** Xóa file .md của một note trong Storage. Best-effort. */
private fun deleteNoteFile(env: ToolEnv, id: String) {
    runCatching { env.fs.findSession("notes/$id.md")?.delete() }
}

/**
 * Xuất toàn bộ to-dos ra Storage/to-dos/to-dos.md (một chiều: DB là nguồn
 * chính xác, app không bao giờ đọc lại file này). Ghi đè toàn bộ file sau
 * mỗi lần thêm / đổi trạng thái / xóa. Best-effort như notes.
 */
private suspend fun exportTodos(env: ToolEnv) {
    runCatching {
        val text = buildString {
            appendLine("# To-dos")
            for (t in env.db.todos().all()) {
                appendLine("- [${if (t.done) "x" else " "}] ${t.text}")
            }
        }
        env.fs.writeSessionText("to-dos/to-dos.md", text)
    }
}

/** Nhóm 4: công cụ công việc hằng ngày. */
object DailyTools {
    fun build(): List<Tool> = listOf(
        dailyTool("calc.eval", Risk.READ, "calculator",
            "Evaluate a math expression with real numbers only. Supports + - * / % ^, parentheses, pi, e, sqrt, abs, sin, cos, tan, asin, acos, atan, ln, log, exp, floor, ceil, round, pow, min, max (angles in radians). No imaginary or complex numbers.",
            schema { str("expression", "Expression, e.g. (12.5 + 3) * 4 / sqrt(16)", required = true) }) { _, a ->
            val expr = a.reqStr("expression").replace('×', '*').replace('÷', '/').replace("−", "-")
            "${a.reqStr("expression")} = ${fmtResult(Calc(expr).eval())}"
        },

        dailyTool("time.now", Risk.READ, "current_time",
            "Get the current date and time (with weekday). Returns date and time only — no time zone or location. The timezone parameter is accepted but ignored.",
            schema { str("timezone", "Accepted for compatibility but ignored: the answer always uses the device's time zone and never reveals it.") }) { _, _ ->
            // Date and time only, no time zone or location: the device time
            // zone is used, and the timezone parameter is ignored.
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            "${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))} (${weekdays[now.dayOfWeek]})"
        },

        dailyTool("time.diff", Risk.READ, "date_difference",
            "Compute the difference between two dates/times: total days, hours, minutes and a 'x years y months z days' form.",
            schema {
                str("start", "Start: 2026-11-06, 2026-11-06T14:30 or 06/11/2026.", required = true)
                str("end", "End (default: now).")
            }) { _, a ->
            val zone = ZoneId.systemDefault()
            val s = parseDateTime(a.reqStr("start"), zone)
            val e = a.str("end")?.let { parseDateTime(it, zone) } ?: ZonedDateTime.now(zone)
            val days = ChronoUnit.DAYS.between(s.toLocalDate(), e.toLocalDate())
            val dur = Duration.between(s, e)
            val p = if (s.toLocalDate() <= e.toLocalDate()) Period.between(s.toLocalDate(), e.toLocalDate()) else Period.between(e.toLocalDate(), s.toLocalDate())
            "From ${s.toLocalDateTime()} to ${e.toLocalDateTime()}:\n" +
                "• Difference: ${days} calendar days (${if (days < 0) "the end is BEFORE the start" else "the end is after the start"})\n" +
                "• Total: ${dur.toHours()} hours = ${dur.toMinutes()} minutes\n" +
                "• Calendar form: ${p.years} years ${p.months} months ${p.days} days"
        },

        dailyTool("web.fetch", Risk.READ, "fetch_url",
            "Read the content of a web page from a link (http/https) and return it as plain text with markup removed. Long pages may be truncated.",
            schema {
                str("url", "Full link starting with http:// or https://", required = true)
                int("max_chars", "Maximum number of characters to return (default 15000, max 40000).")
            }) { _, a ->
            val url = a.reqStr("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) throw ToolException("The link must start with http:// or https://")
            val max = (a.int("max_chars") ?: 15000).coerceIn(500, 40000)
            val req = try {
                Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) ByokChat").header("Accept-Language", "vi,en;q=0.8").build()
            } catch (e: IllegalArgumentException) {
                throw ToolException("Invalid link.")
            }
            val (type, rawBody) = try {
                Http.fetchClient.executeCancellable(req) { r ->
                    if (!r.isSuccessful) throw ToolException("The page returned error ${r.code}.")
                    val src = r.body?.source() ?: throw ToolException("The page has no content.")
                    src.request(1_500_000)
                    val bytes = src.buffer.snapshot().toByteArray().let { if (it.size > 1_500_000) it.copyOf(1_500_000) else it }
                    (r.header("Content-Type") ?: "") to String(bytes, Charsets.UTF_8)
                }
            } catch (e: java.io.IOException) {
                throw ToolException("Could not load the page: ${e.message}")
            }
            val body = if (rawBody.length > MAX_PAGE_CHARS) rawBody.take(MAX_PAGE_CHARS) else rawBody
            val title = HTML_TITLE.find(body)?.groupValues?.get(1)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
            val text = if (type.contains("html", true) || body.trimStart().startsWith("<")) htmlToText(body) else body
            val out = if (text.length > max) text.take(max) + ModelFacingText.truncatedNote(text.length - max) else text
            (if (title != null) "Title: $title\n" else "") + "Source: $url\n\n" + out
        },

        dailyTool("web.search", Risk.READ, "web_search",
            "Search the web and return a list of results (title, link, description). Use web fetch to read a result in detail.",
            schema {
                str("query", "Search keywords.", required = true)
                int("count", "Number of results (default 5, max 10).")
            },
            unavailableHint = "Enter your search service API key in Settings (DuckDuckGo needs no key).",
            isAvailable = { it.settings.searchProvider.value == "DUCKDUCKGO" || it.settings.searchApiKey.value.isNotBlank() }) { env, a ->
            val q = a.reqStr("query")
            val n = (a.int("count") ?: 5).coerceIn(1, 10)
            val key = env.settings.searchApiKey.value
            val provider = env.settings.searchProvider.value
            val results: List<WebSearchResult> = when (provider) {
                "TAVILY" -> {
                    requireSearchKey(provider, key)
                    val body = buildJsonObject { put("query", q); put("max_results", n) }.toString()
                    val req = Request.Builder().url("https://api.tavily.com/search").header("Authorization", "Bearer $key")
                        .post(body.toRequestBody(Http.JSON)).build()
                    parseTavilyResults(Http.client.getJson(req).jsonObject)
                }
                "SERPER" -> {
                    requireSearchKey(provider, key)
                    val body = buildJsonObject { put("q", q); put("num", n) }.toString()
                    val req = Request.Builder().url("https://google.serper.dev/search").header("X-API-KEY", key)
                        .post(body.toRequestBody(Http.JSON)).build()
                    parseSerperResults(Http.client.getJson(req).jsonObject)
                }
                "DUCKDUCKGO" -> {
                    val url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(q, "UTF-8")
                    val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) ByokChat").get().build()
                    val html = try {
                        Http.fetchClient.executeCancellable(req) { r ->
                            if (!r.isSuccessful) throw ToolException("DuckDuckGo returned error ${r.code}.")
                            r.body?.string() ?: throw ToolException("DuckDuckGo returned no content.")
                        }
                    } catch (e: java.io.IOException) {
                        throw ToolException("Could not reach DuckDuckGo: ${e.message}")
                    }
                    parseDuckDuckGoResults(html).take(n)
                }
                else -> {
                    requireSearchKey(provider, key)
                    val url = "https://api.search.brave.com/res/v1/web/search?count=$n&q=" + java.net.URLEncoder.encode(q, "UTF-8")
                    val req = Request.Builder().url(url).header("Accept", "application/json").header("X-Subscription-Token", key).get().build()
                    parseBraveResults(Http.client.getJson(req).jsonObject)
                }
            }
            formatWebSearchResults(q, results)
        },

        // ----- Ghi chú nhớ lâu -----
        dailyTool("note.save", Risk.WRITE, "save_note",
            "Save a long-term note (persists across conversations). Use it for information the user wants you to remember.",
            schema {
                str("title", "Short title.", required = true)
                str("content", "Note content.", required = true)
            },
            preview = { _, a -> "Save note \"${a.reqStr("title")}\":\n" + previewText(a.reqStr("content")) }) { env, a ->
            val now = System.currentTimeMillis()
            val id = noteId()
            val note = NoteEntity(id, a.reqStr("title").trim(), a.reqStr("content"), now, now)
            env.db.notes().upsert(note)
            exportNote(env, note)
            "Note saved (id: $id)."
        },

        dailyTool("note.list", Risk.READ, "list_notes",
            "List the saved notes (id, title, first part). Can be filtered by keyword.",
            schema { str("query", "Only show notes containing this keyword (optional).") }) { env, a ->
            val q = a.str("query").orEmpty()
            val notes = env.db.notes().all().filter { q.isBlank() || TextUtil.findAll(it.title + "\n" + it.content, q).isNotEmpty() }
            if (notes.isEmpty()) "No notes yet." else notes.joinToString("\n") { "• [${it.id}] ${it.title} — ${it.content.replace("\n", " ").take(80)}" }
        },

        dailyTool("note.get", Risk.READ, "read_note", "Read a note in full by id.",
            schema { str("id", "Note id (from list_notes).", required = true) }) { env, a ->
            val n = env.db.notes().get(a.reqStr("id").trim()) ?: throw ToolException("No note with id '${a.reqStr("id")}'.")
            "# ${n.title}\n\n${n.content}"
        },

        dailyTool("note.update", Risk.WRITE, "update_note", "Edit a note's title or content. Set append=true to add to the end instead of replacing.",
            schema {
                str("id", "Note id.", required = true)
                str("title", "New title (optional).")
                str("content", "New content (optional).")
                bool("append", "Append content to the end instead of replacing (default false).")
            },
            preview = { env, a ->
                val n = env.db.notes().get(a.reqStr("id").trim())
                "Edit note \"${n?.title ?: a.reqStr("id")}\":\n" + (a.str("title")?.let { "New title: $it\n" } ?: "") + (a.str("content")?.let { (if (a.bool("append") == true) "Append:\n" else "New content:\n") + previewText(it) } ?: "")
            }) { env, a ->
            val n = env.db.notes().get(a.reqStr("id").trim()) ?: throw ToolException("No note with id '${a.reqStr("id")}'.")
            val newContent = a.str("content")?.let { if (a.bool("append") == true) n.content.trimEnd() + "\n" + it else it } ?: n.content
            val updated = n.copy(title = a.str("title")?.trim() ?: n.title, content = newContent, updatedAt = System.currentTimeMillis())
            env.db.notes().upsert(updated)
            exportNote(env, updated)
            "Updated note ${n.id}."
        },

        dailyTool("note.delete", Risk.DELETE, "delete_note", "Delete a note. The user must always confirm.",
            schema { str("id", "Note id.", required = true) },
            preview = { env, a -> "DELETE note: " + (env.db.notes().get(a.reqStr("id").trim())?.let { "\"${it.title}\"" } ?: a.reqStr("id")) }) { env, a ->
            val id = a.reqStr("id").trim()
            env.db.notes().get(id) ?: throw ToolException("No note with id '$id'.")
            env.db.notes().delete(id)
            deleteNoteFile(env, id)
            "Note deleted."
        },

        // ----- Việc cần làm -----
        dailyTool("todo.add", Risk.WRITE, "add_todo", "Add an item to the to-do list.",
            schema { str("text", "To-do text.", required = true) },
            preview = { _, a -> "Add to-do: ${a.reqStr("text")}" }) { env, a ->
            val id = noteId()
            env.db.todos().upsert(TodoEntity(id, a.reqStr("text").trim(), false, System.currentTimeMillis()))
            exportTodos(env)
            "To-do added (id: $id)."
        },

        dailyTool("todo.list", Risk.READ, "list_todos", "Show the to-do list.",
            schema { str("filter", "Filter by status.", enum = listOf("all", "open", "done")) }) { env, a ->
            val f = a.str("filter") ?: "all"
            val items = env.db.todos().all().filter { f == "all" || (f == "open" && !it.done) || (f == "done" && it.done) }
            if (items.isEmpty()) "The list is empty." else items.joinToString("\n") { "${if (it.done) "☑" else "☐"} [${it.id}] ${it.text}" }
        },

        dailyTool("todo.complete", Risk.WRITE, "complete_todo", "Mark a to-do as done (or not done if done=false).",
            schema {
                str("id", "To-do id.", required = true)
                bool("done", "true = done (default), false = not done.")
            },
            preview = { env, a -> "Mark ${if (a.bool("done") == false) "NOT done" else "done"}: " + (env.db.todos().get(a.reqStr("id").trim())?.text ?: a.reqStr("id")) }) { env, a ->
            val t = env.db.todos().get(a.reqStr("id").trim()) ?: throw ToolException("No to-do with id '${a.reqStr("id")}'.")
            env.db.todos().upsert(t.copy(done = a.bool("done") ?: true))
            exportTodos(env)
            "Updated: ${t.text}"
        },

        dailyTool("todo.delete", Risk.DELETE, "delete_todo", "Delete a to-do from the list. The user must always confirm.",
            schema { str("id", "To-do id.", required = true) },
            preview = { env, a -> "DELETE to-do: " + (env.db.todos().get(a.reqStr("id").trim())?.text ?: a.reqStr("id")) }) { env, a ->
            env.db.todos().get(a.reqStr("id").trim()) ?: throw ToolException("No to-do with id '${a.reqStr("id")}'.")
            env.db.todos().delete(a.reqStr("id").trim())
            exportTodos(env)
            "To-do deleted."
        },

        // ----- Báo thức, lịch, clipboard -----
        dailyTool("alarm.set", Risk.WRITE, "set_alarm",
            "Set an alarm in the phone's Clock app. Without 'days' it rings once at the next occurrence of that time (within 24 hours).",
            schema {
                int("hour", "Hour (0-23).", required = true)
                int("minute", "Minute (0-59).", required = true)
                str("message", "Alarm label.")
                strArray("days", "Repeat weekly on these days: mon, tue, wed, thu, fri, sat, sun.")
            },
            preview = { _, a -> "Set alarm at %02d:%02d".format(a.int("hour") ?: 0, a.int("minute") ?: 0) + (a.str("message")?.let { " — $it" } ?: "") + (a.strList("days")?.takeIf { it.isNotEmpty() }?.let { "\nRepeats: ${it.joinToString(", ")}" } ?: "") }) { env, a ->
            val h = a.int("hour") ?: throw ToolException("Missing 'hour'.")
            val m = a.int("minute") ?: throw ToolException("Missing 'minute'.")
            if (h !in 0..23 || m !in 0..59) throw ToolException("Hour must be 0-23 and minute 0-59.")
            val dayMap = mapOf("mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY)
            val days = a.strList("days").orEmpty().map { d -> dayMap[d.trim().lowercase().take(3)] ?: throw ToolException("Invalid day '$d'.") }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_MESSAGE, a.str("message") ?: "")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                env.app.startActivity(intent)
            } catch (e: Exception) {
                throw ToolException("Could not set the alarm (no suitable Clock app or blocked by the system): ${e.message}")
            }
            "Alarm request sent for %02d:%02d".format(h, m) + (if (days.isEmpty()) " (one time)." else ", repeating weekly.")
        },

        dailyTool("calendar.create", Risk.WRITE, "create_calendar_event",
            "Create an event in the phone's calendar (the user must grant calendar permission).",
            schema {
                str("title", "Event title.", required = true)
                str("start", "Start, local time, like 2026-11-07T09:00.", required = true)
                str("end", "End (default: 1 hour after the start).")
                str("description", "Description.")
                str("location", "Location.")
                int("reminder_minutes", "Remind this many minutes before (optional).")
            },
            preview = { _, a -> "Create event \"${a.reqStr("title")}\"\nStart: ${a.reqStr("start")}" + (a.str("end")?.let { "\nEnd: $it" } ?: "") + (a.str("location")?.let { "\nLocation: $it" } ?: "") }) { env, a ->
            val zone = ZoneId.systemDefault()
            val start = parseDateTime(a.reqStr("start"), zone)
            val end = a.str("end")?.let { parseDateTime(it, zone) } ?: start.plusHours(1)
            if (!end.isAfter(start)) throw ToolException("The end time must be after the start time.")
            if (!env.permissions.ensure(listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))) {
                throw ToolException("Calendar access has not been granted. The user needs to allow the Calendar permission for the app.")
            }
            val cr = env.app.contentResolver
            val calId = cr.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                "${CalendarContract.Calendars.VISIBLE}=1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null, null,
            )?.use { c ->
                var first: Long? = null
                var primary: Long? = null
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (first == null) first = id
                    if (c.getInt(1) == 1 && primary == null) primary = id
                }
                primary ?: first
            } ?: throw ToolException("No writable calendar was found on the phone.")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, a.reqStr("title"))
                put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                a.str("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                a.str("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }
            val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values) ?: throw ToolException("Could not create the event.")
            a.int("reminder_minutes")?.let { mins ->
                val rv = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, uri.lastPathSegment?.toLongOrNull() ?: 0L)
                    put(CalendarContract.Reminders.MINUTES, mins)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                runCatching { cr.insert(CalendarContract.Reminders.CONTENT_URI, rv) }
            }
            "Created event \"${a.reqStr("title")}\" at ${start.toLocalDateTime()}."
        },

        dailyTool("clipboard.write", Risk.WRITE, "copy_to_clipboard",
            "Copy a piece of text to the clipboard so the user can paste it elsewhere. You CANNOT read the clipboard.",
            schema { str("text", "Text to copy.", required = true) },
            preview = { _, a -> "Copy to clipboard:\n" + previewText(a.reqStr("text")) }) { env, a ->
            withContext(Dispatchers.Main) {
                val cm = env.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AI", a.reqStr("text")))
            }
            "Copied ${a.reqStr("text").length} characters to the clipboard."
        },
    )
}
