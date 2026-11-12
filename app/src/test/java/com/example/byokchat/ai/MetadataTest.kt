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
        assertEquals(ReasoningEffort.HIGH, high.effectiveEffort(ProviderType.CLAUDE))
        // Tóm tắt trên panel hiện effort thật sự áp cho hãng đó.
        assertEquals("gpt-5 · effort high · max 8192", high.summary("gpt-5", 8192, ProviderType.OPENAI))
        assertEquals("gpt-5 · effort high · max 8192", high.summary("gpt-5", 8192, ProviderType.CLAUDE))
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
    fun thinkingDefaultsAreOffWithBudget4096() {
        val m = MetadataParams()
        assertFalse(m.thinkingEnabled)
        assertEquals(4096, m.thinkingBudget)
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
        assertNull(m.copy(effort = ReasoningEffort.NONE).claudeEffort("claude-opus-5"))
        assertEquals("low", m.copy(effort = ReasoningEffort.MINIMAL).claudeEffort("claude-opus-5"))
        assertEquals("medium", m.claudeEffort("claude-opus-5"))
        assertEquals("xhigh", m.copy(effort = ReasoningEffort.XHIGH).claudeEffort("claude-opus-5"))
        assertEquals("max", m.copy(effort = ReasoningEffort.MAX).claudeEffort("claude-opus-5"))
    }

    @Test
    fun claudeEffortDependsOnModelSupport() {
        // Đợt provider 12/11/2026 (Q6): chỉ gửi effort cho model hãng hỗ trợ.
        val m = MetadataParams()
        // Model không hỗ trợ effort thì không gửi gì (gửi là lỗi 400).
        assertNull(m.claudeEffort("claude-sonnet-4-5"))
        assertNull(m.claudeEffort("claude-haiku-4-5"))
        assertNull(m.claudeEffort("claude-3-5-sonnet"))
        assertNull(m.claudeEffort("claude-opus-4-4"))
        // Opus 4.5 chỉ có low/medium/high: xhigh/max kẹp về high.
        assertEquals("high", m.copy(effort = ReasoningEffort.XHIGH).claudeEffort("claude-opus-4-5"))
        assertEquals("high", m.copy(effort = ReasoningEffort.MAX).claudeEffort("claude-opus-4-5"))
        assertEquals("medium", m.claudeEffort("claude-opus-4-5"))
        // Opus 4.6 / Sonnet 4.6: có max nhưng không có xhigh.
        assertEquals("high", m.copy(effort = ReasoningEffort.XHIGH).claudeEffort("claude-opus-4-6"))
        assertEquals("max", m.copy(effort = ReasoningEffort.MAX).claudeEffort("claude-sonnet-4-6"))
        // Model mới không nhận ra tên thì coi là hỗ trợ đủ.
        assertEquals("xhigh", m.copy(effort = ReasoningEffort.XHIGH).claudeEffort("unknown-model"))
    }

    @Test
    fun effortOptionsArePerProvider() {
        val m = MetadataParams()
        assertEquals(7, m.effortOptionsFor(ProviderType.OPENAI).size)
        assertEquals(listOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX), m.effortOptionsFor(ProviderType.DEEPSEEK))
        assertFalse(m.effortOptionsFor(ProviderType.GEMINI).contains(ReasoningEffort.XHIGH))
        assertFalse(m.effortOptionsFor(ProviderType.GEMINI).contains(ReasoningEffort.MAX))
        assertFalse(m.effortOptionsFor(ProviderType.CLAUDE).contains(ReasoningEffort.MINIMAL))
    }

    @Test
    fun fieldNamesMatchEachProvidersApi() {
        val m = MetadataParams()
        assertEquals("reasoning.effort", m.effortFieldName(ProviderType.OPENAI))
        assertEquals("reasoning_effort", m.effortFieldName(ProviderType.CUSTOM))
        assertEquals("reasoning_effort", m.effortFieldName(ProviderType.DEEPSEEK))
        assertEquals("thinkingLevel", m.effortFieldName(ProviderType.GEMINI))
        assertEquals("output_config.effort", m.effortFieldName(ProviderType.CLAUDE))
        assertEquals("medium", m.effortValueFor(ProviderType.OPENAI))
        assertEquals("high", m.copy(effort = ReasoningEffort.MEDIUM).effortValueFor(ProviderType.DEEPSEEK))
        assertEquals("MEDIUM", m.effortValueFor(ProviderType.GEMINI))
        assertEquals("medium", m.effortValueFor(ProviderType.CLAUDE))
        assertNull(m.copy(effort = ReasoningEffort.NONE).effortValueFor(ProviderType.GEMINI))
    }
}
