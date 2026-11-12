package com.example.byokchat.ai

import com.example.byokchat.chat.ModelFacingText
import com.example.byokchat.data.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ClaudeProvider(private val cfg: ProviderConfig, private val log: RequestLog? = null) : AiProvider {
    private val base: String get() = cfg.effectiveBaseUrl().trimEnd('/')

    private companion object {
        val CACHE_MARK: JsonElement = buildJsonObject { put("type", "ephemeral") }
    }

    /**
     * Chế độ thinking theo model (Đợt provider 12/11/2026 — theo bảng cấu hình
     * từng model trong tài liệu Anthropic, đọc 12/11/2026):
     * - MANUAL: model 4.5 trở về trước — `thinking: {type: "enabled",
     *   budget_tokens}`; gửi `type: "adaptive"` là lỗi 400.
     * - ADAPTIVE: model 4.6/4.7/4.8 — `thinking: {type: "adaptive"}` (+
     *   output_config.effort quyết độ sâu); manual đã deprecated (4.6) hoặc
     *   lỗi 400 (4.7/4.8). Thinking tắt theo mặc định.
     * - ADAPTIVE_ALWAYS_ON: model 5.x (+ Mythos Preview) — thinking bật theo
     *   mặc định của hãng, manual là lỗi 400; tắt cũng bị từ chối ở hầu hết
     *   model này nên công tắc tắt chỉ có nghĩa là "app không gửi thêm cấu
     *   hình" (xem ghi chú ở thẻ Metadata).
     * Tên model do user gõ tay nên chỉ đoán theo tên (cùng cách app đoán
     * giới hạn ngữ cảnh theo tên model): tên không nhận ra thì coi là model
     * hiện tại (adaptive) — mọi model mới đều chỉ còn adaptive; nếu đoán sai
     * thì API báo 400 rõ ràng chứ app không tự đổi lặng lẽ.
     */
    internal enum class ThinkingMode { MANUAL, ADAPTIVE, ADAPTIVE_ALWAYS_ON }

    internal fun thinkingModeFor(model: String): ThinkingMode {
        val m = model.lowercase()
        if (Regex("""claude[-_ ]?(opus|sonnet|haiku|fable|mythos)[-_ ]?5([.-]|$)""").containsMatchIn(m)) {
            return ThinkingMode.ADAPTIVE_ALWAYS_ON
        }
        if (Regex("""claude[-_ ]?5([.-]|$)""").containsMatchIn(m)) return ThinkingMode.ADAPTIVE_ALWAYS_ON
        if ("mythos-preview" in m) return ThinkingMode.ADAPTIVE_ALWAYS_ON
        if (Regex("""4[-_. ]?[678]""").containsMatchIn(m)) return ThinkingMode.ADAPTIVE
        if (Regex("""4[-_. ]?5""").containsMatchIn(m)) return ThinkingMode.MANUAL
        if (Regex("""claude[-_ ]?3""").containsMatchIn(m)) return ThinkingMode.MANUAL
        if (Regex("""4[-_. ]?[0-4]""").containsMatchIn(m)) return ThinkingMode.MANUAL
        return ThinkingMode.ADAPTIVE
    }

    private fun Request.Builder.withAuth(): Request.Builder =
        header("x-api-key", cfg.apiKey).header("anthropic-version", "2023-06-01")

    private class Partial(val id: String, val name: String) {
        val args = StringBuilder()
    }

    /**
     * Prompt caching của Claude: chỉ bật với địa chỉ mặc định (hoặc có chứa anthropic.com), vì
     * máy chủ khác có thể không hiểu trường này. Đánh dấu 3 chỗ: system, công cụ cuối, khối cuối của tin cuối.
     */
    private val caching: Boolean
        get() = cfg.baseUrl.isBlank() || cfg.baseUrl.contains("anthropic.com")

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        val useCache = caching
        val body = buildBody(request, useCache)
        val logId = log?.beginRequest(cfg.name, request.model, body, sessionId = request.sessionId, turnNo = request.turnNo) ?: -1
        val raw = StringBuilder()
        var usage: Usage? = null
        var httpStatus: Int? = null
        val req = Request.Builder()
            .url("$base/v1/messages")
            .withAuth()
            .post(body.toString().toRequestBody(Http.JSON))
            .build()

        val partial = sortedMapOf<Int, Partial>()
        // Khối thinking đang stream (Đợt provider 12/11/2026): mỗi khối theo
        // dõi riêng theo index; signature đến cuối khối (signature_delta ngay
        // trước content_block_stop). Khối redacted chỉ có data, không có chữ.
        class ThinkingPart(var redacted: Boolean = false) {
            val thinking = StringBuilder()
            var signature: String? = null
            var data: String? = null
        }
        val thinkingParts = sortedMapOf<Int, ThinkingPart>()
        val doneThinking = mutableListOf<JsonObject>()
        try {
            Http.client.sseFlow(req) { httpStatus = it }.collect { ev ->
                raw.append(ev.data).append('\n')
                if (raw.length > 1_000_000) raw.setLength(1_000_000)
                val obj = runCatching { Json.parseToJsonElement(ev.data).jsonObject }.getOrNull() ?: return@collect
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    // Số token thật: đầu lượt gửi input (+ cache), cuối lượt gửi output.
                    "message_start" -> obj["message"]?.jsonObject?.get("usage")?.jsonObject
                        ?.let { usage = Usage.merge(usage, Usage.fromClaude(it)) }
                    "message_delta" -> obj["usage"]?.jsonObject
                        ?.let { usage = Usage.merge(usage, Usage.fromClaude(it)) }
                    "content_block_start" -> {
                        val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: return@collect
                        val cb = obj["content_block"]?.jsonObject ?: return@collect
                        when (cb["type"]?.jsonPrimitive?.contentOrNull) {
                            "tool_use" -> partial[idx] = Partial(
                                cb["id"]?.jsonPrimitive?.contentOrNull ?: "",
                                cb["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            )
                            "thinking" -> thinkingParts[idx] = ThinkingPart()
                            "redacted_thinking" -> thinkingParts[idx] = ThinkingPart(redacted = true).also {
                                it.data = cb["data"]?.jsonPrimitive?.contentOrNull
                            }
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
                            // Suy luận đọc được: hiện ở khối "Suy luận", không
                            // trộn vào câu trả lời.
                            "thinking_delta" -> d["thinking"]?.jsonPrimitive?.contentOrNull
                                ?.let { delta ->
                                    thinkingParts[idx]?.thinking?.append(delta)
                                    if (delta.isNotEmpty()) emit(AiEvent.Reasoning(delta))
                                }
                            "signature_delta" -> d["signature"]?.jsonPrimitive?.contentOrNull
                                ?.let { thinkingParts[idx]?.signature = it }
                        }
                    }
                    // Một khối thinking xong: ghi lại nguyên vẹn để gửi lại ở
                    // lượt sau (kể cả khối chỉ có signature mà không có chữ —
                    // display omitted vẫn cần signature để giữ mạch suy luận).
                    "content_block_stop" -> {
                        val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: return@collect
                        val part = thinkingParts.remove(idx) ?: return@collect
                        doneThinking += if (part.redacted) {
                            buildJsonObject {
                                put("type", "redacted_thinking")
                                part.data?.let { put("data", it) }
                            }
                        } else {
                            buildJsonObject {
                                put("type", "thinking")
                                put("thinking", part.thinking.toString())
                                part.signature?.let { put("signature", it) }
                            }
                        }
                    }
                    "error" -> throw AiException(errorText(obj["error"] ?: obj))
                }
            }
        } catch (e: Exception) {
            val err = if (e is CancellationException) "cancelled" else e.message
            log?.finishRequest(logId, httpStatus = httpStatus, responseText = raw.toString(), usage = usage, error = err)
            throw e
        }
        log?.finishRequest(logId, httpStatus = httpStatus, responseText = raw.toString(), usage = usage, error = null)
        if (doneThinking.isNotEmpty()) emit(AiEvent.Replay(JsonArray(doneThinking).toString()))
        val calls = partial.values.filter { it.name.isNotEmpty() }.map {
            AiToolCall(it.id, it.name, it.args.toString().ifBlank { "{}" })
        }
        if (calls.isNotEmpty()) emit(AiEvent.ToolCalls(calls))
    }.flowOn(Dispatchers.IO)

    /**
     * Body gửi đi, tách riêng khỏi stream() để test được (Đợt 6). Thẻ Metadata:
     * - max_tokens (bắt buộc với Claude) như cũ.
     * - output_config.effort khi effort != NONE — tín hiệu mềm, max_tokens
     *   vẫn là trần cứng. NONE = không gửi (dùng mặc định của hãng).
     * - Extended thinking (Đợt provider 12/11/2026), chỉ khi công tắc bật:
     *   model 4.5 trở về trước gửi thinking {type: "enabled", budget_tokens}
     *   (budget phải ≥ 1024 và < max_tokens — sai thì báo rõ, không tự sửa);
     *   model 4.6+ gửi thinking {type: "adaptive"} (độ sâu do effort quyết,
     *   manual là lỗi 400 ở 4.7+). Cả hai đều xin display "summarized" để
     *   user đọc được suy luận (mặc định ở model mới là "omitted" — khối
     *   thinking trả về rỗng chữ, chỉ còn signature).
     * - stop_sequences khi có.
     * - Structured output: KHÔNG gửi — output_config.format cần json_schema
     *   KÈM schema, chưa có UI schema nên không gửi bừa. Service tier:
     *   KHÔNG gửi nữa (đã bỏ khỏi Metadata — D6).
     * - KHÔNG BAO GIỜ gửi temperature/top_p/top_k: API version 2026-10-21
     *   gỡ hẳn, gửi kèm là lỗi 400 kể cả giá trị mặc định. Tov cũng chưa
     *   từng gửi chúng.
     */
    internal fun buildBody(request: AiRequest, useCache: Boolean = caching): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", request.maxOutputTokens)
        put("stream", true)
        request.metadata.claudeEffort()?.let { effort ->
            put("output_config", buildJsonObject { put("effort", effort) })
        }
        if (request.metadata.thinkingEnabled) {
            when (thinkingModeFor(request.model)) {
                ThinkingMode.MANUAL -> {
                    val budget = request.metadata.thinkingBudget
                    if (budget < 1024 || budget >= request.maxOutputTokens) {
                        throw AiException(
                            "Thinking budget must be at least 1024 tokens and less than the maximum reply length " +
                                "(${request.maxOutputTokens}). Change it in the Metadata card.",
                        )
                    }
                    put("thinking", buildJsonObject {
                        put("type", "enabled")
                        put("budget_tokens", budget)
                        put("display", "summarized")
                    })
                }
                ThinkingMode.ADAPTIVE, ThinkingMode.ADAPTIVE_ALWAYS_ON -> put("thinking", buildJsonObject {
                    put("type", "adaptive")
                    put("display", "summarized")
                })
            }
        }
        val stops = request.metadata.stopSequences.filter { it.isNotBlank() }
        if (stops.isNotEmpty()) put("stop_sequences", JsonArray(stops.map { JsonPrimitive(it) }))
        if (request.system.isNotBlank()) {
            if (useCache) {
                put("system", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", request.system)
                        put("cache_control", CACHE_MARK)
                    })
                })
            } else {
                put("system", request.system)
            }
        }
        put("messages", buildMessages(request.messages, useCache, request.metadata.thinkingEnabled))
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEachIndexed { i, t ->
                    add(buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", t.schema)
                        if (useCache && i == request.tools.lastIndex) put("cache_control", CACHE_MARK)
                    })
                }
            })
        }
    }

    private fun withCacheMark(el: JsonElement): JsonElement =
        if (el is JsonObject) JsonObject(el + ("cache_control" to CACHE_MARK)) else el

    private fun parseObject(s: String): JsonElement =
        runCatching { Json.parseToJsonElement(s) }.getOrNull()?.takeIf { it is JsonObject } ?: JsonObject(emptyMap())

    private fun buildMessages(msgs: List<AiMessage>, useCache: Boolean, includeThinking: Boolean = false): JsonArray {
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
                    // Đợt provider 12/11/2026: gửi lại khối thinking nguyên
                    // vẹn (thinking + signature), ĐÚNG thứ tự, TRƯỚC khối
                    // text — thiếu/sửa là lỗi 400, và ở model mới chữ ký còn
                    // gắn với system/tools/tin trước đó (đổi một trong các
                    // thứ đó là khối mất hiệu lực). Chỉ gửi khi thinking đang
                    // bật ở lượt này; tắt thì thôi (lịch sử text thường vẫn
                    // được Claude chấp nhận). Khối của hãng khác thì bỏ qua.
                    if (includeThinking && m.reasoningProvider == "anthropic") {
                        m.reasoningReplay?.let { replay ->
                            runCatching { Json.parseToJsonElement(replay).jsonArray }.getOrNull()?.forEach { el ->
                                val o = el as? JsonObject ?: return@forEach
                                when (o["type"]?.jsonPrimitive?.contentOrNull) {
                                    "thinking" -> add("assistant", buildJsonObject {
                                        put("type", "thinking")
                                        put("thinking", o["thinking"]?.jsonPrimitive?.contentOrNull ?: "")
                                        o["signature"]?.jsonPrimitive?.contentOrNull?.let { put("signature", it) }
                                    })
                                    "redacted_thinking" -> add("assistant", buildJsonObject {
                                        put("type", "redacted_thinking")
                                        o["data"]?.jsonPrimitive?.contentOrNull?.let { put("data", it) }
                                    })
                                }
                            }
                        }
                    }
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
                    put("content", m.text.ifEmpty { ModelFacingText.NO_RESULT })
                    if (m.isError) put("is_error", true)
                })
            }
        }
        return buildJsonArray {
            out.forEachIndexed { ti, t ->
                // Khối cuối của tin cuối được đánh dấu để Claude nhớ lại phần hội thoại đã gửi.
                val blocks: List<JsonElement> =
                    if (useCache && ti == out.lastIndex && t.blocks.isNotEmpty()) {
                        t.blocks.dropLast(1) + withCacheMark(t.blocks.last())
                    } else t.blocks
                add(buildJsonObject {
                    put("role", t.role)
                    put("content", JsonArray(blocks))
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
