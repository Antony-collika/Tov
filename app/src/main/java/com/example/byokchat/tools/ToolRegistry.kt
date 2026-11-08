package com.example.byokchat.tools

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.ToolDef
import com.example.byokchat.chat.TokenEstimator
import com.example.byokchat.chat.ToolOutcome
import com.example.byokchat.chat.ToolRuntime
import com.example.byokchat.util.cap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        if (n.isEmpty()) return "Tên không được để trống."
        if (!NAME_RX.matches(n)) {
            return "Tên chỉ gồm chữ cái không dấu, số, dấu gạch dưới (_) hoặc gạch ngang (-); phải bắt đầu bằng chữ cái hoặc _; tối đa 64 ký tự (đây là yêu cầu của các nhà cung cấp AI)."
        }
        val clash = all.firstOrNull { it.uid != t.uid && effectiveName(it).equals(n, ignoreCase = true) }
        if (clash != null) return "Tên này đang được công cụ \"${clash.uid}\" dùng. Hãy chọn tên khác."
        return null
    }

    fun validateDescription(d: String): String? = when {
        d.isBlank() -> "Mô tả không được để trống (model cần mô tả để biết khi nào dùng công cụ)."
        d.length > 1500 -> "Mô tả quá dài (tối đa 1500 ký tự)."
        else -> null
    }

    fun isAvailable(t: Tool): Boolean =
        (!t.needsWorkspace || env.fs.accessible.value) && t.isAvailable(env)

    /** Có công cụ tệp nào đang bật không (để cảnh báo khi chưa chọn workspace). */
    fun hasEnabledWorkspaceTools(): Boolean = all.any { it.needsWorkspace && mode(it) != ToolMode.OFF }

    // ---------- ToolRuntime ----------

    override suspend fun definitions(): List<ToolDef> {
        withContext(Dispatchers.IO) { env.fs.refresh() }
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
        val tool = all.firstOrNull { effectiveName(it) == call.name && mode(it) != ToolMode.OFF && isAvailable(it) }
            ?: return ToolOutcome("", "Không có công cụ tên '${call.name}' hoặc công cụ này đang tắt.", true)
        val displayName = effectiveName(tool)
        val args: JsonObject = try {
            if (call.argsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(call.argsJson) as? JsonObject
                ?: return ToolOutcome(tool.uid, "Tham số phải là một đối tượng JSON.", true)
        } catch (e: Exception) {
            return ToolOutcome(tool.uid, "Tham số không phải JSON hợp lệ: ${e.message?.lineSequence()?.firstOrNull()}", true)
        }

        if (mode(tool) == ToolMode.ASK) {
            val detail = try {
                withContext(Dispatchers.IO) { tool.preview(env, args) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "(Không tạo được bản xem trước: ${e.message})\n" + args.toString().take(500)
            }
            val allowed = confirmations.ask(ConfirmRequest(displayName, tool.uid, tool.risk, detail, turnId))
            if (!allowed) return ToolOutcome(tool.uid, "Người dùng đã từ chối thao tác này. Đừng thử lại trừ khi họ yêu cầu.", true)
        }

        return try {
            ToolOutcome(tool.uid, withContext(Dispatchers.IO) { tool.run(env, args) }.cap(), false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolException) {
            ToolOutcome(tool.uid, e.message ?: "Lỗi không rõ.", true)
        } catch (e: Exception) {
            ToolOutcome(tool.uid, "Lỗi khi chạy công cụ: ${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    companion object {
        private val NAME_RX = Regex("^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    }
}
