package com.example.byokchat.ui

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType

/** What the user can do about an AI error, besides "Retry". */
internal enum class ErrorFix { PROVIDER_SETTINGS, PICK_MODEL }

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
