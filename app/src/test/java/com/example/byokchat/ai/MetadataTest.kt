package com.example.byokchat.ai

import com.example.byokchat.data.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun effortIsKeptForEveryProvider() {
        // Đợt provider 12/11/2026: KHÔNG ép effort "none" cho hãng nào nữa —
        // làm theo từng hãng (OpenAI dùng Responses API, suy luận + tool
        // dùng chung được; DeepSeek có mapping riêng; Custom gửi nguyên vẹn).
        val medium = MetadataParams()
        val high = MetadataParams(effort = ReasoningEffort.HIGH)
        assertEquals("medium", medium.effortValueFor(ProviderType.OPENAI))
        assertEquals("high", high.effortValueFor(ProviderType.OPENAI))
        assertEquals("high", high.effortValueFor(ProviderType.CUSTOM))
        assertEquals("high", high.effortValueFor(ProviderType.DEEPSEEK))
        assertEquals(ReasoningEffort.HIGH, high.effectiveEffort(ProviderType.OPENAI))
        assertEquals(ReasoningEffort.HIGH, high.effectiveEffort(ProviderType.CUSTOM))
        assertEquals(ReasoningEffort.HIGH, high.effectiveEffort(ProviderType.DEEPSEEK))
        assertEquals(ReasoningEffort.HIGH, high.effectiveEffort(ProviderType.GEMINI))
        // Tóm tắt trên panel hiện effort thật sự áp cho hãng đó.
        assertEquals("gpt-5 · effort high · max 8192", high.summary("gpt-5", 8192, ProviderType.OPENAI))
    }

    @Test
    fun deepSeekEffortClampsToLowHighMax() {
        val m = MetadataParams()
        assertEquals("none", m.copy(effort = ReasoningEffort.NONE).deepSeekEffort())
        assertEquals("low", m.copy(effort = ReasoningEffort.MINIMAL).deepSeekEffort())
        assertEquals("low", m.copy(effort = ReasoningEffort.LOW).deepSeekEffort())
        assertEquals("high", m.deepSeekEffort())
        assertEquals("high", m.copy(effort = ReasoningEffort.XHIGH).deepSeekEffort())
        assertEquals("max", m.copy(effort = ReasoningEffort.MAX).deepSeekEffort())
    }

    @Test
    fun astraAndSolRejectNone() {
        val none = MetadataParams(effort = ReasoningEffort.NONE)
        assertNull(MetadataParams().responsesEffortProblem("gpt-6-astra"))
        assertNull(none.responsesEffortProblem("gpt-5.5"))
        val problem = none.responsesEffortProblem("gpt-6-astra")
        assertNotNull(problem)
        assertTrue(problem!!.contains("does not accept"))
        assertNotNull(none.responsesEffortProblem("gpt-6.1 sol"))
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
    fun effortOptionsArePerProvider() {
        val m = MetadataParams()
        assertEquals(7, m.effortOptionsFor(ProviderType.OPENAI).size)
        assertEquals(listOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX), m.effortOptionsFor(ProviderType.DEEPSEEK))
        assertFalse(m.effortOptionsFor(ProviderType.GEMINI).contains(ReasoningEffort.XHIGH))
        assertFalse(m.effortOptionsFor(ProviderType.GEMINI).contains(ReasoningEffort.MAX))
    }

    @Test
    fun fieldNamesMatchEachProvidersApi() {
        val m = MetadataParams()
        assertEquals("reasoning.effort", m.effortFieldName(ProviderType.OPENAI))
        assertEquals("reasoning_effort", m.effortFieldName(ProviderType.CUSTOM))
        assertEquals("reasoning_effort", m.effortFieldName(ProviderType.DEEPSEEK))
        assertEquals("thinkingLevel", m.effortFieldName(ProviderType.GEMINI))
        assertEquals("medium", m.effortValueFor(ProviderType.OPENAI))
        assertEquals("high", m.copy(effort = ReasoningEffort.MEDIUM).effortValueFor(ProviderType.DEEPSEEK))
        assertEquals("MEDIUM", m.effortValueFor(ProviderType.GEMINI))
        assertNull(m.copy(effort = ReasoningEffort.NONE).effortValueFor(ProviderType.GEMINI))
    }
}
