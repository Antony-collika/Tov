package com.example.byokchat.ai

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/** Dùng cho OpenAI và mọi dịch vụ tương thích OpenAI (mục Tùy chỉnh). */
class OpenAiProvider(
    private val cfg: ProviderConfig,
    private val log: RequestLog? = null,
) : AiProvider {
    private val base: String get() = cfg.effectiveBaseUrl().trimEnd('/')

    private fun Request.Builder.withAuth(): Request.Builder =
        if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") else this

    private class Partial {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        val body = buildBody(request)
        val logId = log?.beginRequest(cfg.name, request.model, body, sessionId = request.sessionId, turnNo = request.turnNo) ?: -1
        val raw = StringBuilder()
        var usage: Usage? = null
        var httpStatus: Int? = null
        val req = Request.Builder()
            .url("$base/chat/completions")
            .withAuth()
            .post(body.toString().toRequestBody(Http.JSON))
            .build()

        val partial = sortedMapOf<Int, Partial>()
        try {
            Http.client.sseFlow(req) { httpStatus = it }.collect { ev ->
                if (ev.data == "[DONE]") return@collect
                raw.append(ev.data).append('\n')
                if (raw.length > 1_000_000) raw.setLength(1_000_000)
                val obj = runCatching { Json.parseToJsonElement(ev.data).jsonObject }.getOrNull() ?: return@collect
                obj["error"]?.let { throw AiException(errorText(it)) }
                // Số token thật nằm ở chunk cuối (khi stream_options.include_usage bật).
                obj["usage"]?.jsonObject?.let { usage = Usage.merge(usage, Usage.fromOpenAi(it)) }
                val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                    ?: return@collect
                delta["content"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) emit(AiEvent.Text(it)) }
                delta["tool_calls"]?.jsonArray?.forEach { tc ->
                    val t = tc.jsonObject
                    val idx = t["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val p = partial.getOrPut(idx) { Partial() }
                    t["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) p.id = it }
                    val fn = t["function"]?.jsonObject
                    fn?.get("name")?.jsonPrimitive?.contentOrNull?.let { if (p.name.isEmpty()) p.name = it }
                    fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { p.args.append(it) }
                }
            }
        } catch (e: Exception) {
            val err = if (e is CancellationException) "cancelled" else e.message
            log?.finishRequest(logId, httpStatus = httpStatus, responseText = raw.toString(), usage = usage, error = err)
            throw e
        }
        log?.finishRequest(logId, httpStatus = httpStatus, responseText = raw.toString(), usage = usage, error = null)
        val calls = partial.values.filter { it.name.isNotEmpty() }.map {
            AiToolCall(
                id = it.id.ifEmpty { "call_" + UUID.randomUUID().toString().take(12) },
                name = it.name,
                argsJson = it.args.toString().ifBlank { "{}" },
            )
        }
        if (calls.isNotEmpty()) emit(AiEvent.ToolCalls(calls))
    }.flowOn(Dispatchers.IO)

    /**
     * Body gửi đi, tách riêng khỏi stream() để test được (Đợt 6). Thẻ Metadata:
     * - max_completion_tokens (KHÔNG dùng max_tokens đã deprecated).
     * - reasoning_effort LUÔN "none" (đợt fix 11/11/2026, D3): Chat
     *   Completions chỉ cho gọi tool khi reasoning_effort "none" — server
     *   mặc định medium nên bỏ qua cũng là medium; tool luôn được ưu tiên
     *   hơn thinking, app tự ép về none (xem MetadataParams.effectiveEffort).
     * - Tools: LUÔN gửi — không còn trường hợp nào tool bị tắt vì reasoning.
     *   tool_choice "auto" khi có tool.
     * - stop tối đa 4 chuỗi.
     * - KHÔNG BAO GIỜ gửi temperature/top_p/top_k/logprobs (bỏ khi reasoning
     *   bật; Tov cũng chưa từng có chúng). Không gửi structured output hay
     *   service tier nữa (đã bỏ khỏi Metadata — D4/D6).
     */
    internal fun buildBody(request: AiRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_completion_tokens", request.maxOutputTokens)
        put("reasoning_effort", request.metadata.effortValueFor(ProviderType.OPENAI) ?: "none")
        put("stream", true)
        // Số token thật: xin thêm stream_options.include_usage theo từng
        // provider (cfg.includeUsage, Đợt 7); tắt đi nếu máy chủ tự dựng
        // từ chối trường này.
        if (cfg.includeUsage) put("stream_options", buildJsonObject { put("include_usage", true) })
        val stops = request.metadata.stopSequences.filter { it.isNotBlank() }.take(4)
        if (stops.isNotEmpty()) put("stop", JsonArray(stops.map { JsonPrimitive(it) }))
        put("messages", buildMessages(request))
        val tools = request.tools
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.schema)
                        })
                    })
                }
            })
            put("tool_choice", "auto")
        }
    }

    private fun buildMessages(r: AiRequest): JsonArray = buildJsonArray {
        if (r.system.isNotBlank()) {
            add(buildJsonObject { put("role", "system"); put("content", r.system) })
        }
        for (m in r.messages) {
            when (m.role) {
                Role.USER -> add(buildJsonObject {
                    put("role", "user")
                    val images = m.imagePaths.mapNotNull { imageBase64(it) }
                    if (images.isEmpty()) {
                        put("content", m.text)
                    } else {
                        put("content", buildJsonArray {
                            if (m.text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", m.text) })
                            images.forEach { b64 ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$b64") })
                                })
                            }
                        })
                    }
                })
                Role.ASSISTANT -> add(buildJsonObject {
                    put("role", "assistant")
                    if (m.text.isEmpty() && m.toolCalls.isNotEmpty()) put("content", JsonNull) else put("content", m.text)
                    if (m.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            m.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.name)
                                        put("arguments", tc.argsJson)
                                    })
                                })
                            }
                        })
                    }
                })
                Role.TOOL -> add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", m.toolCallId ?: "")
                    put("content", m.text)
                })
            }
        }
    }

    override suspend fun listModels(): List<String> {
        val req = Request.Builder().url("$base/models").withAuth().get().build()
        val root = Http.client.getJson(req).jsonObject
        return root["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            ?.sorted() ?: emptyList()
    }
}
