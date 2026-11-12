package com.example.byokchat.ui

import com.example.byokchat.chat.RepairMode
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

    // Đợt bỏ provider 12/11/2026 (sửa theo ý Duy): đoán nguyên nhân lỗi để
    // giải thích user cần làm gì — bật lại công tắc rồi Retry gửi lại đủ,
    // hoặc dữ liệu đã mất thì dùng nút Retry đã BỚT, ghi rõ. Không đoán
    // được thì không giải thích thêm.

    private val custom = ProviderConfig(id = "p", type = ProviderType.CUSTOM, name = "DeepSeek", apiKey = "sk-1")
    private val gemini = ProviderConfig(id = "p", type = ProviderType.GEMINI, name = "Gemini", apiKey = "k")

    @Test fun customReasoningContentErrorOffersReducedRetryWithoutTools() {
        val error = "The server returned an error.\n" +
            "Details: Error 400: reasoning_content must be fully passed back to the API in all subsequent requests."
        assertEquals(ApiErrorCause.MISSING_REASONING_CONTENT, apiErrorCause(error, custom))
        val help = apiErrorHelp(error, custom, keepThoughtSignature = true)
        assertNotNull(help)
        assertTrue(help!!.explanation.contains("Retry without tools"))
        assertEquals(ReducedRetry.WITHOUT_TOOLS, help.reducedRetry)
    }

    @Test fun reasoningContentKeywordOnOtherProviderIsNotMapped() {
        val error = "Details: Error 400: reasoning_content must be fully passed back."
        assertNull(apiErrorCause(error, ok))
        assertNull(apiErrorHelp(error, ok, keepThoughtSignature = true))
        assertNull(apiErrorCause(error, gemini))
    }

    @Test fun geminiThoughtSignatureErrorWithSignatureOnOffersReducedRetry() {
        val error = "The server returned an error.\n" +
            "Details: Error 400: Function call with an invalid thought signature was found."
        assertEquals(ApiErrorCause.MISSING_THOUGHT_SIGNATURE, apiErrorCause(error, gemini))
        val help = apiErrorHelp(error, gemini, keepThoughtSignature = true)
        assertNotNull(help)
        assertTrue(help!!.explanation.contains("Retry without the last tool call"))
        assertEquals(ReducedRetry.WITHOUT_LAST_TOOL_CALL, help.reducedRetry)
    }

    @Test fun geminiThoughtSignatureErrorWithSignatureOffTellsUserToTurnItBackOn() {
        val error = "The server returned an error.\n" +
            "Details: Error 400: Function call with an invalid thought signature was found."
        val help = apiErrorHelp(error, gemini, keepThoughtSignature = false)
        assertNotNull(help)
        assertTrue(help!!.explanation.contains("Keep thought signature"))
        assertTrue(help.explanation.contains("Turn it back on"))
        assertNull(help.reducedRetry)
    }

    @Test fun thoughtSignatureKeywordOnOtherProviderIsNotMapped() {
        val error = "Details: Error 400: invalid thought_signature."
        assertNull(apiErrorCause(error, custom))
        assertNull(apiErrorHelp(error, custom, keepThoughtSignature = true))
    }

    @Test fun unmappedErrorExplainsNothing() {
        assertNull(apiErrorCause("The server returned an error.\nDetails: Error 500: boom.", custom))
        assertNull(apiErrorHelp("The server returned an error.\nDetails: Error 500: boom.", custom, keepThoughtSignature = true))
        assertNull(apiErrorCause(null, custom))
        assertNull(apiErrorHelp(null, custom, keepThoughtSignature = true))
    }

    @Test fun reducedRetryMapsToRepairModeAndLabel() {
        assertEquals(RepairMode.NO_TOOLS, ReducedRetry.WITHOUT_TOOLS.repairMode())
        assertEquals(RepairMode.DROP_LAST_TOOL_ROUND, ReducedRetry.WITHOUT_LAST_TOOL_CALL.repairMode())
        assertEquals("Retry without tools", ReducedRetry.WITHOUT_TOOLS.label())
        assertEquals("Retry without the last tool call", ReducedRetry.WITHOUT_LAST_TOOL_CALL.label())
    }
}
