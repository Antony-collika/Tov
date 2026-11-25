package com.example.byokchat.tools

import android.content.Context
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.SettingsRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext

enum class Risk { READ, WRITE, DELETE }

enum class ToolMode(val label: String) {
    AUTO("Auto"), ASK("Ask first"), OFF("Off")
}

enum class ToolGroup(val label: String) {
    FILES("Files & folders"), TEXT("Text files"), DAILY("Daily tools"), MEDIA("Media"), AGENTS("Agents")
}

class ToolException(message: String) : Exception(message)

/**
 * Coroutine-context element carrying the conversation a running tool call
 * belongs to, plus the turn it belongs to (the delegate tool needs the turn
 * so the sub-agent's messages land in Main's turn). [ToolRegistry.execute]
 * sets it around the tool's run (cùng mẫu với ToolProgressScope); một tool
 * đọc nó để ghi những thứ theo từng cuộc trò chuyện — ví dụ lịch sử
 * "Recently played" — mà không phải đổi chữ ký run của tool. Null ngoài
 * một lần chạy tool.
 */
class ConversationScope(val conversationId: String, val turnId: String? = null) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ConversationScope>

    override val key: CoroutineContext.Key<*> get() = Key
}

/** Cuộc trò chuyện của tool call đang chạy trong coroutine này, null nếu không có. */
suspend fun currentConversationId(): String? = currentCoroutineContext()[ConversationScope]?.conversationId

/** Lượt của tool call đang chạy trong coroutine này, null nếu không có. */
suspend fun currentTurnId(): String? = currentCoroutineContext()[ConversationScope]?.turnId

/**
 * Coroutine-context element carrying the agent making a tool call
 * (peer mode, 19/11/2026): the sub-agent id when a sub calls a tool,
 * absent when Main calls one. The runner sets it around each sub tool
 * call; [ToolRegistry.execute] only adds elements, so it survives into
 * the tool's run — the wake tool reads it to know who woke whom.
 * Same pattern as [ConversationScope].
 */
class CallerScope(val agentId: String) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CallerScope>

    override val key: CoroutineContext.Key<*> get() = Key
}

/** Id of the agent making the tool call running in this coroutine, null when Main is the caller. */
suspend fun currentCallerAgentId(): String? = currentCoroutineContext()[CallerScope]?.agentId

/**
 * Coroutine-context element carrying one run (25/11/2026): the "round"
 * a tool call belongs to. For Main, a run is a turn; for a sub, a run
 * is one delegation (Main calling the same sub twice in one turn is
 * two runs). The runner sets it around each sub tool call, next to
 * [CallerScope]; [ToolRegistry.execute] only adds elements, so it
 * survives into the tool's run. It is the scope of "Allow all in this
 * round": one Allow-all covers only this run, never the next run of
 * the same agent in the same turn.
 */
class RunScope(val runKey: String) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<RunScope>

    override val key: CoroutineContext.Key<*> get() = Key
}

/** Key of the run the tool call running in this coroutine belongs to, null when Main is the caller. */
suspend fun currentRunKey(): String? = currentCoroutineContext()[RunScope]?.runKey

class ToolEnv(
    val app: Context,
    val fs: WorkspaceFs,
    val db: AppDatabase,
    val settings: SettingsRepository,
    val permissions: PermissionBroker,
    /** Live progress of running tool calls, shown in the chat card (see ToolProgress). */
    val progress: ToolProgressBroker = ToolProgressBroker(),
)

class Tool(
    val uid: String,
    val group: ToolGroup,
    val risk: Risk,
    val defaultName: String,
    val defaultDescription: String,
    val schema: JsonObject,
    val needsWorkspace: Boolean = false,
    val unavailableHint: String? = null,
    val isAvailable: (ToolEnv) -> Boolean = { true },
    val previewFn: (suspend (ToolEnv, JsonObject) -> String)? = null,
    /**
     * Rebuilds the schema on each send instead of using [schema] (Phase 3):
     * the delegate tool's agent enum comes from the loaded team, so it must
     * follow team edits without an app restart. Only the schema is dynamic
     * — [defaultDescription] stays fixed, because tools.md checks it
     * (origin_description) and a team edit must never break that file.
     */
    val schemaProvider: (() -> JsonObject)? = null,
    val run: suspend (ToolEnv, JsonObject) -> String,
) {
    val defaultMode: ToolMode get() = if (risk == Risk.READ) ToolMode.AUTO else ToolMode.ASK

    suspend fun preview(env: ToolEnv, args: JsonObject): String =
        previewFn?.invoke(env, args) ?: args.entries.joinToString("\n") { (k, v) ->
            "$k: " + ((v as? JsonPrimitive)?.contentOrNull ?: v.toString()).take(300)
        }.ifBlank { "(no parameters)" }
}

// ---------- Hỗ trợ đọc tham số ----------

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.reqStr(k: String): String = str(k) ?: throw ToolException("Missing parameter '$k'.")
fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.strList(k: String): List<String>? =
    (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

// ---------- Dựng schema tham số ----------

class SchemaBuilder {
    private val props = linkedMapOf<String, JsonElement>()
    private val req = mutableListOf<String>()

    private fun add(name: String, type: String, desc: String, required: Boolean, extra: JsonObjectBuilder.() -> Unit = {}) {
        props[name] = buildJsonObject {
            put("type", type)
            put("description", desc)
            extra()
        }
        if (required) req += name
    }

    fun str(name: String, desc: String, required: Boolean = false, enum: List<String>? = null) =
        add(name, "string", desc, required) {
            if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
        }

    fun int(name: String, desc: String, required: Boolean = false) = add(name, "integer", desc, required)
    fun bool(name: String, desc: String, required: Boolean = false) = add(name, "boolean", desc, required)
    fun strArray(name: String, desc: String, required: Boolean = false) = add(name, "array", desc, required) {
        put("items", buildJsonObject { put("type", "string") })
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (req.isNotEmpty()) put("required", JsonArray(req.map { JsonPrimitive(it) }))
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

/** Văn bản chia dòng, nhớ kiểu xuống dòng để ghi lại đúng như cũ. */
class TextDoc(val lines: MutableList<String>, val eol: String, var trailing: Boolean) {
    fun render(): String = if (lines.isEmpty()) "" else lines.joinToString(eol) + if (trailing) eol else ""

    companion object {
        fun parse(text: String): TextDoc {
            if (text.isEmpty()) return TextDoc(mutableListOf(), "\n", false)
            val eol = if (text.contains("\r\n")) "\r\n" else "\n"
            val trailing = text.endsWith(eol)
            val body = if (trailing) text.removeSuffix(eol) else text
            return TextDoc(body.split(eol).toMutableList(), eol, trailing)
        }
    }
}

fun previewText(s: String, maxLines: Int = 12, maxChars: Int = 800): String {
    val lines = s.lines()
    var out = lines.take(maxLines).joinToString("\n")
    if (lines.size > maxLines) out += "\n… (${lines.size - maxLines} more lines)"
    return if (out.length > maxChars) out.take(maxChars) + "…" else out
}
