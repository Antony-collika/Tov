package com.example.byokchat.ui

import com.example.byokchat.chat.RepairMode
import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType

/** What the user can do about an AI error, besides "Retry". */
internal enum class ErrorFix { PROVIDER_SETTINGS, PICK_MODEL }

/**
 * Why a provider request failed, when the app can tell from the error
 * (đợt bỏ provider 12/11/2026: educate + Retry, không khóa gì cả).
 * - MISSING_REASONING_CONTENT: máy chủ tương thích (vd DeepSeek) từ chối vì
 *   thiếu reasoning_content khi request có tools.
 * - MISSING_THOUGHT_SIGNATURE: Gemini từ chối vì thiếu/không khớp chữ ký
 *   suy luận của một lệnh gọi tool trước đó.
 */
internal enum class ApiErrorCause { MISSING_REASONING_CONTENT, MISSING_THOUGHT_SIGNATURE }

/**
 * Reduced retry the app can offer as a SEPARATE, clearly labelled button
 * when the data the server needs is gone for good and cannot be restored
 * (sửa theo ý Duy 12/11/2026). It is never the default Retry: the default
 * Retry always resends the full request, rebuilt from the current settings
 * + stored reasoning, after the user has fixed the cause.
 */
internal enum class ReducedRetry { WITHOUT_TOOLS, WITHOUT_LAST_TOOL_CALL }

/** Label shown on the reduced-retry button. */
internal fun ReducedRetry.label(): String = when (this) {
    ReducedRetry.WITHOUT_TOOLS -> "Retry without tools"
    ReducedRetry.WITHOUT_LAST_TOOL_CALL -> "Retry without the last tool call"
}

/** The engine repair a reduced retry runs. */
internal fun ReducedRetry.repairMode(): RepairMode = when (this) {
    ReducedRetry.WITHOUT_TOOLS -> RepairMode.NO_TOOLS
    ReducedRetry.WITHOUT_LAST_TOOL_CALL -> RepairMode.DROP_LAST_TOOL_ROUND
}

/**
 * What the error bubble shows for a guessed provider error (null = the
 * error already explains itself, nothing more to add).
 * - [ApiErrorHelp.explanation]: plain-language explanation telling the user
 *   what to do — either fix a setting and press Retry (the full request is
 *   sent again), or accept that the data is lost and use the reduced retry.
 * - [ApiErrorHelp.reducedRetry]: the reduced retry offered when the missing
 *   data cannot be restored; null when the cause can be fixed by a setting.
 */
internal data class ApiErrorHelp(
    val explanation: String,
    val reducedRetry: ReducedRetry? = null,
)

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
 * Help for a guessed provider error (null = nothing more to add — the
 * friendly sentence from the HTTP status in the error itself is enough).
 * [keepThoughtSignature] is the CURRENT "Keep thought signature" setting
 * (Metadata card): a Gemini signature error while it is OFF most likely
 * means the signatures were not sent — the user can fix it by turning the
 * switch back on and pressing Retry; while it is ON, the signature itself
 * is genuinely lost.
 */
internal fun apiErrorHelp(error: String?, provider: ProviderConfig?, keepThoughtSignature: Boolean): ApiErrorHelp? =
    when (apiErrorCause(error, provider)) {
        ApiErrorCause.MISSING_REASONING_CONTENT ->
            ApiErrorHelp(
                explanation = "The server needs the earlier reasoning sent back with the tool calls, but it is gone — " +
                    "it was lost when an earlier answer was edited or deleted, and it cannot be restored. " +
                    "Retry sends the full request again, but the server will likely refuse it again for the same reason. " +
                    "Use \"Retry without tools\" instead: the server accepts a request without tools even without that reasoning.",
                reducedRetry = ReducedRetry.WITHOUT_TOOLS,
            )
        ApiErrorCause.MISSING_THOUGHT_SIGNATURE ->
            if (!keepThoughtSignature) {
                ApiErrorHelp(
                    explanation = "Gemini needs the thinking signature from an earlier reply sent back, but " +
                        "\"Keep thought signature\" is off in the Metadata card, so the signatures were not sent. " +
                        "Turn it back on, then press Retry to send the full request again.",
                )
            } else {
                ApiErrorHelp(
                    explanation = "Gemini needs the thinking signature from an earlier tool call sent back, but it is gone " +
                        "or no longer matches — it was lost when an earlier answer was edited or deleted, and it cannot be restored. " +
                        "Retry sends the full request again, but Gemini will likely refuse it again for the same reason. " +
                        "Use \"Retry without the last tool call\" instead: Gemini can answer from the text alone without that tool call.",
                    reducedRetry = ReducedRetry.WITHOUT_LAST_TOOL_CALL,
                )
            }
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
