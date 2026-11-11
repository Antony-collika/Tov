package com.example.byokchat.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Client chỉ dành cho công cụ đọc trang web (fetch_url): chờ ngắn hơn client của chat. */
    val fetchClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
    val JSON = "application/json; charset=utf-8".toMediaType()
}

data class SseEvent(val event: String?, val data: String)

/** Đọc luồng Server-Sent Events. Hủy coroutine thì kết nối cũng bị hủy ngay. */
fun OkHttpClient.sseFlow(
    request: Request,
    /** Gọi với mã HTTP thật ngay khi nhận phản hồi (cả khi lỗi HTTP), để request log ghi đúng mã. */
    onStatus: (Int) -> Unit = {},
): Flow<SseEvent> = flow {
    val call = newCall(request)
    val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
    try {
        call.execute().use { resp ->
            onStatus(resp.code)
            if (!resp.isSuccessful) {
                throw AiException(errorMessage(resp.code, resp.body?.string().orEmpty()))
            }
            val source = resp.body?.source() ?: throw AiException("The server returned no data.")
            var event: String? = null
            val data = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            emit(SseEvent(event, data.toString()))
                            data.clear()
                        }
                        event = null
                    }
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).trimStart())
                    }
                }
            }
            if (data.isNotEmpty()) emit(SseEvent(event, data.toString()))
        }
    } catch (e: IOException) {
        if (currentCoroutineContext().isActive) {
            throw AiException(networkErrorMessage(e.message ?: e.javaClass.simpleName))
        } else {
            throw e
        }
    } finally {
        handle?.dispose()
    }
}.flowOn(Dispatchers.IO)

/** Gửi một yêu cầu thường (không streaming) và trả về JSON. */
suspend fun OkHttpClient.getJson(request: Request): JsonElement = withContext(Dispatchers.IO) {
    try {
        newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AiException(errorMessage(resp.code, body))
            Json.parseToJsonElement(body)
        }
    } catch (e: IOException) {
        throw AiException(networkErrorMessage(e.message ?: e.javaClass.simpleName))
    }
}

fun errorMessage(code: Int, body: String): String {
    val detail = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val err = root["error"]
        when (err) {
            is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> err.contentOrNull
            else -> root["message"]?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()
    val raw = "Error $code: ${detail ?: body.take(300).ifBlank { "unknown cause" }}"
    return friendlyHttpMessage(code) + "\nDetails: " + raw
}

/** Câu giải thích dễ hiểu cho từng mã lỗi HTTP. */
internal fun friendlyHttpMessage(code: Int): String = when (code) {
    400 -> "The request was rejected because its content or settings are not valid."
    401 -> "The API key is wrong or has been revoked. Check the key in Settings → AI providers."
    403 -> "This key is not allowed to use the selected model or feature, or the account is restricted."
    404 -> "The model or server address was not found. Check the model name and the server address."
    408 -> "The server took too long to respond. Please try again."
    413 -> "The request is too large (the conversation or an image is too big)."
    429 -> "You are sending requests too fast or have run out of quota. Wait a moment and try again, or check your limits/balance."
    in 500..599 -> "The provider's server is having problems or is overloaded. Try again in a few minutes."
    else -> "The provider returned an error."
}

/** Lỗi mạng (mất kết nối, hết thời gian chờ...). */
internal fun networkErrorMessage(detail: String): String =
    "Could not connect to the network. Check your Wi-Fi/mobile data and try again.\nDetails: $detail"

fun errorText(el: JsonElement): String = when (el) {
    is JsonObject -> el["message"]?.jsonPrimitive?.contentOrNull ?: el.toString()
    is JsonPrimitive -> el.contentOrNull ?: el.toString()
    else -> el.toString()
}
