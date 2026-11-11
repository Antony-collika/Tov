package com.example.byokchat.tools

/**
 * Parser cho file tools.md của thẻ Tools (Đợt E, quyết định 4/5/7).
 *
 * Định dạng (mượn FPilot, xem file mẫu workspace/user/files/tools.md):
 * - Một khối cho mỗi tool, bắt đầu bằng "## <tên>"; thứ tự khối = thứ tự
 *   offer cho model. Xóa cả khối = ẩn tool đó.
 * - Dòng bắt đầu bằng ">" (kể cả thụt đầu dòng) là ghi chú, không gửi.
 * - Mỗi khối có 4 trường: uid + origin_description (định danh tool thật,
 *   KHÔNG được đổi), name + description (cái model thấy, sửa được).
 *   origin_description và description là đa dòng: các dòng thường tiếp theo
 *   được nối vào trường đó. uid/name chỉ nhận một dòng.
 * - Parse lại mỗi lần gửi (không cache). File rỗng (0 khối) là HỢP LỆ và
 *   cho 0 tool — khác FPilot (FPilot lỗi + chặn gửi khi rỗng).
 * - origin_description chỉ dùng để kiểm tra, không lưu vào kết quả.
 */
class ToolsMdParseException(message: String) : Exception(message)

/** Một tool đã parse từ file: uid thật + tên/mô tả model sẽ thấy. */
data class ParsedTool(val uid: String, val name: String, val description: String)

object ToolsMdParser {
    private val NAME_RX = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    const val MAX_DESCRIPTION_CHARS = 1500

    /** Chuẩn hóa khoảng trắng để đối chiếu origin_description (như FPilot). */
    fun normalizeWs(s: String): String = s.trim().replace(Regex("\\s+"), " ")

    /**
     * Parse nội dung tools.md.
     * @param origins uid -> mô tả gốc (defaultDescription trong code). Tool
     *   nào không có trong map này thì parse lỗi, nêu rõ uid.
     * @return danh sách tool theo đúng thứ tự khối; rỗng nếu file không có khối nào.
     * @throws ToolsMdParseException khi file sai format, kèm số dòng.
     */
    fun parse(text: String, origins: Map<String, String>): List<ParsedTool> {
        val drafts = readBlocks(text)
        if (drafts.isEmpty()) return emptyList()
        val seenUids = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        return drafts.map { d ->
            val uid = d.uid?.trim().orEmpty().ifEmpty { d.heading }
            if (uid.isBlank()) {
                throw ToolsMdParseException("A tools block (line ${d.line}) has no uid and no heading to use as one.")
            }
            val origin = origins[uid]
                ?: throw ToolsMdParseException(
                    "Tool \"$uid\" (line ${d.line}) is not a tool of this app, so it cannot be offered to the model.",
                )
            if (!seenUids.add(uid)) {
                throw ToolsMdParseException("Tool \"$uid\" appears twice (line ${d.line}). Each tool may appear only once per file.")
            }
            if (d.origin != null && normalizeWs(d.origin.toString()) != normalizeWs(origin)) {
                throw ToolsMdParseException(
                    "The origin_description of \"$uid\" (line ${d.line}) does not match the tool's original " +
                        "description. Do not change the uid and origin_description lines.",
                )
            }
            val name = d.name?.trim().orEmpty()
            if (name.isEmpty()) {
                throw ToolsMdParseException("Tool \"$uid\" (line ${d.line}) has no name. Each block needs a \"name:\" line.")
            }
            if (!NAME_RX.matches(name)) {
                throw ToolsMdParseException(
                    "The name \"$name\" of tool \"$uid\" (line ${d.line}) is not valid. A tool name may only contain " +
                        "unaccented letters, digits, underscores (_) or hyphens (-); it must start with a letter or _; at most 64 characters.",
                )
            }
            if (!seenNames.add(name.lowercase())) {
                throw ToolsMdParseException("Two tools use the name \"$name\" (line ${d.line}). Each tool needs a different name.")
            }
            val description = d.description?.toString()?.trim().orEmpty()
            if (description.isEmpty()) {
                throw ToolsMdParseException("Tool \"$uid\" (line ${d.line}) has no description. Each block needs a \"description:\" line.")
            }
            if (description.length > MAX_DESCRIPTION_CHARS) {
                throw ToolsMdParseException("The description of \"$uid\" is too long (max $MAX_DESCRIPTION_CHARS characters).")
            }
            ParsedTool(uid, name, description)
        }
    }

