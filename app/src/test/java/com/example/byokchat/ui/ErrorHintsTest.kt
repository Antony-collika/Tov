package com.example.byokchat.ui

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
