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

/**
 * OpenAI chính hãng qua Responses API (Đợt provider 12/11/2026) — TOÀN BỘ
 * traffic OpenAI, không chỉ lúc cần reasoning + tool. OpenAI khuyên dùng
 * Responses cho project mới, và bắt buộc nó cho tools + reasoning ở model
 * mới (từ GPT-5.4; GPT-6 Astra còn không chấp nhận "none" trên Chat
 * Completions). Provider tương thích (máy chủ tự dựng, kể cả DeepSeek qua
 * loại Custom) KHÔNG dùng provider này — họ chỉ có Chat Completions
 * (xem OpenAiProvider).
 *
 * Khác Chat Completions:
 * - Input là danh sách item có type (message / reasoning / function_call /
 *   function_call_output), không phải messages[].
 * - Reasoning là item riêng {type: "reasoning", id, summary, encrypted_content}
 *   — KHÔNG bao giờ lộ token thô; tóm tắt đọc được chỉ có khi xin
 *   reasoning.summary. App gửi lại MỌI output item nguyên vẹn, đúng thứ tự
 *   (replay thủ công, KHÔNG dùng previous_response_id — không nhờ OpenAI
 *   lưu hộ trên server; mọi thứ gửi đi đều hiện trong Preview/log).
 * - store: false (stateless) — reasoning item kèm encrypted_content theo
 *   mặc định, để gửi lại ở lượt sau.
 * - Không có stop sequences (Responses API không có param stop), không có
 *   stream_options.include_usage — usage nằm trong event response.completed.
 * - Không có sự kiện [DONE] — stream kết thúc ở response.completed /
 *   response.incomplete / response.failed.
 *
 * Nguồn kiểm chứng (tài liệu chính thức, đọc 12/11/2026):
 * - OpenAI reasoning guide + migration guide (platform.openai.com):
 *   event response.output_text.delta / response.function_call_arguments.delta /
 *   response.reasoning_summary_text.delta, response.completed mang usage.
 * - OpenAI API reference (create response): usage = {input_tokens,
 *   input_tokens_details.{cached_tokens, cache_write_tokens}, output_tokens,
 *   output_tokens_details.{reasoning_tokens}, total_tokens}.
 */