    /**
     * File mẫu chuẩn: liệt kê TẤT CẢ tool kèm tên/mô tả gốc (như FPilot).
     * Chỉ để user lấy lại khi cần (vd sửa sai format làm parse lỗi) — app
     * KHÔNG BAO GIỜ tự ghi đè file của user bằng mẫu này.
     */
    fun buildTemplate(tools: List<Tool>): String = buildString {
        appendLine("> Tools card. One block per tool, in the order they are offered to the model.")
        appendLine("> Lines starting with \">\" are notes. Delete a whole block to hide that tool from the model.")
        tools.forEach { t ->
            appendLine()
            appendLine("## ${t.uid}")
            appendLine("> Do not change the two lines below (uid and origin_description). They tell you which tool this really is.")
            appendLine("uid: ${t.uid}")
            appendLine("origin_description: ${t.defaultDescription}")
            appendLine("> What the model sees. You can edit name and description.")
            appendLine("name: ${t.defaultName}")
            appendLine("description: ${t.defaultDescription}")
        }
    }

    // ---------- Đọc khối (tách riêng để test được phần tách khối) ----------

    private enum class Field { UID, ORIGIN, NAME, DESCRIPTION }

    private class Draft(val heading: String, val line: Int) {
        var uid: String? = null
        var origin: StringBuilder? = null
        var name: String? = null
        var description: StringBuilder? = null
        var lastField: Field? = null
    }

    private fun readBlocks(text: String): List<Draft> {
        val drafts = mutableListOf<Draft>()
        var current: Draft? = null
        text.lines().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val line = raw.trimEnd()
            when {
                line.trimStart().startsWith(">") -> Unit // Ghi chú: bỏ qua, kể cả thụt đầu dòng.
                line.trim().startsWith("## ") -> {
                    current = Draft(line.trim().removePrefix("## ").trim(), lineNo)
                    drafts += current!!
                }
                line.isBlank() -> {
                    // Dòng trống trong trường đa dòng thì giữ lại (ngắt đoạn);
                    // còn không thì bỏ qua.
                    when (current?.lastField) {
                        Field.ORIGIN -> current?.origin?.append("\n")
                        Field.DESCRIPTION -> current?.description?.append("\n")
                        else -> Unit
                    }
                }
                current == null -> {
                    throw ToolsMdParseException(
                        "Text before the first \"## \" block (line $lineNo): \"${line.take(60)}\". " +
                            "Each tool must start with a \"## \" heading.",
                    )
                }
                line.startsWith("uid:") -> {
                    if (current.uid != null) throw ToolsMdParseException("Duplicate \"uid:\" line (line $lineNo).")
                    current.uid = line.removePrefix("uid:").trim()
                    current.lastField = Field.UID
                }
                line.startsWith("origin_description:") -> {
                    if (current.origin != null) throw ToolsMdParseException("Duplicate \"origin_description:\" line (line $lineNo).")
                    current.origin = StringBuilder(line.removePrefix("origin_description:").trim())
                    current.lastField = Field.ORIGIN
                }
                line.startsWith("name:") -> {
                    if (current.name != null) throw ToolsMdParseException("Duplicate \"name:\" line (line $lineNo).")
                    current.name = line.removePrefix("name:").trim()
                    current.lastField = Field.NAME
                }
                line.startsWith("description:") -> {
                    if (current.description != null) throw ToolsMdParseException("Duplicate \"description:\" line (line $lineNo).")
                    current.description = StringBuilder(line.removePrefix("description:").trim())
                    current.lastField = Field.DESCRIPTION
                }
                current.lastField == Field.ORIGIN -> {
                    current.origin?.append("\n")?.append(line.trim())
                }
                current.lastField == Field.DESCRIPTION -> {
                    current.description?.append("\n")?.append(line.trim())
                }
                else -> {
                    throw ToolsMdParseException(
                        "Unexpected text (line $lineNo): \"${line.take(60)}\". After \"uid:\" or \"name:\" only a new " +
                            "field or a \"## \" block is allowed.",
                    )
                }
            }
        }
        return drafts
    }
}
