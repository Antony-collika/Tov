package com.example.byokchat.ui

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorHintsTest {
    private val ok = ProviderConfig(id = "p", type = ProviderType.OPENAI, name = "OpenAI", apiKey = "sk-123")
    private fun http(code: Int) = "Friendly sentence.\nDetails: Error $code: something"

    @Test fun statusIsReadFromDetailsLine() {
        assertEquals(401, httpStatusOf(http(401)))
        assertEquals(429, httpStatusOf(http(429)))
    }

    @Test fun noDetailsLineMeansNoStatus() {
        assertNull(httpStatusOf("Just one line mentioning 401: here"))
        assertNull(httpStatusOf(null))
    }

    @Test fun wrongKeyOrQuotaOffersProviderSettings() {
        for (c in listOf(401, 403, 404, 429)) assertEquals(ErrorFix.PROVIDER_SETTINGS, errorFix(http(c), ok, "gpt"))
    }

    @Test fun serverErrorsOfferNothingExtra() {
        assertNull(errorFix(http(500), ok, "gpt"))
        assertNull(errorFix("Network is down.\nDetails: timeout", ok, "gpt"))
    }

    @Test fun missingProviderOrKeyOffersProviderSettings() {
        assertEquals(ErrorFix.PROVIDER_SETTINGS, errorFix("x", null, "gpt"))
        assertEquals(ErrorFix.PROVIDER_SETTINGS, errorFix("x", ok.copy(apiKey = ""), "gpt"))
    }

    @Test fun customProviderMayHaveNoKeyButNeedsAnAddress() {
        val custom = ok.copy(type = ProviderType.CUSTOM, apiKey = "", baseUrl = "http://localhost:1234/v1")
        assertNull(errorFix("x", custom, "m"))
        assertEquals(ErrorFix.PROVIDER_SETTINGS, errorFix("x", custom.copy(baseUrl = ""), "m"))
    }

    @Test fun missingModelOffersPicker() {
        assertEquals(ErrorFix.PICK_MODEL, errorFix("x", ok, ""))
    }

    // Đợt bỏ provider 12/11/2026: đoán nguyên nhân lỗi để Retry sửa rồi gửi
    // lại — không đoán được thì không giải thích thêm.

    private val custom = ProviderConfig(id = "p", type = ProviderType.CUSTOM, name = "DeepSeek", apiKey = "sk-1")
    private val gemini = ProviderConfig(id = "p", type = ProviderType.GEMINI, name = "Gemini", apiKey = "k")

    @Test fun customReasoningContentErrorMapsToMissingReasoning() {
        val error = "The server returned an error.\n" +
            "Details: Error 400: reasoning_content must be fully passed back to the API in all subsequent requests."
        assertEquals(ApiErrorCause.MISSING_REASONING_CONTENT, apiErrorCause(error, custom))
        val explanation = explainApiError(error, custom)
        assertNotNull(explanation)
        assertTrue(explanation!!.contains("without tools"))
    }

    @Test fun reasoningContentKeywordOnOtherProviderIsNotMapped() {
        val error = "Details: Error 400: reasoning_content must be fully passed back."
        assertNull(apiErrorCause(error, ok))
        assertNull(explainApiError(error, ok))
        assertNull(apiErrorCause(error, gemini))
    }

    @Test fun geminiThoughtSignatureErrorMapsToMissingSignature() {
        val error = "The server returned an error.\n" +
            "Details: Error 400: Function call with an invalid thought signature was found."
        assertEquals(ApiErrorCause.MISSING_THOUGHT_SIGNATURE, apiErrorCause(error, gemini))
        val explanation = explainApiError(error, gemini)
        assertNotNull(explanation)
        assertTrue(explanation!!.contains("without that last tool call"))
    }

    @Test fun thoughtSignatureKeywordOnOtherProviderIsNotMapped() {
        val error = "Details: Error 400: invalid thought_signature."
        assertNull(apiErrorCause(error, custom))
        assertNull(explainApiError(error, custom))
    }

    @Test fun unmappedErrorExplainsNothing() {
        assertNull(apiErrorCause("The server returned an error.\nDetails: Error 500: boom.", custom))
        assertNull(explainApiError("The server returned an error.\nDetails: Error 500: boom.", custom))
        assertNull(apiErrorCause(null, custom))
        assertNull(explainApiError(null, custom))
    }
}
