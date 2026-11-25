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
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Danh sách toàn bộ công cụ + cầu nối với vòng chat.
 * - UID cố định do app đặt; tên/mô tả GỐC lấy từ code, không bao giờ mất.
 *   Tên model thấy luôn là tên gốc; mô tả model thấy là mô tả gốc, trừ
 *   khi user sửa trong Manage tools (ToolPrefs.description) — thẻ Tools
 *   chỉ chọn tool nào được offer, không còn đổi tên/mô tả qua file tools.md
 *   nữa (RCP, 17/11/2026). Xóa thẻ Tools = 0 tool.
 * - Chế độ Auto/Ask/Off (tầng quyền) vẫn ở trong app (ToolPrefs); tool tắt
 *   vẫn được offer cho model, chỉ chặn lúc gọi (quyết định 8, Đợt E).
 * - Mọi thay đổi trong ToolPrefs có hiệu lực từ request kế tiếp (definitions() đọc lại mỗi lần).
 */
class ToolRegistry(
    private val env: ToolEnv,
    private val prefs: ToolPrefs,
    private val confirmations: ConfirmationBroker,
    /** The loaded sub-agent team (Phase 3): the delegate tool's agent enum is built from it. */
    private val teamProvider: () -> List<com.example.byokchat.chat.SubAgentConfig> = { emptyList() },
    /** Diagnostic log (16/11/2026): the handoff steps, for the Diagnostics screen. Null = no logging. */
    private val diagnosticsLog: com.example.byokchat.chat.DiagnosticsLog? = null,
    /** The agent store (recruit_teammate writes agents.json). Null = no recruiting. */
    private val agentStore: () -> com.example.byokchat.chat.SubAgentStore? = { null },
) : ToolRuntime {
    /** Set by AppContainer after the sub-agent runner is built (it needs this registry for the sub's own tools). */
    var subRunner: com.example.byokchat.chat.SubAgentRunner? = null
    /** Wake Main with a message (peer mode, 19/11/2026): set by AppContainer to the engine's text-wake entry. Null = Main cannot be woken. Read lazily by the wake tool, like [subRunner]. */
    var onMainWakeText: ((String, String) -> Unit)? = null

    val all: List<Tool> = FsTools.build() + TextTools.build() + DataTools.build() + DailyTools.build() + WeatherTools.build() + YouTubeTools.build() + MediaTools.build() + SubAgentTools.build({ subRunner }, teamProvider, diagnosticsLog, agentStore = agentStore, allTools = { all }, requestMainWakeText = { onMainWakeText })
    private val byUid = all.associateBy { it.uid }

    fun tool(uid: String): Tool? = byUid[uid]

    /**
     * Tên model thấy của tool: luôn là tên gốc trong code (Đợt E, quyết
     * định 5) — tên không sửa được, kể cả trong Manage tools.
     */
    fun effectiveName(t: Tool): String = t.defaultName

    /**
     * Mô tả model thấy của tool: mô tả sửa trong Manage tools nếu có
     * (RCP, 17/11/2026), không thì mô tả gốc trong code.
     */
    fun effectiveDescription(t: Tool): String =
        resolveEffectiveDescription(t.defaultDescription, prefs.get(t.uid).description)

    /**
     * Lưu mô tả user sửa trong Manage tools. Text trống hoặc giống hệt mô
     * tả gốc thì xóa override (về mặc định) thay vì lưu trùng.
     */
    fun setDescription(t: Tool, text: String) {
        val trimmed = text.trim()
        prefs.update(t.uid) {
            it.copy(description = if (trimmed.isEmpty() || trimmed == t.defaultDescription) null else trimmed)
        }
    }

    fun mode(t: Tool): ToolMode {
        val m = prefs.get(t.uid).mode?.let { runCatching { ToolMode.valueOf(it) }.getOrNull() } ?: t.defaultMode
        // Công cụ xóa không bao giờ được chạy tự động
        return if (t.risk == Risk.DELETE && m == ToolMode.AUTO) ToolMode.ASK else m
    }

    fun setMode(t: Tool, m: ToolMode) {
        prefs.update(t.uid) { it.copy(mode = if (m == t.defaultMode) null else m.name) }
    }

    /** All per-tool edits (mode + edited description), for backup export. */
    fun toolPrefsSnapshot(): Map<String, ToolPref> = prefs.prefs.value

    /** Mô tả gốc của một tham số trong code (null nếu tham số không có mô tả). */
    fun defaultParamDescription(t: Tool, param: String): String? {
        val props = t.schema["properties"] as? JsonObject ?: return null
        val p = props[param] as? JsonObject ?: return null
        return (p["description"] as? JsonPrimitive)?.contentOrNull
    }

    /**
     * Mô tả tham số mà AI nhìn thấy (Đợt E, quyết định 5): luôn là mô tả
     * gốc trong code. Các override mô tả tham số cũ trong ToolPrefs không
     * còn hiệu lực nữa; tham số cũng không sửa được trong Manage tools.
     */
    fun paramDescription(t: Tool, param: String): String =
        defaultParamDescription(t, param).orEmpty()

    /** Schema thật sự gửi cho AI: luôn là schema gốc trong code — hoặc schema
     *  dựng lại từ đội đã load (tool delegate, Phase 3). */
    fun effectiveSchema(t: Tool): JsonObject = t.schemaProvider?.invoke() ?: t.schema

    fun isAvailable(t: Tool): Boolean =
        (!t.needsWorkspace || env.fs.accessible.value) && t.isAvailable(env)

    /** Có công cụ tệp nào đang bật không (để cảnh báo khi chưa chọn playground folder). */
    fun hasEnabledWorkspaceTools(): Boolean = all.any { it.needsWorkspace && mode(it) != ToolMode.OFF }

    // ---------- ToolRuntime ----------

    /**
     * Danh sách công cụ đưa cho model (RCP, 17/11/2026).
     * - exposed == null: không còn đường nào tạo ra null nữa (thẻ Tools
     *   chỉ chọn trong app; xóa thẻ = 0 tool) — coi như không có tool nào,
     *   giữ lại để tương thích chữ ký.
     * - exposed != null: chỉ các tool trong danh sách đã chọn, đúng thứ tự
     *   đó; tên lấy từ danh sách, mô tả lấy từ mô tả hiệu dụng (override
     *   trong ToolPrefs thắng mô tả trong danh sách), schema tham số lấy từ
     *   tool thật theo uid.
     * - Cả hai đường đều KHÔNG lọc tool mode OFF (quyết định 8): tool tắt
     *   vẫn được offer, chỉ chặn lúc gọi trong execute(). Chỉ lọc tool
     *   không dùng được (chưa chọn playground folder / thiếu API key) vì model có
     *   gọi cũng không chạy được.
     */
    override suspend fun definitions(exposed: List<ParsedTool>?): List<ToolDef> {
        withContext(Dispatchers.IO) { env.fs.refreshIfStale() }
        return if (exposed == null) {
            emptyList()
        } else {
            exposed.mapNotNull { parsed ->
                val tool = byUid[parsed.uid] ?: return@mapNotNull null // uid đã kiểm tra lúc chọn; phòng thủ.
                if (!isAvailable(tool)) null else ToolDef(parsed.name, effectiveDescription(tool), effectiveSchema(tool))
            }
        }
    }

    /**
     * Map tên model gọi -> uid thật. Khi có danh sách đã chọn: CHỈ tra
     * trong đó (tool không được chọn thì không được offer nên cũng không
     * chạy được, kể cả khi tên đó trùng tên gốc của một tool khác). Khi
     * không có danh sách: tra tên gốc.
     */
    override fun uidForName(name: String, exposed: List<ParsedTool>?): String? =
        if (exposed != null) exposed.firstOrNull { it.name == name }?.uid
        else all.firstOrNull { it.defaultName == name }?.uid

    /** Tên model thấy của một uid: tên trong danh sách đã chọn nếu có, không thì tên gốc. */
    override fun nameForUid(uid: String, exposed: List<ParsedTool>?): String? =
        exposed?.firstOrNull { it.uid == uid }?.name ?: byUid[uid]?.defaultName

    /**
     * uid -> mô tả gốc của mọi tool. 17/11/2026 — bỏ hẳn file tools.md
     * ngoài: không còn chỗ nào trong app dùng nữa (chỉ còn
     * ToolsMdParser.collectUids, mà cũng chỉ test dùng). Giữ lại vì là
     * một phần của ToolRuntime, để sau này quyết có xóa hay không.
     */
    override fun originDescriptions(): Map<String, String> =
        all.associate { it.uid to it.defaultDescription }

    override fun allUids(): List<String> = all.map { it.uid }

    /**
     * Tool definitions cho các uid đã cho (RCP, 17/11/2026): theo thứ tự
     * nhóm + registry mặc định (KHÔNG theo thứ tự uid truyền vào — thứ tự
     * user tích không phải thứ tự offer), tên gốc + mô tả hiệu dụng
     * (override trong ToolPrefs nếu có), schema hiệu dụng. Chỉ tool dùng
     * được mới trả về. Main và sub dùng chung (sub cũng thấy mô tả đã sửa).
     */
    override fun definitionsForUids(uids: List<String>): List<Pair<String, ToolDef>> {
        val wanted = uids.toSet()
        return all.filter { it.uid in wanted && isAvailable(it) }
            .map { it.uid to ToolDef(it.defaultName, effectiveDescription(it), effectiveSchema(it)) }
    }

    override fun estimateToolTokens(): Int =
        all.filter { isAvailable(it) }
            .sumOf { TokenEstimator.text(it.defaultName + effectiveDescription(it) + effectiveSchema(it).toString()) }

    /**
     * Kiểm tra giới hạn ghi của AI (Đợt E, quyết định 6 + quyết định 14). Trả về câu báo lỗi
     * cho model nếu vi phạm, null nếu được phép.
     * - Mọi đường dẫn ghi phải nằm dưới allowedWriteSubfolder (nếu có) — áp
     *   dụng BÊN TRONG từng folder playground (đoạn đầu là tên folder).
     * - Tool ghi nội dung văn bản (txt.* WRITE) thì đích phải là file
     *   dạng text — xét theo NỘI DUNG, không theo extension (quyết định
     *   14): file text sửa được bất kể đuôi nào, file nhị phân thì không.
     *   note.save/update không có đường dẫn trong args (app tự ghi
     *   notes/<id>.md trong thư mục Storage) nên mặc nhiên đúng.
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
        fun underSubfolder(path: String): Boolean = underWriteSubfolder(path, sub)
        /**
         * Đích ghi có phải file dạng text không (quyết định 14): xét theo
         * nội dung (không có byte NUL trong 2000 byte đầu — xem
         * WorkspaceFs.isTextFile), không theo extension.
         * - File chưa có → file mới, nội dung ghi là text nên cho phép mọi đuôi.
         * - Không đọc được đích (chưa chọn playground folder / đường dẫn sai) →
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

    override fun endTurn(turnId: String) = confirmations.endTurn(turnId)

    override fun endRun(runKey: String) = confirmations.endRun(runKey)

    override suspend fun execute(call: AiToolCall, turnId: String, exposed: List<ParsedTool>?, conversationId: String?): ToolOutcome {
        // Mỗi lần chạy công cụ bắt đầu với bộ nhớ đệm metadata sạch (xem WorkspaceFs).
        env.fs.clearMeta()
        // Tôn trọng danh sách đã chọn (RCP): tên model gọi phải nằm trong
        // danh sách (hoặc là tên gốc khi không có danh sách); tool không
        // được offer thì không chạy.
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

        // Hỏi user trước khi chạy tool ở mode ASK (quyết định Ask): có hỏi
        // hay không chỉ phụ thuộc mode của tool.
        if (mode(tool) == ToolMode.ASK) {
            val detail = try {
                // Luồng công việc gắn kèm Job hiện tại: khi bấm Dừng, các vòng lặp của công cụ sẽ thoát sớm.
                withContext(Dispatchers.IO + WorkspaceFs.cancelJob.asContextElement(currentCoroutineContext()[Job])) { tool.preview(env, args) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ModelFacingText.previewFailed(e.message) + "\n" + args.toString().take(500)
            }
            // Who is asking (25/11/2026): a sub's id comes from CallerScope
            // (the runner sets it around each sub tool call); Main has none.
            // The round is the sub's run, or Main's turn when Main asks.
            val callerId = currentCallerAgentId()
            val agentName = callerId?.let { id ->
                teamProvider().firstOrNull { it.id == id }?.name ?: id
            } ?: "Main"
            val roundId = currentRunKey() ?: "main:$turnId"
            val allowed = confirmations.ask(
                ConfirmRequest(displayName, tool.uid, tool.risk, detail, turnId, agentName = agentName, roundId = roundId),
            )
            if (!allowed) return ToolOutcome(tool.uid, ModelFacingText.DENIED_ACTION, true)
        }

        return try {
            // ConversationScope: cuộc trò chuyện của lần chạy này, để tool
            // ghi những thứ theo từng cuộc trò chuyện (như "Recently played").
            val runContext =
                Dispatchers.IO + WorkspaceFs.cancelJob.asContextElement(currentCoroutineContext()[Job]) +
                    ToolProgressScope(call.id) +
                    if (conversationId != null) ConversationScope(conversationId, turnId) else EmptyCoroutineContext
            val outcome = ToolOutcome(
                tool.uid,
                withContext(runContext) { tool.run(env, args) }.cap(),
                false,
            )
            // Handoff step (16/11/2026): the delegate tool returned — the
            // next step is the engine recording the result in the chat
            // history. Only the delegate is traced: it is the one whose
            // result the next request depends on.
            if (tool.uid == "sub.delegate") {
                diagnosticsLog?.handoff(
                    "delegate.execute.return", conversationId ?: "", turnId,
                    "tool=${call.name} error=${outcome.isError}",
                )
            }
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            ToolOutcome(tool.uid, e.message ?: "Unknown error.", true)
        } catch (e: Exception) {
            ToolOutcome(tool.uid, ModelFacingText.toolError(e.message ?: e.javaClass.simpleName), true)
        } finally {
            // The call ended: its progress must not linger on the card.
            env.progress.clear(call.id)
        }
    }
}
