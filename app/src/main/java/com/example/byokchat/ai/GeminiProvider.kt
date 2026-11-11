package com.example.byokchat.ai

import com.example.byokchat.data.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class GeminiProvider(private val cfg: ProviderConfig, private val log: RequestLog? = null) : AiProvider {
    private val base: String get() = cfg.effectiveBaseUrl().trimEnd('/')

    private fun Request.Builder.withAuth(): Request.Builder = header("x-goog-api-key", cfg.apiKey)

    private val blockedReasons = setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        val model = request.model.removePrefix("models/")
        val body = buildBody(request)
        val logId = log?.beginRequest(cfg.name, request.model, body) ?: -1
        val raw = StringBuilder()
        var usage: Usage? = null
        var httpStatus: Int? = null
        val req = Request.Builder()
            .url("$base/v1beta/models/$model:streamGenerateContent?alt=sse")
            .withAuth()
            .post(body.toString().toRequestBody(Http.JSON))
            .build()

        val calls = mutableListOf<AiToolCall>()
        try {
            Http.client.sseFlow(req) { httpStatus = it }.collect { ev ->
                raw.append(ev.data).append('\n')
                if (raw.length > 1_000_000) raw.setLength(1_000_000)
                val obj = runCatching { Json.parseToJsonElement(ev.data).jsonObject }.getOrNull() ?: return@collect
                // Số token thật nằm ở usageMetadata trong mỗi chunk (số cộng dồn, lần cuối là số đúng).
                obj["usageMetadata"]?.jsonObject?.let { usage = Usage.merge(usage, Usage.fromGemini(it)) }
                obj["error"]?.let { throw AiException(errorText(it)) }
                obj["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
                    ?.let { throw AiException("Request blocked: $it") }
                val cand = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@collect
                cand["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { p ->
                    val po = p.jsonObject
                    if (po["thought"]?.jsonPrimitive?.booleanOrNull == true) return@forEach
                    po["text"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) emit(AiEvent.Text(it)) }
                    po["functionCall"]?.jsonObject?.let { fc ->
                        calls += AiToolCall(
                            id = "call_" + UUID.randomUUID().toString().take(12),
                            name = fc["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            argsJson = (fc["args"] ?: JsonObject(emptyMap())).toString(),
                            signature = po["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                }
                val finish = cand["finishReason"]?.jsonPrimitive?.contentOrNull
                if (finish != null && finish in blockedReasons) throw AiException("The response was blocked by Gemini's safety filter ($finish).")
            }
        } catch (e: Exception) {
            val err = if (e is CancellationException) "cancelled" else e.message
            log?.finishRequest(logId, httpStatus = httpStatus, responseText = raw.toString(), usage = usage, error = err)
            throw e
        }
        log?.finishRequest(logId, httpStatus = httpStatus, responseText = raw.toString(), usage = usage, error = null)
        val valid = calls.filter { it.name.isNotEmpty() }
        if (valid.isNotEmpty()) emit(AiEvent.ToolCalls(valid))
    }.flowOn(Dispatchers.IO)

    /**
     * Body gửi đi, tách riêng khỏi stream() để test được (Đợt 6). Thẻ Metadata:
     * - generationConfig.maxOutputTokens như cũ.
     * - thinkingConfig.thinkingLevel khi effort != NONE (bắt buộc với
     *   Gemini 4; NONE = không gửi, server dùng mặc định của nó — thinking
     *   trên Gemini 4 không tắt được). KHÔNG gửi thinkingBudget (chỉ Gemini
     *   2.5, không được gộp với thinkingLevel).
     * - stopSequences khi có; structured output = responseMimeType
     *   application/json (không cần schema).
     * - KHÔNG gửi temperature/topK/topP/penalty/candidateCount (model mới
     *   nhất không còn hỗ trợ — bỏ qua hoặc 400) và KHÔNG gửi service tier
     *   (serviceTier của Gemini chỉ là output, không phải trường gửi đi).
     */
    internal fun buildBody(request: AiRequest): JsonObject = buildJsonObject {
        if (request.system.isNotBlank()) {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", request.system) }) })
            })
        }
        put("contents", buildContents(request.messages))
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        request.tools.forEach { t ->
                            add(buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                val s = sanitize(t.schema) as JsonObject
                                // Gemini từ chối "properties" rỗng: công cụ không có tham số thì bỏ hẳn
                                if ((s["properties"] as? JsonObject)?.isNotEmpty() == true) put("parameters", s)
                            })
                        }
                    })
                })
            })
        }
        put("generationConfig", buildJsonObject {
            put("maxOutputTokens", request.maxOutputTokens)
            request.metadata.geminiThinkingLevel()?.let { level ->
                put("thinkingConfig", buildJsonObject { put("thinkingLevel", level) })
            }
            val stops = request.metadata.stopSequences.filter { it.isNotBlank() }
            if (stops.isNotEmpty()) put("stopSequences", JsonArray(stops.map { JsonPrimitive(it) }))
            if (request.metadata.structuredOutput) put("responseMimeType", "application/json")
        })
    }

    private fun sanitize(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(
            el.filterKeys { it != "additionalProperties" && it != "\$schema" }.mapValues { sanitize(it.value) }
        )
        is JsonArray -> JsonArray(el.map { sanitize(it) })
        else -> el
    }

    private fun parseObject(s: String): JsonElement =
        runCatching { Json.parseToJsonElement(s) }.getOrNull()?.takeIf { it is JsonObject } ?: JsonObject(emptyMap())

    private fun buildContents(msgs: List<AiMessage>): JsonArray {
        class Content(val role: String, val parts: MutableList<JsonElement>)

        val out = mutableListOf<Content>()
        fun add(role: String, part: JsonElement) {
            if (out.lastOrNull()?.role == role) out.last().parts.add(part) else out.add(Content(role, mutableListOf(part)))
        }
        for (m in msgs) {
            when (m.role) {
                Role.USER -> {
                    m.imagePaths.mapNotNull { imageBase64(it) }.forEach { b64 ->
                        add("user", buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", "image/jpeg")
                                put("data", b64)
                            })
                        })
                    }
                    if (m.text.isNotBlank()) add("user", buildJsonObject { put("text", m.text) })
                }
                Role.ASSISTANT -> {
                    if (m.text.isNotBlank()) add("model", buildJsonObject { put("text", m.text) })
                    m.toolCalls.forEach { tc ->
                        add("model", buildJsonObject {
                            put("functionCall", buildJsonObject {
                                put("name", tc.name)
                                put("args", parseObject(tc.argsJson))
                            })
                            tc.signature?.let { put("thoughtSignature", it) }
                        })
                    }
                }
                Role.TOOL -> add("user", buildJsonObject {
                    put("functionResponse", buildJsonObject {
                        put("name", m.toolName ?: "tool")
                        put("response", buildJsonObject { put(if (m.isError) "error" else "result", m.text) })
                    })
                })
            }
        }
        return buildJsonArray {
            out.forEach { c ->
                add(buildJsonObject {
                    put("role", c.role)
                    put("parts", JsonArray(c.parts))
                })
            }
        }
    }

    override suspend fun listModels(): List<String> {
        val req = Request.Builder().url("$base/v1beta/models?pageSize=200").withAuth().get().build()
        val root = Http.client.getJson(req).jsonObject
        return root["models"]?.jsonArray?.mapNotNull { m ->
            val o = m.jsonObject
            val methods = o["supportedGenerationMethods"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            if ("generateContent" in methods) o["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") else null
        } ?: emptyList()
    }
}
