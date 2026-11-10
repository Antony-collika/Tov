package com.example.byokchat.tools

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.ToolDef
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
            .map { ToolDef(effectiveName(it), effectiveDescription(it), it.schema) }
    }

    override fun uidForName(name: String): String? = all.firstOrNull { effectiveName(it) == name }?.uid

    override fun nameForUid(uid: String): String? = byUid[uid]?.let { effectiveName(it) }

    override fun estimateToolTokens(): Int =
        all.filter { mode(it) != ToolMode.OFF && isAvailable(it) }
            .sumOf { TokenEstimator.text(effectiveName(it) + effectiveDescription(it) + it.schema.toString()) }

    override fun endTurn(turnId: String) = confirmations.endTurn(turnId)

    override suspend fun execute(call: AiToolCall, turnId: String): ToolOutcome {
        // Mỗi lần chạy công cụ bắt đầu với bộ nhớ đệm metadata sạch (xem WorkspaceFs).
        env.fs.clearMeta()
        val tool = all.firstOrNull { effectiveName(it) == call.name && mode(it) != ToolMode.OFF && isAvailable(it) }
            ?: return ToolOutcome("", "There is no tool named '${call.name}', or it is turned off.", true)
        val displayName = effectiveName(tool)
        val args: JsonObject = try {
            if (call.argsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(call.argsJson) as? JsonObject
                ?: return ToolOutcome(tool.uid, "The arguments must be a JSON object.", true)
        } catch (e: Exception) {
            return ToolOutcome(tool.uid, "The arguments are not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}", true)
        }

        if (mode(tool) == ToolMode.ASK) {
            val detail = try {
                // Luồng công việc gắn kèm Job hiện tại: khi bấm Dừng, các vòng lặp của công cụ sẽ thoát sớm.
                withContext(Dispatchers.IO + WorkspaceFs.cancelJob.asContextElement(currentCoroutineContext()[Job])) { tool.preview(env, args) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "(Could not build a preview: ${e.message})\n" + args.toString().take(500)
            }
            val allowed = confirmations.ask(ConfirmRequest(displayName, tool.uid, tool.risk, detail, turnId))
            if (!allowed) return ToolOutcome(tool.uid, "The user denied this action. Do not try again unless they ask you to.", true)
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
            ToolOutcome(tool.uid, "Error while running the tool: ${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    companion object {
        private val NAME_RX = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    }
}
