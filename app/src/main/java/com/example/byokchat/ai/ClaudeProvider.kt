package com.example.byokchat.ai

import com.example.byokchat.data.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ClaudeProvider(private val cfg: ProviderConfig) : AiProvider {
    private val base: String get() = cfg.effectiveBaseUrl().trimEnd('/')

    private fun Request.Builder.withAuth(): Request.Builder =
        header("x-api-key", cfg.apiKey).header("anthropic-version", "2023-06-01")

    private class Partial(val id: String, val name: String) {
        val args = StringBuilder()
    }

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        val body = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxOutputTokens)
            put("stream", true)
            if (request.system.isNotBlank()) put("system", request.system)
            put("messages", buildMessages(request.messages))
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    request.tools.forEach { t ->
                        add(buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("input_schema", t.schema)
                        })
                    }
                })
            }
        }
        val req = Request.Builder()
            .url("$base/v1/messages")
            .withAuth()
            .post(body.toString().toRequestBody(Http.JSON))
            .build()

        val partial = sortedMapOf<Int, Partial>()
        Http.client.sseFlow(req).collect { ev ->
            val obj = runCatching { Json.parseToJsonElement(ev.data).jsonObject }.getOrNull() ?: return@collect
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_start" -> {
                    val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: return@collect
                    val cb = obj["content_block"]?.jsonObject ?: return@collect
                    if (cb["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                        partial[idx] = Partial(
                            cb["id"]?.jsonPrimitive?.contentOrNull ?: "",
                            cb["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        )
                    }
                }
                "content_block_delta" -> {
                    val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: return@collect
                    val d = obj["delta"]?.jsonObject ?: return@collect
                    when (d["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> d["text"]?.jsonPrimitive?.contentOrNull
                            ?.let { if (it.isNotEmpty()) emit(AiEvent.Text(it)) }
                        "input_json_delta" -> d["partial_json"]?.jsonPrimitive?.contentOrNull
                            ?.let { partial[idx]?.args?.append(it) }
                    }
                }
                "error" -> throw AiException(errorText(obj["error"] ?: obj))
            }
        }
        val calls = partial.values.filter { it.name.isNotEmpty() }.map {
            AiToolCall(it.id, it.name, it.args.toString().ifBlank { "{}" })
        }
        if (calls.isNotEmpty()) emit(AiEvent.ToolCalls(calls))
    }

    private fun parseObject(s: String): JsonElement =
        runCatching { Json.parseToJsonElement(s) }.getOrNull()?.takeIf { it is JsonObject } ?: JsonObject(emptyMap())

    private fun buildMessages(msgs: List<AiMessage>): JsonArray {
        class Turn(val role: String, val blocks: MutableList<JsonElement>)

        val out = mutableListOf<Turn>()
        fun add(role: String, block: JsonElement) {
            if (out.lastOrNull()?.role == role) out.last().blocks.add(block) else out.add(Turn(role, mutableListOf(block)))
        }
        for (m in msgs) {
            when (m.role) {
                Role.USER -> {
                    m.imagePaths.mapNotNull { imageBase64(it) }.forEach { b64 ->
                        add("user", buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", b64)
                            })
                        })
                    }
                    if (m.text.isNotBlank()) add("user", buildJsonObject { put("type", "text"); put("text", m.text) })
                }
                Role.ASSISTANT -> {
                    if (m.text.isNotBlank()) add("assistant", buildJsonObject { put("type", "text"); put("text", m.text) })
                    m.toolCalls.forEach { tc ->
                        add("assistant", buildJsonObject {
                            put("type", "tool_use")
                            put("id", tc.id)
                            put("name", tc.name)
                            put("input", parseObject(tc.argsJson))
                        })
                    }
                }
                Role.TOOL -> add("user", buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", m.toolCallId ?: "")
                    put("content", m.text.ifEmpty { "(no result)" })
                    if (m.isError) put("is_error", true)
                })
            }
        }
        return buildJsonArray {
            out.forEach { t ->
                add(buildJsonObject {
                    put("role", t.role)
                    put("content", JsonArray(t.blocks))
                })
            }
        }
    }

    override suspend fun listModels(): List<String> {
        val req = Request.Builder().url("$base/v1/models?limit=100").withAuth().get().build()
        val root = Http.client.getJson(req).jsonObject
        return root["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull } ?: emptyList()
    }
}