class ResponsesProvider(
    private val cfg: ProviderConfig,
    private val log: RequestLog? = null,
) : AiProvider {
    private val base: String get() = cfg.effectiveBaseUrl().trimEnd('/')

    private fun Request.Builder.withAuth(): Request.Builder =
        if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") else this

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        // Astra/Sol không chấp nhận effort "none" (tài liệu OpenAI): báo rõ
        // trước khi gửi, không tự đổi sang effort khác lặng lẽ.
        request.metadata.responsesEffortProblem(request.model)?.let { throw AiException(it) }
        val body = buildBody(request)
        val logId = log?.beginRequest(cfg.name, request.model, body, sessionId = request.sessionId, turnNo = request.turnNo) ?: -1
        val raw = StringBuilder()
        var usage: Usage? = null
        var httpStatus: Int? = null
        val req = Request.Builder()
            .url("$base/responses")
            .withAuth()
            .post(body.toString().toRequestBody(Http.JSON))
            .build()

        // Output items đã xong (từ response.output_item.done), dự phòng khi
        // event kết thúc không mang response.output.
        val doneItems = mutableListOf<JsonObject>()
        var terminalItems: List<JsonObject>? = null
        try {
            Http.client.sseFlow(req) { httpStatus = it }.collect { ev ->
                if (ev.data == "[DONE]") return@collect
                raw.append(ev.data).append('\n')
                if (raw.length > 1_000_000) raw.setLength(1_000_000)
                val obj = runCatching { Json.parseToJsonElement(ev.data).jsonObject }.getOrNull() ?: return@collect
                val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: ev.event ?: return@collect
                when (type) {
                    "error" -> throw AiException(errorText(obj["error"] ?: obj))
                    "response.output_text.delta" ->
                        obj["delta"]?.jsonPrimitive?.contentOrNull
                            ?.let { if (it.isNotEmpty()) emit(AiEvent.Text(it)) }
                    "response.reasoning_summary_text.delta" ->
                        obj["delta"]?.jsonPrimitive?.contentOrNull
                            ?.let { if (it.isNotEmpty()) emit(AiEvent.Reasoning(it)) }
                    "response.output_item.done" ->
                        obj["item"]?.jsonObject?.let { doneItems += it }
                    "response.completed", "response.incomplete" -> {
                        val resp = obj["response"]?.jsonObject
                        resp?.get("usage")?.jsonObject?.let { usage = Usage.merge(usage, Usage.fromResponses(it)) }
                        terminalItems = resp?.get("output")?.jsonArray
                            ?.mapNotNull { it as? JsonObject }
                    }
                    "response.failed" -> {
                        val err = obj["response"]?.jsonObject?.get("error") ?: obj["error"]
                        throw AiException(err?.let { errorText(it) } ?: "The response failed.")
                    }
                }
            }
        } catch (e: Exception) {
            val err = if (e is CancellationException) "cancelled" else e.message
            log?.finishRequest(logId, httpStatus = httpStatus, responseText = raw.toString(), usage = usage, error = err)
            throw e
        }
        log?.finishRequest(logId, httpStatus = httpStatus, responseText = raw.toString(), usage = usage, error = null)
        // Gửi lại: toàn bộ output items, đúng thứ tự (ưu tiên bản trong
        // response.completed — đầy đủ và có thẩm quyền nhất).
        val items = terminalItems ?: doneItems
        if (items.isNotEmpty()) {
            emit(AiEvent.Replay(JsonArray(items).toString()))
        }
        val calls = items.filter { it["type"]?.jsonPrimitive?.contentOrNull == "function_call" }.mapNotNull { item ->
            val name = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AiToolCall(
                // function_call_output nối bằng call_id, không phải id (fc_...).
                id = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                    ?: item["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = name,
                argsJson = item["arguments"]?.jsonPrimitive?.contentOrNull?.ifBlank { "{}" } ?: "{}",
            )
        }.filter { it.id.isNotEmpty() }
        if (calls.isNotEmpty()) emit(AiEvent.ToolCalls(calls))
    }.flowOn(Dispatchers.IO)

    /**
     * Body gửi đi, tách riêng khỏi stream() để test được.
     * - instructions: system prompt (gửi lại mỗi lượt — previous_response_id
     *   không mang nó theo, và app cũng không dùng previous_response_id).
     * - input: lịch sử dạng item (xem [buildInput]).
     * - reasoning: {effort, summary: "auto"} — summary để user đọc được suy
     *   luận (không xin thì reasoning item không có summary). Effort NONE
     *   thì không xin summary (không có suy luận để tóm tắt).
     * - max_output_tokens (Responses dùng tên này, không phải
     *   max_completion_tokens). stream: true. store: false (stateless —
     *   không lưu gì trên server OpenAI, reasoning item kèm encrypted_content).
     * - KHÔNG gửi stop (Responses không có param này), KHÔNG gửi
     *   stream_options, KHÔNG gửi temperature/top_p (Tov không dùng).
     */
    internal fun buildBody(request: AiRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_output_tokens", request.maxOutputTokens)
        if (request.system.isNotBlank()) put("instructions", request.system)
        put("stream", true)
        put("store", false)
        val effort = request.metadata.responsesEffort()
        put("reasoning", buildJsonObject {
            put("effort", effort)
            if (effort != "none") put("summary", "auto")
        })
        put("input", buildInput(request.messages, request.metadata.replayReasoning))
        val tools = request.tools
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.schema)
                    })
                }
            })
            put("tool_choice", "auto")
        }
    }

    /**
     * Lịch sử thành input items của Responses, đúng thứ tự.
     * - Tin user: {role: "user", content} (kèm ảnh thì content là mảng
     *   input_text + input_image).
     * - Tin assistant có response items đã lưu (hãng openai): gửi lại
     *   nguyên vẹn — reasoning + message + function_call. Function_call
     *   chỉ giữ khi có function_call_output tương ứng sau đó (tránh gửi
     *   lệnh gọi lẻ — API từ chối). Reasoning item chỉ giữ khi công tắc
     *   [MetadataParams.replayReasoning] bật, hoặc lượt đó có tool call
     *   (OpenAI bắt buộc reasoning đi kèm kết quả tool call).
     * - Tin assistant không có response items (tin cũ / nhập từ file):
     *   dựng lại từ chữ + tool calls (message item + function_call item).
     * - Tin tool: {type: "function_call_output", call_id, output} — chỉ
     *   khi call_id đó thật sự đã được gọi trước đó.
     */
    internal fun buildInput(msgs: List<AiMessage>, replayReasoning: Boolean = true): JsonArray {
        val outputIds = msgs.filter { it.role == Role.TOOL }.mapNotNull { it.toolCallId }.toSet()
        val calledIds = msgs.filter { it.role == Role.ASSISTANT }.flatMap { m ->
            m.toolCalls.map { it.id } +
                (m.reasoningReplay?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }
                    ?.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        if (o["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                            o["call_id"]?.jsonPrimitive?.contentOrNull
                        } else null
                    }.orEmpty())
        }.toSet()
        return buildJsonArray {
            for (m in msgs) {
                when (m.role) {
                    Role.USER -> {
                        val images = m.imagePaths.mapNotNull { imageBase64(it) }
                        if (images.isEmpty()) {
                            add(buildJsonObject { put("role", "user"); put("content", m.text) })
                        } else {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", buildJsonArray {
                                    if (m.text.isNotBlank()) add(buildJsonObject {
                                        put("type", "input_text"); put("text", m.text)
                                    })
                                    images.forEach { b64 ->
                                        add(buildJsonObject {
                                            put("type", "input_image")
                                            put("image_url", "data:image/jpeg;base64,$b64")
                                        })
                                    }
                                })
                            })
                        }
                    }
                    Role.ASSISTANT -> {
                        val replayed = m.reasoningReplay
                            ?.takeIf { m.reasoningProvider == "openai" }
                            ?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }
                        if (replayed != null) {
                            // Gửi lại nguyên vẹn, trừ function_call lẻ (không
                            // có kết quả tool đi kèm trong lịch sử này).
                            // Công tắc replayReasoning (12/11/2026, chỉ
                            // OpenAI): tắt thì lượt thường bỏ reasoning item
                            // (giữ lại message + function_call); lượt có tool
                            // call thì reasoning bắt buộc đi kèm kết quả tool
                            // nên luôn gửi.
                            val hasToolCall = m.toolCalls.isNotEmpty() ||
                                replayed.any { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "function_call" }
                            val sendReasoning = replayReasoning || hasToolCall
                            replayed.forEach { el ->
                                val o = el as? JsonObject ?: return@forEach
                                val type = o["type"]?.jsonPrimitive?.contentOrNull
                                if (type == "reasoning" && !sendReasoning) return@forEach
                                if (type == "function_call") {
                                    val callId = o["call_id"]?.jsonPrimitive?.contentOrNull
                                    if (callId !in outputIds) return@forEach
                                }
                                add(o)
                            }
                        } else {
                            if (m.text.isNotBlank()) {
                                add(buildJsonObject { put("role", "assistant"); put("content", m.text) })
                            }
                            m.toolCalls.forEach { tc ->
                                if (tc.id !in outputIds) return@forEach
                                add(buildJsonObject {
                                    put("type", "function_call")
                                    // Input item cần id: lấy call_id (duy nhất
                                    // theo lệnh gọi); server nối kết quả bằng
                                    // call_id.
                                    put("id", tc.id)
                                    put("call_id", tc.id)
                                    put("name", tc.name)
                                    put("arguments", tc.argsJson)
                                })
                            }
                        }
                    }
                    Role.TOOL -> {
                        val callId = m.toolCallId ?: return@forEach
                        if (callId !in calledIds) return@forEach
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put("output", m.text)
                        })
                    }
                }
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
