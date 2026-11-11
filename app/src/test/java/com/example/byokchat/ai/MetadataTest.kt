package com.example.byokchat.ai

import com.example.byokchat.data.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Test cho Đợt 6 + đợt fix 11/11/2026 (D2–D6): MetadataParams map sang trường riêng từng hãng + luật effort × tools. */
class MetadataTest {

    @Test
    fun defaultsAreMediumNoStops() {
        val m = MetadataParams()
        assertEquals(ReasoningEffort.MEDIUM, m.effort)
        assertEquals(emptyList<String>(), m.stopSequences)
        assertEquals("gpt-4o · effort medium · max 8192", m.summary("gpt-4o", 8192))
    }

    @Test
    fun openAiEffortIsAlwaysForcedToNone() {
        // Đợt fix 11/11/2026 (D3): Chat Completions chỉ gọi được tool khi
        // reasoning_effort "none" — app ép về none để tool luôn dùng được,
        // bất kể effort đã lưu là gì. Giá trị đã lưu vẫn giữ để đổi sang
        // Claude/Gemini thì dùng lại.
        val medium = MetadataParams()
        val high = MetadataParams(effort = ReasoningEffort.HIGH)
        assertEquals("none", medium.effortValueFor(ProviderType.OPENAI))
        assertEquals("none", high.effortValueFor(ProviderType.OPENAI))
        assertEquals("none", medium.effortValueFor(ProviderType.CUSTOM))
        assertEquals(ReasoningEffort.NONE, medium.effectiveEffort(ProviderType.OPENAI))
        assertEquals(ReasoningEffort.NONE, high.effectiveEffort(ProviderType.CUSTOM))
        // Hãng khác giữ nguyên lựa chọn của user.
        assertEquals(ReasoningEffort.HIGH, high.effectiveEffort(ProviderType.GEMINI))
        assertEquals(ReasoningEffort.HIGH, high.effectiveEffort(ProviderType.CLAUDE))
        assertEquals("HIGH", high.effortValueFor(ProviderType.GEMINI))
        assertEquals("high", high.effortValueFor(ProviderType.CLAUDE))
        // Tóm tắt trên panel hiện effort thật sự áp cho hãng đó.
        assertEquals("gpt-4o · effort none · max 8192", high.summary("gpt-4o", 8192, ProviderType.OPENAI))
        assertEquals("gpt-4o · effort high · max 8192", high.summary("gpt-4o", 8192, ProviderType.CLAUDE))
    }

    @Test
    fun geminiThinkingLevelOmitsNoneAndClampsXhighMaxToHigh() {
        val m = MetadataParams()
        assertNull(m.copy(effort = ReasoningEffort.NONE).geminiThinkingLevel())
        assertEquals("MINIMAL", m.copy(effort = ReasoningEffort.MINIMAL).geminiThinkingLevel())
        assertEquals("MEDIUM", m.geminiThinkingLevel())
        assertEquals("HIGH", m.copy(effort = ReasoningEffort.XHIGH).geminiThinkingLevel())
        assertEquals("HIGH", m.copy(effort = ReasoningEffort.MAX).geminiThinkingLevel())
    }

    @Test
    fun claudeEffortOmitsNoneAndClampsMinimalToLow() {
        val m = MetadataParams()
        assertNull(m.copy(effort = ReasoningEffort.NONE).claudeEffort())
        assertEquals("low", m.copy(effort = ReasoningEffort.MINIMAL).claudeEffort())
        assertEquals("medium", m.claudeEffort())
        assertEquals("xhigh", m.copy(effort = ReasoningEffort.XHIGH).claudeEffort())
        assertEquals("max", m.copy(effort = ReasoningEffort.MAX).claudeEffort())
    }

    @Test
    fun effortOptionsArePerProvider() {
        val m = MetadataParams()
        assertEquals(7, m.effortOptionsFor(ProviderType.OPENAI).size)
        assertFalse(m.effortOptionsFor(ProviderType.GEMINI).contains(ReasoningEffort.XHIGH))
        assertFalse(m.effortOptionsFor(ProviderType.GEMINI).contains(ReasoningEffort.MAX))
        assertFalse(m.effortOptionsFor(ProviderType.CLAUDE).contains(ReasoningEffort.MINIMAL))
    }

    @Test
    fun fieldNamesMatchEachProvidersApi() {
        val m = MetadataParams()
        assertEquals("reasoning_effort", m.effortFieldName(ProviderType.OPENAI))
        assertEquals("thinkingLevel", m.effortFieldName(ProviderType.GEMINI))
        assertEquals("output_config.effort", m.effortFieldName(ProviderType.CLAUDE))
        assertEquals("none", m.effortValueFor(ProviderType.OPENAI))
        assertEquals("MEDIUM", m.effortValueFor(ProviderType.GEMINI))
        assertEquals("medium", m.effortValueFor(ProviderType.CLAUDE))
        assertNull(m.copy(effort = ReasoningEffort.NONE).effortValueFor(ProviderType.GEMINI))
    }
}
