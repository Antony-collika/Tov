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
import com.example.byokchat.ai.getJson
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

    private fun fail(): Nothing = throw ToolException("Biểu thức không hợp lệ gần vị trí ${i + 1}: '${s.drop(i).take(10)}'")
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
                '/' -> { i++; val d = power(); if (d == 0.0) throw ToolException("Không thể chia cho 0."); v /= d }
                '%' -> { i++; val d = power(); if (d == 0.0) throw ToolException("Không thể chia lấy dư cho 0."); v %= d }
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
                else -> throw ToolException("Không biết '$name'.")
            }
        }
        fail()
    }

    private fun call(name: String, a: List<Double>): Double {
        fun one(): Double { if (a.size != 1) throw ToolException("Hàm $name cần đúng 1 tham số."); return a[0] }
        return when (name) {
            "sqrt" -> Math.sqrt(one()); "abs" -> Math.abs(one())
            "sin" -> Math.sin(one()); "cos" -> Math.cos(one()); "tan" -> Math.tan(one())
            "asin" -> Math.asin(one()); "acos" -> Math.acos(one()); "atan" -> Math.atan(one())
            "ln" -> Math.log(one()); "log" -> Math.log10(one()); "exp" -> Math.exp(one())
            "floor" -> Math.floor(one()); "ceil" -> Math.ceil(one()); "round" -> Math.rint(one())
            "pow" -> { if (a.size != 2) throw ToolException("pow cần 2 tham số."); Math.pow(a[0], a[1]) }
            "min" -> a.minOrNull() ?: throw ToolException("min cần ít nhất 1 tham số.")
            "max" -> a.maxOrNull() ?: throw ToolException("max cần ít nhất 1 tham số.")
            else -> throw ToolException("Hàm '$name' không được hỗ trợ.")
        }
    }
}

private fun fmtResult(d: Double): String {
    if (d.isNaN() || d.isInfinite()) throw ToolException("Kết quả không xác định (NaN hoặc vô cực).")
    if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
    val bd = BigDecimal(d).round(MathContext(12)).stripTrailingZeros()
    return if (Math.abs(d) >= 1e15 || Math.abs(d) < 1e-6) bd.toString() else bd.toPlainString()
}

// ---------- Ngày giờ ----------
private val weekdays = mapOf(
    DayOfWeek.MONDAY to "Thứ Hai", DayOfWeek.TUESDAY to "Thứ Ba", DayOfWeek.WEDNESDAY to "Thứ Tư",
    DayOfWeek.THURSDAY to "Thứ Năm", DayOfWeek.FRIDAY to "Thứ Sáu", DayOfWeek.SATURDAY to "Thứ Bảy", DayOfWeek.SUNDAY to "Chủ Nhật",
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
    throw ToolException("Không hiểu ngày giờ '$s'. Hãy dùng dạng 2026-11-06 hoặc 2026-11-06T14:30 hoặc 06/11/2026.")
}

