package com.example.byokchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the friendly error sentences: every code has its own sentence and keeps the original detail. */
class HttpErrorTest {

    private fun body(msg: String) = "{\"error\":{\"message\":\"$msg\"}}"

    private fun check(code: Int, friendly: String) {
        val r = errorMessage(code, body("chi tiet goc"))
        assertTrue("code $code is missing the friendly sentence: $r", r.startsWith(friendly + "\n"))
        assertTrue("code $code is missing the original detail: $r", r.contains("Details: Error $code: chi tiet goc"))
    }

    @Test fun code400() = check(400, "The request was rejected because its content or settings are not valid.")

    @Test fun code401() = check(401, "The API key is wrong or has been revoked. Check the key in Settings → AI providers.")

    @Test fun code403() = check(403, "This key is not allowed to use the selected model or feature, or the account is restricted.")

    @Test fun code404() = check(404, "The model or server address was not found. Check the model name and the server address.")

    @Test fun code408() = check(408, "The server took too long to respond. Please try again.")

    @Test fun code413() = check(413, "The request is too large (the conversation or an image is too big).")

    @Test fun code429() = check(429, "You are sending requests too fast or have run out of quota. Wait a moment and try again, or check your limits/balance.")

    @Test fun serverErrors500to599() {
        val friendly = "The provider's server is having problems or is overloaded. Try again in a few minutes."
        for (code in listOf(500, 502, 503, 529, 599)) check(code, friendly)
    }

    @Test fun otherCodes() {
        check(418, "The provider returned an error.")
        check(499, "The provider returned an error.")
        check(600, "The provider returned an error.")
    }

    @Test fun errorAsPlainStringIsUsedAsDetail() {
        val r = errorMessage(401, "{\"error\":\"bad key\"}")
        assertTrue(r.endsWith("Details: Error 401: bad key"))
    }

    @Test fun bodyIsNotJson() {
        val r = errorMessage(502, "<html>Bad Gateway</html>")
        assertTrue(r.contains("Details: Error 502: <html>Bad Gateway</html>"))
    }

    @Test fun emptyBody() {
        val r = errorMessage(500, "")
        assertTrue(r.endsWith("Details: Error 500: unknown cause"))
    }

    @Test fun longBodyIsCutTo300Chars() {
        val r = errorMessage(500, "x".repeat(1000))
        assertEquals(300, r.substringAfter("Error 500: ").length)
    }

    @Test fun networkMessage() {
        val r = networkErrorMessage("timeout")
        assertEquals("Could not connect to the network. Check your Wi-Fi/mobile data and try again.\nDetails: timeout", r)
    }
}
