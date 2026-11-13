package com.example.byokchat.tools

import android.content.Context
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.SettingsRepository
import kotlinx.serialization.json.*

enum class Risk { READ, WRITE, DELETE }

enum class ToolMode(val label: String) {
    AUTO("Auto"), ASK("Ask first"), OFF("Off")
}

enum class ToolGroup(val label: String) {
    FILES("Files & folders"), TEXT("Text files"), DAILY("Daily tools"), MEDIA("Media")
}

class ToolException(message: String) : Exception(message)

class ToolEnv(
    val app: Context,
    val fs: WorkspaceFs,
    val db: AppDatabase,
    val settings: SettingsRepository,
    val permissions: PermissionBroker,
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