// ---------- Web ----------
private fun htmlToText(html: String): String {
    val cleaned = html
        .replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>"), "\n")
    val text = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
    return text.replace('\u00A0', ' ').lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

private fun noteId() = UUID.randomUUID().toString().take(8)

/** Nhóm 4: công cụ công việc hằng ngày. */
object DailyTools {
    fun build(): List<Tool> = listOf(
        dailyTool("calc.eval", Risk.READ, "calculator",
            "Tính một biểu thức toán học. Hỗ trợ + - * / % (chia dư) ^ (lũy thừa), ngoặc, hằng pi và e, hàm sqrt abs sin cos tan asin acos atan ln log exp floor ceil round pow min max (góc tính bằng radian). Hãy dùng công cụ này thay vì tự tính nhẩm.",
            schema { str("expression", "Biểu thức, ví dụ: (12.5 + 3) * 4 / sqrt(16)", required = true) }) { _, a ->
            val expr = a.reqStr("expression").replace('×', '*').replace('÷', '/').replace("−", "-")
            "${a.reqStr("expression")} = ${fmtResult(Calc(expr).eval())}"
        },

        dailyTool("time.now", Risk.READ, "current_time",
            "Lấy ngày giờ hiện tại của thiết bị (kèm thứ trong tuần và múi giờ).",
            schema { str("timezone", "Múi giờ IANA, ví dụ 'Asia/Ho_Chi_Minh', 'America/New_York' (mặc định: múi giờ của máy).") }) { _, a ->
            val zone = a.str("timezone")?.let { runCatching { ZoneId.of(it) }.getOrNull() ?: throw ToolException("Múi giờ '$it' không hợp lệ.") } ?: ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            "${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))} (${weekdays[now.dayOfWeek]}), múi giờ ${zone.id} (UTC${now.offset.id.replace("Z", "+00:00")})"
        },

        dailyTool("time.diff", Risk.READ, "date_difference",
            "Tính khoảng cách giữa hai ngày/giờ: tổng số ngày, giờ, phút và dạng 'x năm y tháng z ngày'.",
            schema {
                str("start", "Mốc đầu: 2026-11-06, 2026-11-06T14:30 hoặc 06/11/2026.", required = true)
                str("end", "Mốc cuối (mặc định: hiện tại).")
            }) { _, a ->
            val zone = ZoneId.systemDefault()
            val s = parseDateTime(a.reqStr("start"), zone)
            val e = a.str("end")?.let { parseDateTime(it, zone) } ?: ZonedDateTime.now(zone)
            val days = ChronoUnit.DAYS.between(s.toLocalDate(), e.toLocalDate())
            val dur = Duration.between(s, e)
            val p = if (s.toLocalDate() <= e.toLocalDate()) Period.between(s.toLocalDate(), e.toLocalDate()) else Period.between(e.toLocalDate(), s.toLocalDate())
            "Từ ${s.toLocalDateTime()} đến ${e.toLocalDateTime()}:\n" +
                "• Chênh lệch ${days} ngày theo lịch (${if (days < 0) "mốc cuối nằm TRƯỚC mốc đầu" else "mốc cuối sau mốc đầu"})\n" +
                "• Tổng: ${dur.toHours()} giờ = ${dur.toMinutes()} phút\n" +
                "• Dạng lịch: ${p.years} năm ${p.months} tháng ${p.days} ngày"
        },

        dailyTool("web.fetch", Risk.READ, "fetch_url",
            "Đọc nội dung một trang web từ đường link (http/https) và trả về dạng chữ thuần, đã bỏ phần mã. Có thể cắt bớt nếu trang dài.",
            schema {
                str("url", "Đường link đầy đủ bắt đầu bằng http:// hoặc https://", required = true)
                int("max_chars", "Số ký tự tối đa trả về (mặc định 15000, tối đa 40000).")
            }) { _, a ->
            val url = a.reqStr("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) throw ToolException("Link phải bắt đầu bằng http:// hoặc https://")
            val max = (a.int("max_chars") ?: 15000).coerceIn(500, 40000)
            val req = try {
                Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) ByokChat").header("Accept-Language", "vi,en;q=0.8").build()
            } catch (e: IllegalArgumentException) {
                throw ToolException("Link không hợp lệ.")
            }
            val (type, body) = try {
                Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw ToolException("Trang trả về lỗi ${r.code}.")
                    val src = r.body?.source() ?: throw ToolException("Trang không có nội dung.")
                    src.request(1_500_000)
                    val bytes = src.buffer.snapshot().toByteArray().let { if (it.size > 1_500_000) it.copyOf(1_500_000) else it }
                    (r.header("Content-Type") ?: "") to String(bytes, Charsets.UTF_8)
                }
            } catch (e: java.io.IOException) {
                throw ToolException("Không tải được trang: ${e.message}")
            }
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.get(1)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
            val text = if (type.contains("html", true) || body.trimStart().startsWith("<")) htmlToText(body) else body
            val out = if (text.length > max) text.take(max) + "\n…[đã cắt bớt, còn ${text.length - max} ký tự]" else text
            (if (title != null) "Tiêu đề: $title\n" else "") + "Nguồn: $url\n\n" + out
        },

        dailyTool("web.search", Risk.READ, "web_search",
            "Tìm kiếm trên web và trả về danh sách kết quả (tiêu đề, link, mô tả). Dùng web fetch để đọc chi tiết một kết quả.",
            schema {
                str("query", "Từ khóa tìm kiếm.", required = true)
                int("count", "Số kết quả (mặc định 5, tối đa 10).")
            },
            unavailableHint = "Cần nhập API key dịch vụ tìm kiếm trong Cài đặt.",
            isAvailable = { it.settings.searchApiKey.value.isNotBlank() }) { env, a ->
            val q = a.reqStr("query")
            val n = (a.int("count") ?: 5).coerceIn(1, 10)
            val key = env.settings.searchApiKey.value
            val lines = mutableListOf<String>()
            if (env.settings.searchProvider.value == "TAVILY") {
                val body = buildJsonObject { put("query", q); put("max_results", n) }.toString()
                val req = Request.Builder().url("https://api.tavily.com/search").header("Authorization", "Bearer $key")
                    .post(body.toRequestBody(Http.JSON)).build()
                val root = Http.client.getJson(req).jsonObject
                root["results"]?.jsonArray?.forEach { r ->
                    val o = r.jsonObject
                    lines.add("• ${o["title"]?.jsonPrimitive?.contentOrNull}\n  ${o["url"]?.jsonPrimitive?.contentOrNull}\n  ${o["content"]?.jsonPrimitive?.contentOrNull?.take(300)}")
                }
            } else {
                val url = "https://api.search.brave.com/res/v1/web/search?count=$n&q=" + java.net.URLEncoder.encode(q, "UTF-8")
                val req = Request.Builder().url(url).header("Accept", "application/json").header("X-Subscription-Token", key).get().build()
                val root = Http.client.getJson(req).jsonObject
                root["web"]?.jsonObject?.get("results")?.jsonArray?.forEach { r ->
                    val o = r.jsonObject
                    val desc = o["description"]?.jsonPrimitive?.contentOrNull?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
                    lines.add("• ${o["title"]?.jsonPrimitive?.contentOrNull}\n  ${o["url"]?.jsonPrimitive?.contentOrNull}\n  ${desc?.take(300)}")
                }
            }
            if (lines.isEmpty()) "Không có kết quả cho '$q'." else "Kết quả tìm '$q':\n" + lines.joinToString("\n")
        },

        // ----- Ghi chú nhớ lâu -----
        dailyTool("note.save", Risk.WRITE, "save_note",
            "Lưu một ghi chú nhớ lâu (tồn tại qua các cuộc trò chuyện). Dùng cho thông tin người dùng muốn bạn nhớ.",
            schema {
                str("title", "Tiêu đề ngắn.", required = true)
                str("content", "Nội dung ghi chú.", required = true)
            },
            preview = { _, a -> "Lưu ghi chú \"${a.reqStr("title")}\":\n" + previewText(a.reqStr("content")) }) { env, a ->
            val now = System.currentTimeMillis()
            val id = noteId()
            env.db.notes().upsert(NoteEntity(id, a.reqStr("title").trim(), a.reqStr("content"), now, now))
            "Đã lưu ghi chú (id: $id)."
        },

        dailyTool("note.list", Risk.READ, "list_notes",
            "Liệt kê các ghi chú đã lưu (id, tiêu đề, đoạn đầu). Có thể lọc theo từ khóa.",
            schema { str("query", "Chỉ hiện ghi chú chứa từ khóa này (không bắt buộc).") }) { env, a ->
            val q = a.str("query").orEmpty()
            val notes = env.db.notes().all().filter { q.isBlank() || TextUtil.findAll(it.title + "\n" + it.content, q).isNotEmpty() }
            if (notes.isEmpty()) "Chưa có ghi chú nào." else notes.joinToString("\n") { "• [${it.id}] ${it.title} — ${it.content.replace("\n", " ").take(80)}" }
        },

        dailyTool("note.get", Risk.READ, "read_note", "Đọc đầy đủ một ghi chú theo id.",
            schema { str("id", "Id ghi chú (lấy từ list_notes).", required = true) }) { env, a ->
            val n = env.db.notes().get(a.reqStr("id").trim()) ?: throw ToolException("Không có ghi chú id '${a.reqStr("id")}'.")
            "# ${n.title}\n\n${n.content}"
        },

        dailyTool("note.update", Risk.WRITE, "update_note", "Sửa tiêu đề hoặc nội dung một ghi chú. Đặt append=true để thêm nội dung vào cuối thay vì thay thế.",
            schema {
                str("id", "Id ghi chú.", required = true)
                str("title", "Tiêu đề mới (không bắt buộc).")
                str("content", "Nội dung mới (không bắt buộc).")
                bool("append", "Thêm content vào cuối thay vì thay thế (mặc định false).")
            },
            preview = { env, a ->
                val n = env.db.notes().get(a.reqStr("id").trim())
                "Sửa ghi chú \"${n?.title ?: a.reqStr("id")}\":\n" + (a.str("title")?.let { "Tiêu đề mới: $it\n" } ?: "") + (a.str("content")?.let { (if (a.bool("append") == true) "Thêm vào cuối:\n" else "Nội dung mới:\n") + previewText(it) } ?: "")
            }) { env, a ->
            val n = env.db.notes().get(a.reqStr("id").trim()) ?: throw ToolException("Không có ghi chú id '${a.reqStr("id")}'.")
            val newContent = a.str("content")?.let { if (a.bool("append") == true) n.content.trimEnd() + "\n" + it else it } ?: n.content
            env.db.notes().upsert(n.copy(title = a.str("title")?.trim() ?: n.title, content = newContent, updatedAt = System.currentTimeMillis()))
            "Đã cập nhật ghi chú ${n.id}."
        },

        dailyTool("note.delete", Risk.DELETE, "delete_note", "Xóa một ghi chú. Người dùng luôn phải xác nhận.",
            schema { str("id", "Id ghi chú.", required = true) },
            preview = { env, a -> "XÓA ghi chú: " + (env.db.notes().get(a.reqStr("id").trim())?.let { "\"${it.title}\"" } ?: a.reqStr("id")) }) { env, a ->
            env.db.notes().get(a.reqStr("id").trim()) ?: throw ToolException("Không có ghi chú id '${a.reqStr("id")}'.")
            env.db.notes().delete(a.reqStr("id").trim())
            "Đã xóa ghi chú."
        },

        // ----- Việc cần làm -----
        dailyTool("todo.add", Risk.WRITE, "add_todo", "Thêm một việc vào danh sách việc cần làm.",
            schema { str("text", "Nội dung việc cần làm.", required = true) },
            preview = { _, a -> "Thêm việc: ${a.reqStr("text")}" }) { env, a ->
            val id = noteId()
            env.db.todos().upsert(TodoEntity(id, a.reqStr("text").trim(), false, System.currentTimeMillis()))
            "Đã thêm việc (id: $id)."
        },

        dailyTool("todo.list", Risk.READ, "list_todos", "Xem danh sách việc cần làm.",
            schema { str("filter", "Lọc theo trạng thái.", enum = listOf("all", "open", "done")) }) { env, a ->
            val f = a.str("filter") ?: "all"
            val items = env.db.todos().all().filter { f == "all" || (f == "open" && !it.done) || (f == "done" && it.done) }
            if (items.isEmpty()) "Danh sách trống." else items.joinToString("\n") { "${if (it.done) "☑" else "☐"} [${it.id}] ${it.text}" }
        },

        dailyTool("todo.complete", Risk.WRITE, "complete_todo", "Đánh dấu một việc đã xong (hoặc chưa xong nếu done=false).",
            schema {
                str("id", "Id việc.", required = true)
                bool("done", "true = đã xong (mặc định), false = chưa xong.")
            },
            preview = { env, a -> "Đánh dấu ${if (a.bool("done") == false) "CHƯA xong" else "đã xong"}: " + (env.db.todos().get(a.reqStr("id").trim())?.text ?: a.reqStr("id")) }) { env, a ->
            val t = env.db.todos().get(a.reqStr("id").trim()) ?: throw ToolException("Không có việc id '${a.reqStr("id")}'.")
            env.db.todos().upsert(t.copy(done = a.bool("done") ?: true))
            "Đã cập nhật: ${t.text}"
        },

        dailyTool("todo.delete", Risk.DELETE, "delete_todo", "Xóa một việc khỏi danh sách. Người dùng luôn phải xác nhận.",
            schema { str("id", "Id việc.", required = true) },
            preview = { env, a -> "XÓA việc: " + (env.db.todos().get(a.reqStr("id").trim())?.text ?: a.reqStr("id")) }) { env, a ->
            env.db.todos().get(a.reqStr("id").trim()) ?: throw ToolException("Không có việc id '${a.reqStr("id")}'.")
            env.db.todos().delete(a.reqStr("id").trim())
            "Đã xóa việc."
        },

        // ----- Báo thức, lịch, clipboard -----
        dailyTool("alarm.set", Risk.WRITE, "set_alarm",
            "Đặt báo thức trong ứng dụng Đồng hồ của điện thoại. Không có 'days' thì chuông một lần vào lần tới giờ đó (trong 24 giờ).",
            schema {
                int("hour", "Giờ (0-23).", required = true)
                int("minute", "Phút (0-59).", required = true)
                str("message", "Nhãn báo thức.")
                strArray("days", "Lặp lại hằng tuần vào các thứ: mon, tue, wed, thu, fri, sat, sun.")
            },
            preview = { _, a -> "Đặt báo thức lúc %02d:%02d".format(a.int("hour") ?: 0, a.int("minute") ?: 0) + (a.str("message")?.let { " — $it" } ?: "") + (a.strList("days")?.takeIf { it.isNotEmpty() }?.let { "\nLặp lại: ${it.joinToString(", ")}" } ?: "") }) { env, a ->
            val h = a.int("hour") ?: throw ToolException("Thiếu 'hour'.")
            val m = a.int("minute") ?: throw ToolException("Thiếu 'minute'.")
            if (h !in 0..23 || m !in 0..59) throw ToolException("Giờ phải từ 0-23 và phút từ 0-59.")
            val dayMap = mapOf("mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY)
            val days = a.strList("days").orEmpty().map { d -> dayMap[d.trim().lowercase().take(3)] ?: throw ToolException("Thứ '$d' không hợp lệ.") }
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
                throw ToolException("Không đặt được báo thức (không có ứng dụng Đồng hồ phù hợp hoặc bị hệ thống chặn): ${e.message}")
            }
            "Đã gửi yêu cầu đặt báo thức lúc %02d:%02d".format(h, m) + (if (days.isEmpty()) " (một lần)." else ", lặp lại hằng tuần.")
        },

        dailyTool("calendar.create", Risk.WRITE, "create_calendar_event",
            "Tạo sự kiện trong lịch của điện thoại (cần người dùng cấp quyền lịch).",
            schema {
                str("title", "Tên sự kiện.", required = true)
                str("start", "Bắt đầu, giờ địa phương, dạng 2026-11-07T09:00.", required = true)
                str("end", "Kết thúc (mặc định: 1 giờ sau khi bắt đầu).")
                str("description", "Mô tả.")
                str("location", "Địa điểm.")
                int("reminder_minutes", "Nhắc trước bao nhiêu phút (không bắt buộc).")
            },
            preview = { _, a -> "Tạo sự kiện \"${a.reqStr("title")}\"\nBắt đầu: ${a.reqStr("start")}" + (a.str("end")?.let { "\nKết thúc: $it" } ?: "") + (a.str("location")?.let { "\nĐịa điểm: $it" } ?: "") }) { env, a ->
            val zone = ZoneId.systemDefault()
            val start = parseDateTime(a.reqStr("start"), zone)
            val end = a.str("end")?.let { parseDateTime(it, zone) } ?: start.plusHours(1)
            if (!end.isAfter(start)) throw ToolException("Thời điểm kết thúc phải sau thời điểm bắt đầu.")
            if (!env.permissions.ensure(listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))) {
                throw ToolException("Chưa được cấp quyền truy cập lịch. Người dùng cần cho phép quyền Lịch cho ứng dụng.")
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
            } ?: throw ToolException("Không tìm thấy lịch nào có thể ghi trên điện thoại.")
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, a.reqStr("title"))
                put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                a.str("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                a.str("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }
            val uri = cr.insert(CalendarContract.Events.CONTENT_URI, values) ?: throw ToolException("Không tạo được sự kiện.")
            a.int("reminder_minutes")?.let { mins ->
                val rv = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, uri.lastPathSegment?.toLongOrNull() ?: 0L)
                    put(CalendarContract.Reminders.MINUTES, mins)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                runCatching { cr.insert(CalendarContract.Reminders.CONTENT_URI, rv) }
            }
            "Đã tạo sự kiện \"${a.reqStr("title")}\" lúc ${start.toLocalDateTime()}."
        },

        dailyTool("clipboard.write", Risk.WRITE, "copy_to_clipboard",
            "Chép một đoạn chữ vào bộ nhớ tạm (clipboard) để người dùng dán ở nơi khác. Bạn KHÔNG đọc được clipboard.",
            schema { str("text", "Nội dung cần chép.", required = true) },
            preview = { _, a -> "Chép vào clipboard:\n" + previewText(a.reqStr("text")) }) { env, a ->
            withContext(Dispatchers.Main) {
                val cm = env.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AI", a.reqStr("text")))
            }
            "Đã chép ${a.reqStr("text").length} ký tự vào clipboard."
        },
    )
}
