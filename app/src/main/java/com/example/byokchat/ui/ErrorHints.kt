package com.example.byokchat.ui

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType

/** What the user can do about an AI error, besides "Retry". */
internal enum class ErrorFix { PROVIDER_SETTINGS, PICK_MODEL }

/**
 * Why a provider request failed, when the app can tell from the error —
 * so Retry can fix the cause instead of resending the identical request
 * (đợt bỏ provider 12/11/2026: educate + Retry, không khóa gì cả).
 * - MISSING_REASONING_CONTENT: máy chủ tương thích (vd DeepSeek) từ chối vì
 *   thiếu reasoning_content khi request có tools.
 * - MISSING_THOUGHT_SIGNATURE: Gemini từ chối vì thiếu/không khớp chữ ký
 *   suy luận của một lệnh gọi tool trước đó.
 */
internal enum class ApiErrorCause { MISSING_REASONING_CONTENT, MISSING_THOUGHT_SIGNATURE }

/**
 * Đoán nguyên nhân lỗi từ chữ lỗi mà provider trả về (kèm trong dòng
 * Details của lỗi HTTP, hoặc message lỗi giữa luồng). Chỉ nhận khi đúng
 * hãng: reasoning_content là luật của máy chủ tương thích, thought
 * signature là luật của Gemini.
 */
internal fun apiErrorCause(error: String?, provider: ProviderConfig?): ApiErrorCause? {
    if (error == null) return null
    val lower = error.lowercase()
    return when (provider?.type) {
        ProviderType.CUSTOM -> if ("reasoning_content" in lower) ApiErrorCause.MISSING_REASONING_CONTENT else null
        ProviderType.GEMINI -> if ("thought_signature" in lower || "thought signature" in lower) ApiErrorCause.MISSING_THOUGHT_SIGNATURE else null
        else -> null
    }
}

/**
 * Câu giải thích bằng lời dễ hiểu cho nguyên nhân đã đoán được (hiện dưới
 * lỗi trong bong bóng chat). Lỗi khác thì trả null — câu thân thiện theo mã
 * HTTP trong chính lỗi đã đủ giải thích.
 */
internal fun explainApiError(error: String?, provider: ProviderConfig?): String? = when (apiErrorCause(error, provider)) {
    ApiErrorCause.MISSING_REASONING_CONTENT ->
        "The server needs the earlier reasoning sent back with the tool calls, but it is missing — it can go missing if an earlier answer was edited or deleted. " +
            "Retry sends the request again without tools, which the server accepts without that reasoning."
    ApiErrorCause.MISSING_THOUGHT_SIGNATURE ->
        "Gemini needs the thinking signature from an earlier tool call sent back, but it is missing or no longer matches — it can happen if an earlier answer was edited or deleted. " +
            "Retry sends the request again without that last tool call, so Gemini can answer from the text alone."
    null -> null
}

/**
 * Picks the extra button for an error card.
 *
 * Setup problems are decided from the CURRENT state (no provider, no key, bad address, no model), so the button
 * disappears once the user has fixed it. HTTP problems (wrong key, no access, model/address not found, quota) are
 * recognised from the status code written in the error's "details" line, which does not depend on the app language.
 */
internal fun errorFix(error: String?, provider: ProviderConfig?, model: String): ErrorFix? {
    if (provider == null) return ErrorFix.PROVIDER_SETTINGS
    if (provider.apiKey.isBlank() && provider.type != ProviderType.CUSTOM) return ErrorFix.PROVIDER_SETTINGS
    if (!provider.effectiveBaseUrl().startsWith("http")) return ErrorFix.PROVIDER_SETTINGS
    if (model.isBlank()) return ErrorFix.PICK_MODEL
    return when (httpStatusOf(error)) {
        401, 403, 404, 429 -> ErrorFix.PROVIDER_SETTINGS
        else -> null
    }
}

// The details line looks like "<label> 401: <message>"; the status code is the first "NNN:" after the first line.
private val STATUS_RX = Regex("""(?<!\d)(\d{3}):""")

internal fun httpStatusOf(error: String?): Int? {
    if (error == null) return null
    val nl = error.indexOf('\n')
    if (nl < 0) return null
    return STATUS_RX.find(error, nl + 1)?.groupValues?.get(1)?.toIntOrNull()
}

internal fun ErrorFix.label(): String = when (this) {
    ErrorFix.PROVIDER_SETTINGS -> "Open provider settings"
    ErrorFix.PICK_MODEL -> "Select model"
}
