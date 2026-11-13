package com.example.byokchat.tools

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.ToolDef
import com.example.byokchat.chat.ModelFacingText
import com.example.byokchat.chat.TokenEstimator
import com.example.byokchat.chat.ToolOutcome
import com.example.byokchat.chat.ToolRuntime
import com.example.byokchat.data.normalizeWriteSubfolder
import com.example.byokchat.util.cap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Danh sách toàn bộ công cụ + cầu nối với vòng chat.
 * - UID cố định do app đặt; tên/mô tả GỐC lấy từ code, không bao giờ mất.
 *   Tên/mô tả model nhìn thấy chỉ còn đến từ file tools.md của thẻ Tools
 *   (đường "legacy" khi chưa chọn workspace — fs == null — thì dùng tên/mô
 *   tả gốc; xóa thẻ Tools = 0 tool).
 * - Chế độ Auto/Ask/Off (tầng quyền) vẫn ở trong app (ToolPrefs); tool tắt
 *   vẫn được offer cho model, chỉ chặn lúc gọi (quyết định 8, Đợt E).
 * - Mọi thay đổi trong ToolPrefs có hiệu lực từ request kế tiếp (definitions() đọc lại mỗi lần).
 */
class ToolRegistry(
    private val env: ToolEnv,
    private val prefs: ToolPrefs,
    private val confirmations: ConfirmationBroker,
) : ToolRuntime {
    val all: List<Tool> = FsTools.build() + TextTools.build() + DataTools.build() + DailyTools.build()
    private val byUid = all.associateBy { it.uid }

    fun tool(uid: String): Tool? = byUid[uid]

    /**
     * Tên/mô tả GỐC của tool, lấy từ code (Đợt E, quyết định 5): không bao
     * giờ mất, file tools.md không ghi đè. Tên/mô tả model thấy chỉ còn
     * đến từ file tools.md của thẻ Tools (xem definitions(exposed)); các
     * override tên/mô tả cũ trong ToolPrefs không còn hiệu lực nữa.
     */
    fun effectiveName(t: Tool): String = t.defaultName
    fun effectiveDescription(t: Tool): String = t.defaultDescription

    fun mode(t: Tool): ToolMode {
        val m = prefs.get(t.uid).mode?.let { runCatching { ToolMode.valueOf(it) }.getOrNull() } ?: t.defaultMode
        // Công cụ xóa không bao giờ được chạy tự động
        return if (t.risk == Risk.DELETE && m == ToolMode.AUTO) ToolMode.ASK else m
    }

    fun setMode(t: Tool, m: ToolMode) {
        prefs.update(t.uid) { it.copy(mode = if (m == t.defaultMode) null else m.name) }
    }

    /** Mô tả gốc của một tham số trong code (null nếu tham số không có mô tả). */
    fun defaultParamDescription(t: Tool, param: String): String? {
        val props = t.schema["properties"] as? JsonObject ?: return null
        val p = props[param] as? JsonObject ?: return null
        return (p["description"] as? JsonPrimitive)?.contentOrNull
    }

    /**
     * Mô tả tham số mà AI nhìn thấy (Đợt E, quyết định 5): luôn là mô tả
     * gốc trong code. Các override mô tả tham số cũ trong ToolPrefs không
     * còn hiệu lực nữa — file tools.md cũng không đổi được schema tham số.
     */
    fun paramDescription(t: Tool, param: String): String =
        defaultParamDescription(t, param).orEmpty()

    /** Schema thật sự gửi cho AI: luôn là schema gốc trong code. */
    fun effectiveSchema(t: Tool): JsonObject = t.schema

    fun isAvailable(t: Tool): Boolean =
        (!t.needsWorkspace || env.fs.accessible.value) && t.isAvailable(env)

    /** Có công cụ tệp nào đang bật không (để cảnh báo khi chưa chọn workspace). */
    fun hasEnabledWorkspaceTools(): Boolean = all.any { it.needsWorkspace && mode(it) != ToolMode.OFF }

    // ---------- ToolRuntime ----------

    /**
     * Danh sách công cụ đưa cho model (Đợt E).
     * - exposed == null (legacy, CHỈ khi chưa chọn workspace — fs == null):
     *   mọi tool dùng được, tên/mô tả/schema gốc. Không có thẻ Tools
     *   KHÔNG còn cho null nữa (xóa thẻ = 0 tool).
     * - exposed != null: chỉ các tool trong file, đúng thứ tự file/khối,
     *   tên/mô tả từ file, schema tham số lấy từ tool thật theo uid (file
     *   không đổi được schema).
     * - Cả hai đường đều KHÔNG lọc tool mode OFF (quyết định 8): tool tắt
     *   vẫn được offer, chỉ chặn lúc gọi trong execute(). Chỉ lọc tool
     *   không dùng được (chưa chọn workspace / thiếu API key) vì model có
     *   gọi cũng không chạy được.
     */
    override suspend fun definitions(exposed: List<ParsedTool>?): List<ToolDef> {
        withContext(Dispatchers.IO) { env.fs.refreshIfStale() }
        return if (exposed == null) {
            all.filter { isAvailable(it) }
                .map { ToolDef(it.defaultName, it.defaultDescription, it.schema) }
        } else {
            exposed.mapNotNull { parsed ->
                val tool = byUid[parsed.uid] ?: return@mapNotNull null // uid đã kiểm tra lúc parse; phòng thủ.
                if (!isAvailable(tool)) null else ToolDef(parsed.name, parsed.description, tool.schema)
            }
        }
    }

    /**
     * Map tên model gọi -> uid thật. Khi có file: CHỈ tra trong file (tool
     * không trong file thì không được offer nên cũng không chạy được, kể cả
     * khi tên đó trùng tên gốc của một tool khác). Khi legacy: tra tên gốc.
     */
    override fun uidForName(name: String, exposed: List<ParsedTool>?): String? =
        if (exposed != null) exposed.firstOrNull { it.name == name }?.uid
        else all.firstOrNull { it.defaultName == name }?.uid

    /** Tên model thấy của một uid: tên trong file nếu có, không thì tên gốc. */
    override fun nameForUid(uid: String, exposed: List<ParsedTool>?): String? =
        exposed?.firstOrNull { it.uid == uid }?.name ?: byUid[uid]?.defaultName

    override fun originDescriptions(): Map<String, String> =
        all.associate { it.uid to it.defaultDescription }

    override fun estimateToolTokens(): Int =
        all.filter { isAvailable(it) }
            .sumOf { TokenEstimator.text(it.defaultName + it.defaultDescription + it.schema.toString()) }

    /**
     * Kiểm tra giới hạn ghi của AI (Đợt E, quyết định 6 + quyết định 14). Trả về câu báo lỗi
     * cho model nếu vi phạm, null nếu được phép.
     * - Mọi đường dẫn ghi phải nằm dưới allowedWriteSubfolder (nếu có).
     * - Tool ghi nội dung văn bản (txt.* WRITE) thì đích phải là file
     *   dạng text — xét theo NỘI DUNG, không theo extension (quyết định
     *   14): file text sửa được bất kể đuôi nào, file nhị phân thì không.
     *   note.save/update không có đường dẫn trong args (app
     *   tự ghi Tov/notes/<id>.md) nên mặc nhiên đúng.
     * - Tool ghi khác (fs.copy/move/rename/zip, convert.*, csv.*, json.*):
     *   đích là file đầu ra theo đúng chức năng của tool (.zip/.json/.csv...)
     *   nên chỉ kiểm tra subfolder, không ép đuôi .md.
     * - Thư mục đích (dest_dir, directory, và path của fs.mkdir) chỉ kiểm
     *   tra subfolder (thư mục không có đuôi .md).
     * - Chỉ áp cho tool WRITE; tool READ không giới hạn, tool DELETE luôn
     *   hỏi user trước (và xóa không phải là ghi).
     */
    private fun aiWriteViolation(tool: Tool, args: JsonObject): String? {
        if (tool.risk != Risk.WRITE) return null
        val sub = normalizeWriteSubfolder(env.settings.allowedWriteSubfolder.value)
        fun underSubfolder(path: String): Boolean {
            if (sub.isEmpty()) return true
            val p = env.fs.pathInsideSelectedFolder(path)
            return p.startsWith("$sub/")
        }
        /**
         * Đích ghi có phải file dạng text không (quyết định 14): xét theo
         * nội dung (không có byte NUL trong 2000 byte đầu — xem
         * WorkspaceFs.isTextFile), không theo extension.
         * - File chưa có → file mới, nội dung ghi là text nên cho phép mọi đuôi.
         * - Không đọc được đích (chưa chọn workspace / đường dẫn sai) →
         *   cho qua để tool tự báo lỗi như cũ, không crash ở đây.
         * - Thư mục → cho qua để tool tự báo lỗi như cũ.
         */
        fun isTextTarget(path: String): Boolean {
            val doc = runCatching { env.fs.find(path) }.getOrNull() ?: return true
            if (env.fs.isDir(doc)) return true
            return env.fs.isTextFile(doc)
        }
        // Đích ghi dạng file của từng tool ("path" của fs.copy/move là NGUỒN
        // đọc nên không tính; đích của chúng nằm ở "dest").
        val fileDestKeys: List<String> = when {
            tool.uid.startsWith("txt.") -> listOf("path", "output_path")
            tool.uid in setOf("fs.copy", "fs.move", "fs.rename", "fs.zip") -> listOf("dest")
            tool.uid.startsWith("convert.") -> listOf("output_path")
            tool.uid.startsWith("csv.") || tool.uid.startsWith("json.") -> listOf("path")
            tool.uid == "fs.mkdir" || tool.uid == "note.save" || tool.uid == "note.update" -> emptyList()
            else -> listOf("path", "dest", "output_path")
        }
        val dirDestKeys = listOf("dest_dir", "directory") +
            if (tool.uid == "fs.mkdir") listOf("path") else emptyList()
        val textWriter = tool.uid.startsWith("txt.")
        for (key in fileDestKeys) {
            val p = args.str(key) ?: continue
            if (textWriter && !isTextTarget(p)) return ModelFacingText.notTextFile(p)
            if (!underSubfolder(p)) return ModelFacingText.outsideWriteSubfolder(p, sub)
        }
        if (tool.uid == "txt.replace_many") {
            args.strList("paths")?.forEach { p ->
                if (!isTextTarget(p)) return ModelFacingText.notTextFile(p)
                if (!underSubfolder(p)) return ModelFacingText.outsideWriteSubfolder(p, sub)
            }
        }
        for (key in dirDestKeys) {
            val p = args.str(key) ?: continue
            if (!underSubfolder(p)) return ModelFacingText.outsideWriteSubfolder(p, sub)
        }
        return null
    }

    /** Ghi vào Tov/ luôn phải hỏi user, kể cả khi công cụ đang ở mode Auto (Đợt D).
     * Kiểm tra mọi tham số có thể là đích ghi: đường dẫn đích trực tiếp
     * (path/source/dest), đầu ra chuyển đổi (output_path), thư mục giải nén
     * (dest_dir), thư mục xử lý hàng loạt (directory) và danh sách file
     * xử lý hàng loạt (paths). Tham số chỉ là nguồn đọc (vd 'sources' của
     * fs.zip) không tính — đích ghi của nó đã nằm ở 'dest'. */
    private fun targetsProtectedDir(tool: Tool, args: JsonObject): Boolean {
        if (tool.risk == Risk.READ) return false
        if (listOf("path", "source", "dest", "output_path", "dest_dir", "directory").any { key ->
            val p = args.str(key) ?: return@any false
            isProtectedPath(p, env.fs.rootNames())
        }) return true
        // txt.replace_many ghi vào từng file trong danh sách 'paths'.
        return args.strList("paths")?.any { isProtectedPath(it, env.fs.rootNames()) } == true
    }

    override fun endTurn(turnId: String) = confirmations.endTurn(turnId)

    override suspend fun execute(call: AiToolCall, turnId: String, exposed: List<ParsedTool>?): ToolOutcome {
        // Mỗi lần chạy công cụ bắt đầu với bộ nhớ đệm metadata sạch (xem WorkspaceFs).
        env.fs.clearMeta()
        // Tôn trọng danh sách file (Đợt E): tên model gọi phải nằm trong file
        // (hoặc là tên gốc khi legacy); tool không được offer thì không chạy.
        val tool = if (exposed != null) {
            exposed.firstOrNull { it.name == call.name }?.uid?.let(byUid::get)
        } else {
            all.firstOrNull { it.defaultName == call.name }
        } ?: return ToolOutcome("", ModelFacingText.noSuchTool(call.name), true)
        if (!isAvailable(tool)) return ToolOutcome("", ModelFacingText.noSuchTool(call.name), true)
        // Quyết định 8: tool tắt ở Settings vẫn được offer, chỉ chặn lúc gọi —
        // model được báo rõ là tool đang tắt để tự nói với user.
        if (mode(tool) == ToolMode.OFF) {
            return ToolOutcome(tool.uid, ModelFacingText.toolTurnedOff(call.name), true)
        }
        val displayName = exposed?.firstOrNull { it.uid == tool.uid }?.name ?: tool.defaultName
        val args: JsonObject = try {
            if (call.argsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(call.argsJson) as? JsonObject
                ?: return ToolOutcome(tool.uid, ModelFacingText.ARGS_NOT_OBJECT, true)
        } catch (e: Exception) {
            return ToolOutcome(tool.uid, ModelFacingText.invalidArgsJson(e.message), true)
        }

        // Đợt E, quyết định 6 + quyết định 14: "Allowed write subfolder" +
        // AI chỉ ghi file dạng text (theo nội dung, không theo extension) —
        // thực thi ở tầng tool (không phải tầng SAF), chặn ngay trước khi hỏi
        // user để không hỏi về một lần ghi vốn đã bị chặn.
        aiWriteViolation(tool, args)?.let { return ToolOutcome(tool.uid, it, true) }

        // Thư mục bảo vệ Tov/ (Đợt D): AI chỉ đọc được, ghi luôn phải hỏi
        // user — kể cả khi công cụ đang ở mode Auto.
        val protected = targetsProtectedDir(tool, args)
        if (mode(tool) == ToolMode.ASK || protected) {
            val detail = try {
                // Luồng công việc gắn kèm Job hiện tại: khi bấm Dừng, các vòng lặp của công cụ sẽ thoát sớm.
                withContext(Dispatchers.IO + WorkspaceFs.cancelJob.asContextElement(currentCoroutineContext()[Job])) { tool.preview(env, args) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ModelFacingText.previewFailed(e.message) + "\n" + args.toString().take(500)
            } + if (protected) "\nThis is inside the protected Tov/ folder, so it always asks first." else ""
            val allowed = confirmations.ask(ConfirmRequest(displayName, tool.uid, tool.risk, detail, turnId))
            if (!allowed) return ToolOutcome(tool.uid, ModelFacingText.DENIED_ACTION, true)
        }

        return try {
            ToolOutcome(
                tool.uid,
                withContext(Dispatchers.IO + WorkspaceFs.cancelJob.asContextElement(currentCoroutineContext()[Job])) { tool.run(env, args) }.cap(),
                false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            ToolOutcome(tool.uid, e.message ?: "Unknown error.", true)
        } catch (e: Exception) {
            ToolOutcome(tool.uid, ModelFacingText.toolError(e.message ?: e.javaClass.simpleName), true)
        }
    }
}

/**
 * Đường dẫn có nằm trong thư mục bảo vệ Tov/ không (Đợt D): AI chỉ đọc
 * được, ghi luôn phải hỏi user. So theo đoạn đầu tiên của đường dẫn tương
 * đối trong workspace ("Tov/prompts/x.md" → true, "Tov2/x.md" → false).
 */
fun isProtectedPath(path: String, workspaceRoots: List<String> = emptyList()): Boolean {
    val segments = path.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    val first = segments.firstOrNull()
    val relative = if (first != null && first in workspaceRoots) segments.drop(1) else segments
    return relative.firstOrNull() == "Tov"
}
