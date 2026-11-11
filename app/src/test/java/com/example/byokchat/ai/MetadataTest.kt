package com.example.byokchat.ai

import com.example.byokchat.data.ProviderType
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho Đợt 6: MetadataParams map sang trường riêng từng hãng + luật tắt tool. */
class MetadataTest {

    private fun toolDefs() = listOf(
        ToolDef("time_now", "Get the current time", buildJsonObject {}),
    )

    @Test
    fun defaultsAreMediumAutoNoStopsNoJsonTierAuto() {
        val m = MetadataParams()
        assertEquals(ReasoningEffort.MEDIUM, m.effort)
        assertEquals(emptyList<String>(), m.stopSequences)
        assertEquals(ToolChoiceMode.AUTO, m.toolChoice)
        assertFalse(m.structuredOutput)
        assertEquals(ServiceTier.AUTO, m.serviceTier)
        assertEquals("gpt-4o · effort medium · max 8192 · stream on", m.summary("gpt-4o", 8192))
    }

    @Test
    fun openAiAlwaysSendsReasoningEffortIncludingNone() {
        // Server mặc định medium nên bỏ qua trường này cũng là medium — và
        // tool sẽ bị chặn; vì vậy OpenAI luôn gửi, kể cả "none".
        assertEquals("none", MetadataParams(effort = ReasoningEffort.NONE).openAiReasoningEffort())
        assertEquals("minimal", MetadataParams(effort = ReasoningEffort.MINIMAL).openAiReasoningEffort())
        assertEquals("medium", MetadataParams().openAiReasoningEffort())
        assertEquals("max", MetadataParams(effort = ReasoningEffort.MAX).openAiReasoningEffort())
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
    fun serviceTierOnlySendsWhereTheProviderHasIt() {
        val m = MetadataParams()
        assertNull(m.openAiServiceTier())
        assertNull(m.claudeServiceTier())
        assertEquals("flex", m.copy(serviceTier = ServiceTier.FLEX).openAiServiceTier())
        assertNull(m.copy(serviceTier = ServiceTier.FLEX).claudeServiceTier())
        assertEquals("standard_only", m.copy(serviceTier = ServiceTier.STANDARD_ONLY).claudeServiceTier())
        assertNull(m.copy(serviceTier = ServiceTier.STANDARD_ONLY).openAiServiceTier())
    }

    @Test
    fun reasoningSuppressesToolsOnlyForOpenAiCompatible() {
        val medium = MetadataParams()
        val none = MetadataParams(effort = ReasoningEffort.NONE)
        // OpenAI (và Custom dùng shape OpenAI): effort != none thì không gửi tool.
        assertTrue(medium.suppressesTools(ProviderType.OPENAI))
        assertTrue(medium.suppressesTools(ProviderType.CUSTOM))
        assertTrue(medium.toolsSuppressedByReasoning(ProviderType.OPENAI))
        assertFalse(none.suppressesTools(ProviderType.OPENAI))
        // Gemini/Claude: effort nào tool cũng gửi được.
        assertFalse(medium.suppressesTools(ProviderType.GEMINI))
        assertFalse(medium.suppressesTools(ProviderType.CLAUDE))
        assertFalse(medium.toolsSuppressedByReasoning(ProviderType.GEMINI))
        assertFalse(medium.toolsSuppressedByReasoning(ProviderType.CLAUDE))
        // toolChoice NONE tắt tool với mọi hãng (tương đương tắt thẻ Tools
        // cho request này).
        assertTrue(none.copy(toolChoice = ToolChoiceMode.NONE).suppressesTools(ProviderType.CLAUDE))
        assertFalse(none.copy(toolChoice = ToolChoiceMode.NONE).toolsSuppressedByReasoning(ProviderType.CLAUDE))
    }

    @Test
    fun effectiveToolsDropsToolsWhenSuppressed() {
        val tools = toolDefs()
        assertEquals(emptyList<ToolDef>(), MetadataParams().effectiveTools(ProviderType.OPENAI, tools))
        assertEquals(tools, MetadataParams(effort = ReasoningEffort.NONE).effectiveTools(ProviderType.OPENAI, tools))
        assertEquals(tools, MetadataParams().effectiveTools(ProviderType.CLAUDE, tools))
        assertEquals(
            emptyList<ToolDef>(),
            MetadataParams(effort = ReasoningEffort.NONE, toolChoice = ToolChoiceMode.NONE)
                .effectiveTools(ProviderType.GEMINI, tools),
        )
    }

    @Test
    fun effortAndTierOptionsArePerProvider() {
        val m = MetadataParams()
        assertEquals(7, m.effortOptionsFor(ProviderType.OPENAI).size)
        assertFalse(m.effortOptionsFor(ProviderType.GEMINI).contains(ReasoningEffort.XHIGH))
        assertFalse(m.effortOptionsFor(ProviderType.GEMINI).contains(ReasoningEffort.MAX))
        assertFalse(m.effortOptionsFor(ProviderType.CLAUDE).contains(ReasoningEffort.MINIMAL))
        assertNull(m.serviceTierOptionsFor(ProviderType.GEMINI))
        assertEquals(
            listOf(ServiceTier.AUTO, ServiceTier.STANDARD_ONLY),
            m.serviceTierOptionsFor(ProviderType.CLAUDE),
        )
        assertFalse(m.supportsStructuredOutput(ProviderType.CLAUDE))
        assertTrue(m.supportsStructuredOutput(ProviderType.OPENAI))
        assertTrue(m.supportsStructuredOutput(ProviderType.GEMINI))
    }

    @Test
    fun fieldNamesMatchEachProvidersApi() {
        val m = MetadataParams()
        assertEquals("reasoning_effort", m.effortFieldName(ProviderType.OPENAI))
        assertEquals("thinkingLevel", m.effortFieldName(ProviderType.GEMINI))
        assertEquals("output_config.effort", m.effortFieldName(ProviderType.CLAUDE))
        assertEquals("medium", m.effortValueFor(ProviderType.OPENAI))
        assertEquals("MEDIUM", m.effortValueFor(ProviderType.GEMINI))
        assertEquals("medium", m.effortValueFor(ProviderType.CLAUDE))
        assertNull(m.copy(effort = ReasoningEffort.NONE).effortValueFor(ProviderType.GEMINI))
    }
}
