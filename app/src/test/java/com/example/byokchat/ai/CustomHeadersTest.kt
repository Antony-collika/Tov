package com.example.byokchat.ai

import com.example.byokchat.data.CustomHeader
import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test cho custom HTTP headers theo từng provider: model đọc/ghi JSON
 * và helper gắn header lên request. Header xác thực luôn thắng custom
 * header cùng tên vì các provider gọi withCustomHeaders() TRƯỚC
 * withAuth() ở mọi chỗ dựng request (stream + listModels).
 */
class CustomHeadersTest {

    /** Cùng cấu hình JSON như SettingsRepository dùng để lưu provider. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun cfg(headers: List<CustomHeader> = emptyList()) = ProviderConfig(
        id = "p1", type = ProviderType.CUSTOM, name = "Test", apiKey = "real-key",
        baseUrl = "https://example.test/v1", customHeaders = headers,
    )

    private fun build(headers: List<CustomHeader>) =
        Request.Builder().url("https://example.test/v1/chat/completions")
            .withCustomHeaders(cfg(headers)).build()

    @Test
    fun `headers round-trip through JSON`() {
        val original = cfg(listOf(CustomHeader("user-agent", "Cursor/0.40.0"), CustomHeader("X-Foo", "bar")))
        val decoded = json.decodeFromString<ProviderConfig>(json.encodeToString(original))
        assertEquals(original.customHeaders, decoded.customHeaders)
    }

    @Test
    fun `old JSON without the field reads as empty headers`() {
        val decoded = json.decodeFromString<ProviderConfig>(
            """{"id":"p1","type":"CUSTOM","name":"Old","apiKey":"k","baseUrl":"https://example.test/v1"}""",
        )
        assertTrue(decoded.customHeaders.isEmpty())
    }

    @Test
    fun `custom headers are carried on the built request`() {
        val req = build(listOf(CustomHeader("user-agent", "Cursor/0.40.0"), CustomHeader("X-Foo", "bar")))
        assertEquals("Cursor/0.40.0", req.header("user-agent"))
        assertEquals("bar", req.header("X-Foo"))
    }

    @Test
    fun `later duplicate header wins`() {
        val req = build(listOf(CustomHeader("X-Foo", "first"), CustomHeader("X-Foo", "second")))
        assertEquals("second", req.header("X-Foo"))
    }

    @Test
    fun `blank and invalid header names are skipped`() {
        val req = build(listOf(CustomHeader("  ", "blank"), CustomHeader("my header", "space"), CustomHeader("X-Ok", "yes")))
        assertNull(req.header("my header"))
        assertEquals("yes", req.header("X-Ok"))
    }

    @Test
    fun `auth applied after custom headers wins`() {
        // Mô phỏng đúng thứ tự các provider dùng: withCustomHeaders()
        // trước, withAuth() sau. Custom header cùng tên phải thua.
        val req = Request.Builder().url("https://example.test/v1/chat/completions")
            .withCustomHeaders(cfg(listOf(CustomHeader("Authorization", "Bearer fake"))))
            .header("Authorization", "Bearer real-key")
            .build()
        assertEquals("Bearer real-key", req.header("Authorization"))
    }

    @Test
    fun `no custom headers means no extra headers`() {
        val req = build(emptyList())
        assertNull(req.header("user-agent"))
    }
}
