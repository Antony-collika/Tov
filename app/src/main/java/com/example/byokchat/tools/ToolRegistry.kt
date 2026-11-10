package com.example.byokchat.tools

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.ToolDef
import com.example.byokchat.chat.ModelFacingText
import com.example.byokchat.chat.TokenEstimator
import com.example.byokchat.chat.ToolOutcome
import com.example.byokchat.chat.ToolRuntime
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
 * - UID cố định do app đặt; tên/mô tả model nhìn thấy là phần người dùng có thể đổi.
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

    fun effectiveName(t: Tool): String = prefs.get(t.uid).name?.takeIf { it.isNotBlank() } ?: t.defaultName
    fun effectiveDescription(t: Tool): String = prefs.get(t.uid).description?.takeIf { it.isNotBlank() } ?: t.defaultDescription

    fun mode(t: Tool): ToolMode {
        val m = prefs.get(t.uid).mode?.let { runCatching { ToolMode.valueOf(it) }.getOrNull() } ?: t.defaultMode
        // Công cụ xóa không bao giờ được chạy tự động
        return if (t.risk == Risk.DELETE && m == ToolMode.AUTO) ToolMode.ASK else m
    }

    fun setMode(t: Tool, m: ToolMode) {
        prefs.update(t.uid) { it.copy(mode = if (m == t.defaultMode) null else m.name) }
    }

    fun setNameAndDescription(t: Tool, name: String, description: String) {
        prefs.update(t.uid) {
            it.copy(
                name = name.trim().takeIf { n -> n != t.defaultName },
                description = description.trim().takeIf { d -> d != t.defaultDescription },
            )
        }
    }

    fun restoreDefaults(t: Tool) {
        prefs.update(t.uid) { it.copy(name = null, description = null) }
    }

    /** Mô tả gốc của một tham số trong code (null nếu tham số không có mô tả). */
    fun defaultParamDescription(t: Tool, param: String): String? {
        val props = t.schema["properties"] as? JsonObject ?: return null
        val p = props[param] as? JsonObject ?: return null
        return (p["description"] as? JsonPrimitive)?.contentOrNull
    }

    /** Mô tả tham số mà AI nhìn thấy: bản sửa của user, nếu không thì bản gốc. */
    fun paramDescription(t: Tool, param: String): String =
        prefs.get(t.uid).paramDescriptions[param] ?: defaultParamDescription(t, param).orEmpty()

    /** Schema thật sự gửi cho AI: mô tả tham số đã sửa (nếu có) thay mô tả gốc. */
    fun effectiveSchema(t: Tool): JsonObject {
        val overrides = prefs.get(t.uid).paramDescriptions
        if (overrides.isEmpty()) return t.schema
        val props = t.schema["properties"] as? JsonObject ?: return t.schema
        val newProps = JsonObject(props.entries.associate { (k, v) ->
            val desc = overrides[k]
            if (desc == null || v !is JsonObject) k to v
            else k to JsonObject(v.toMap() + ("description" to JsonPrimitive(desc)))
        })
        return JsonObject(t.schema.toMap() + ("properties" to newProps))
    }

    /** Sửa mô tả một tham số; về lại đúng mô tả gốc thì xóa bản sửa. */
    fun setParamDescription(t: Tool, param: String, desc: String) {
        val d = desc.trim()
        prefs.update(t.uid) { pref ->
            val map = pref.paramDescriptions.toMutableMap()
            if (d.isEmpty() || d == defaultParamDescription(t, param)) map.remove(param) else map[param] = d
            pref.copy(paramDescriptions = map)
        }
    }

    fun restoreParamDescriptions(t: Tool) {
        prefs.update(t.uid) { it.copy(paramDescriptions = emptyMap()) }
    }

    /** Trả về thông báo lỗi nếu tên không hợp lệ, null nếu ổn. */
    fun validateName(t: Tool, name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return "The name must not be empty."
        if (!NAME_RX.matches(n)) {
            return "The name may only contain unaccented letters, digits, underscores (_) or hyphens (-); it must start with a letter or _; at most 64 characters (an AI provider requirement)."
        }
        val clash = all.firstOrNull { it.uid != t.uid && effectiveName(it).equals(n, ignoreCase = true) }
        if (clash != null) return "This name is already used by the tool \"${clash.uid}\". Choose a different name."
        return null
    }

    fun validateDescription(d: String): String? = when {
        d.isBlank() -> "The description must not be empty (the model needs it to know when to use the tool)."
        d.length > 1500 -> "The description is too long (max 1500 characters)."
        else -> null
    }

    fun isAvailable(t: Tool): Boolean =
        (!t.needsWorkspace || env.fs.accessible.value) && t.isAvailable(env)

    /** Có công cụ tệp nào đang bật không (để cảnh báo khi chưa chọn workspace). */
    fun hasEnabledWorkspaceTools(): Boolean = all.any { it.needsWorkspace && mode(it) != ToolMode.OFF }

    // ---------- ToolRuntime ----------

    override suspend fun definitions(): List<ToolDef> {
        withContext(Dispatchers.IO) { env.fs.refreshIfStale() }
        return all.filter { mode(it) != ToolMode.OFF && isAvailable(it) }
            .map { ToolDef(effectiveName(it), effectiveDescription(it), effectiveSchema(it)) }
    }

    override fun uidForName(name: String): String? = all.firstOrNull { effectiveName(it) == name }?.uid

    override fun nameForUid(uid: String): String? = byUid[uid]?.let { effectiveName(it) }

    override fun estimateToolTokens(): Int =
        all.filter { mode(it) != ToolMode.OFF && isAvailable(it) }
            .sumOf { TokenEstimator.text(effectiveName(it) + effectiveDescription(it) + effectiveSchema(it).toString()) }

    override fun endTurn(turnId: String) = confirmations.endTurn(turnId)

    override suspend fun execute(call: AiToolCall, turnId: String): ToolOutcome {
        // Mỗi lần chạy công cụ bắt đầu với bộ nhớ đệm metadata sạch (xem WorkspaceFs).
        env.fs.clearMeta()
        val tool = all.firstOrNull { effectiveName(it) == call.name && mode(it) != ToolMode.OFF && isAvailable(it) }
            ?: return ToolOutcome("", ModelFacingText.noSuchTool(call.name), true)
        val displayName = effectiveName(tool)
        val args: JsonObject = try {
            if (call.argsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(call.argsJson) as? JsonObject
                ?: return ToolOutcome(tool.uid, ModelFacingText.ARGS_NOT_OBJECT, true)
        } catch (e: Exception) {
            return ToolOutcome(tool.uid, ModelFacingText.invalidArgsJson(e.message), true)
        }

        if (mode(tool) == ToolMode.ASK) {
            val detail = try {
                // Luồng công việc gắn kèm Job hiện tại: khi bấm Dừng, các vòng lặp của công cụ sẽ thoát sớm.
                withContext(Dispatchers.IO + WorkspaceFs.cancelJob.asContextElement(currentCoroutineContext()[Job])) { tool.preview(env, args) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ModelFacingText.previewFailed(e.message) + "\n" + args.toString().take(500)
            }
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

    companion object {
        private val NAME_RX = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    }
}
