package com.example.byokchat.chat

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lỗi parse history.md / history.meta.json, kèm số dòng khi có. */
class HistoryParseException(message: String) : Exception(message)

/** Một tin nhắn đã parse từ history.md (sidecar). */
data class ParsedHistoryMessage(
    /** UUID của tin (dòng {"id":...} ngay sau heading). */
    val id: String,
    val turnNo: Int,
    /** "user" hoặc "assistant". */
    val role: String,
    val text: String,
    /** Nội dung lỗi của lượt lỗi (dòng "(error: ...)" cuối tin assistant). */
    val error: String? = null,
)

/** Toàn bộ history.md đã parse (chỉ định dạng sidecar; không dùng format legacy). */
data class ParsedHistory(
    val session: String,
    val title: String,
    val created: String?,
    /** Số lượt tiếp theo, đã kẹp lên tối thiểu (số lượt lớn nhất + 1). */
    val nextTurn: Int,
    val messages: List<ParsedHistoryMessage>,
)

/**
 * Parser cho history.md + history.meta.json (Đợt E, format sidecar mượn FPilot).
 * Chỉ parse, không chạm Room — việc áp vào DB nằm ở HistorySync.
 * LƯU Ý: dòng "## " trong nội dung tin nhắn sẽ bị hiểu là heading mới (giới
 * hạn chung của format, FPilot cũng vậy); vòng tool trong .md ("## Tn round k")
 * thì parse lỗi — vòng tool phải nằm trong history.meta.json.
 */
object HistoryMdParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val headingRx = Regex("^## T(\\d+) (user|assistant)\\s*$")
    private val imagesLineRx = Regex("^\\(\\+ \\d+ image\\(s\\)\\)$")
    private val errorLineRx = Regex("^\\(error: (.*)\\)$")

    /** Parse history.md sidecar. */
    fun parseMd(text: String): ParsedHistory {
        val lines = text.lines()
        var i = 0
        while (i < lines.size && lines[i].isBlank()) i++
        if (i >= lines.size || lines[i].trim() != "---") {
            throw HistoryParseException("history.md must start with a frontmatter block (\"---\" on the first line).")
        }
        val fmStart = i + 1
        var fmEnd = -1
        for (j in fmStart until lines.size) {
            if (lines[j].trim() == "---") { fmEnd = j; break }
        }
        if (fmEnd < 0) throw HistoryParseException("history.md frontmatter is not closed (missing second \"---\").")
        var session: String? = null
        var title: String? = null
        var created: String? = null
        var nextTurn: Int? = null
        for (j in fmStart until fmEnd) {
            val line = lines[j]
            if (line.isBlank()) continue
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "session" -> session = value
                "title" -> title = decodeTitle(value)
                "created" -> created = value.ifEmpty { null }
                "next_turn" -> nextTurn = value.toIntOrNull()
            }
        }
        val sessionId = session?.takeIf { it.isNotBlank() }
            ?: throw HistoryParseException("history.md frontmatter has no \"session:\".")

        val messages = mutableListOf<ParsedHistoryMessage>()
        val seenIds = mutableSetOf<String>()
        var currentTurn = 0
        var currentRole: String? = null
        var currentId: String? = null
        var currentLine = 0
        val currentText = mutableListOf<String>()
        fun flush() {
            val role = currentRole ?: return
            val id = currentId ?: throw HistoryParseException(
                "Message T$currentTurn $role (line $currentLine) has no {\"id\":...} line after its heading.",
            )
            if (!seenIds.add(id)) throw HistoryParseException("Message id \"$id\" appears twice (line $currentLine).")
            var body = currentText.joinToString("\n").trimEnd('\n')
            var error: String? = null
            val bodyLines = body.lines()
            if (role == "user" && bodyLines.lastOrNull()?.let { imagesLineRx.matches(it) } == true) {
                body = bodyLines.dropLast(1).joinToString("\n").trimEnd('\n')
            }
            if (role == "assistant") {
                val last = body.lines().lastOrNull()
                val m = last?.let { errorLineRx.matchEntire(it) }
                if (m != null) {
                    error = m.groupValues[1]
                    body = body.lines().dropLast(1).joinToString("\n").trimEnd('\n')
                }
            }
            messages += ParsedHistoryMessage(id, currentTurn, role, body, error)
            currentRole = null
            currentId = null
            currentText.clear()
        }
        i = fmEnd + 1
        while (i < lines.size) {
            val line = lines[i]
            val lineNo = i + 1
            when {
                line.trim().startsWith("## ") -> {
                    val m = headingRx.matchEntire(line.trim())
                        ?: throw HistoryParseException(
                            "Unknown section heading (line $lineNo): \"${line.trim().take(60)}\". " +
                                "Only \"## Tn user\" / \"## Tn assistant\" are allowed — tool rounds belong in history.meta.json, not in history.md.",
                        )
                    flush()
                    currentTurn = m.groupValues[1].toInt()
                    currentRole = m.groupValues[2]
                    currentLine = lineNo
                }
                currentRole != null && currentId == null -> {
                    // Dòng đầu tiên (không trống) sau heading phải là {"id":...}.
                    if (line.isBlank()) Unit
                    else {
                        val obj = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull()
                        val id = obj?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        if (id == null) {
                            throw HistoryParseException(
                                "Message T$currentTurn $currentRole (line $lineNo) has no {\"id\":...} line after its heading. " +
                                    "Expected a line like {\"id\":\"...\"}, got: \"${line.trim().take(60)}\".",
                            )
                        }
                        currentId = id
                    }
                }
                currentRole != null -> currentText += line
                line.isBlank() -> Unit
                else -> throw HistoryParseException(
                    "Text outside any message (line $lineNo): \"${line.trim().take(60)}\". " +
                        "Text must be inside a \"## Tn user\" / \"## Tn assistant\" section.",
                )
            }
            i++
        }
        flush()
        val maxTurn = messages.maxOfOrNull { it.turnNo } ?: 0
        val effectiveNext = maxOf(nextTurn ?: 0, maxTurn + 1)
        return ParsedHistory(sessionId, title.orEmpty(), created, effectiveNext, messages)
    }

    /** Parse history.meta.json (dùng lại model @Serializable của HistoryExporter). */
    fun parseMeta(text: String): HistoryMeta {
        val meta = try {
            json.decodeFromString(HistoryMeta.serializer(), text)
        } catch (e: Exception) {
            throw HistoryParseException("history.meta.json could not be read: ${e.message}")
        }
        if (meta.version != 1) {
            throw HistoryParseException("history.meta.json has unsupported version ${meta.version} (expected 1).")
        }
        if (meta.session.isBlank()) {
            throw HistoryParseException("history.meta.json has no \"session\".")
        }
        return meta
    }

    /** Title trong frontmatter ghi dạng chuỗi JSON có ngoặc kép; file cũ không có ngoặc thì lấy thô. */
    private fun decodeTitle(value: String): String {
        if (!value.startsWith("\"")) return value
        return runCatching { json.decodeFromString(String.serializer(), value) }.getOrDefault(value)
    }
}
