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
    val JSON = "application/json; charset=utf-8".toMediaType()
}

data class SseEvent(val event: String?, val data: String)

/** Đọc luồng Server-Sent Events. Hủy coroutine thì kết nối cũng bị hủy ngay. */
fun OkHttpClient.sseFlow(request: Request): Flow<SseEvent> = flow {
    val call = newCall(request)
    val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
    try {
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                throw AiException(errorMessage(resp.code, resp.body?.string().orEmpty()))
            }
            val source = resp.body?.source() ?: throw AiException("Máy chủ không trả về dữ liệu.")
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
    val raw = "Lỗi $code: ${detail ?: body.take(300).ifBlank { "không rõ nguyên nhân" }}"
    return friendlyHttpMessage(code) + "\nChi tiết: " + raw
}

/** Câu giải thích dễ hiểu cho từng mã lỗi HTTP. */
internal fun friendlyHttpMessage(code: Int): String = when (code) {
    400 -> "Yêu cầu bị từ chối vì nội dung hoặc cài đặt chưa hợp lệ."
    401 -> "API key không đúng hoặc đã bị thu hồi. Hãy kiểm tra lại key trong Cài đặt → Nhà cung cấp AI."
    403 -> "Key này không có quyền dùng mô hình hoặc chức năng đang chọn, hoặc tài khoản bị hạn chế."
    404 -> "Không tìm thấy mô hình hoặc địa chỉ máy chủ. Hãy kiểm tra tên mô hình và địa chỉ máy chủ."
    408 -> "Máy chủ phản hồi quá lâu. Hãy thử lại."
    413 -> "Nội dung gửi đi quá lớn (cuộc trò chuyện quá dài hoặc ảnh quá lớn)."
    429 -> "Bạn gửi quá nhanh hoặc đã hết hạn mức của tài khoản. Hãy đợi một lúc rồi thử lại, hoặc kiểm tra hạn mức/số dư."
    in 500..599 -> "Máy chủ của nhà cung cấp đang gặp sự cố hoặc quá tải. Hãy thử lại sau ít phút."
    else -> "Nhà cung cấp trả về lỗi."
}

/** Lỗi mạng (mất kết nối, hết thời gian chờ...). */
internal fun networkErrorMessage(detail: String): String =
    "Không kết nối được mạng. Hãy kiểm tra Wi-Fi/4G rồi thử lại.\nChi tiết: $detail"

fun errorText(el: JsonElement): String = when (el) {
    is JsonObject -> el["message"]?.jsonPrimitive?.contentOrNull ?: el.toString()
    is JsonPrimitive -> el.contentOrNull ?: el.toString()
    else -> el.toString()
}
