package com.example.byokchat.ui

import com.example.byokchat.ai.AiMessage
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.MetadataParams
import com.example.byokchat.ai.Role
import com.example.byokchat.chat.AssembledMessage
import com.example.byokchat.data.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Preview phải thể hiện đúng lần gửi tiếp theo sẽ gửi (quyết định Duy
 * 12/11/2026): suy luận chỉ được coi là "sẽ gửi" khi hãng thật sự gửi lại
 * nó — cùng luật với code gửi (ResponsesProvider cho OpenAI, OpenAiProvider
 * cho Custom).
 */
class RequestInspectorReasoningTest {
    private val reasoningReplay = """[{"type":"reasoning","id":"rs_1","encrypted_content":"abc"},{"type":"message","content":[{"type":"output_text","text":"hi"}]}]"""
    private val toolCallReplay = """[{"type":"reasoning","id":"rs_1"},{"type":"function_call","call_id":"call_1","name":"get_time","arguments":"{}"}]"""
    private val messageOnlyReplay = """[{"type":"message","content":[{"type":"output_text","text":"hi"}]}]"""

    private fun msg(
        reasoningText: String? = "some reasoning",
        reasoningReplay: String? = reasoningReplay,
        reasoningProvider: String? = "openai",
        toolCalls: List<AiToolCall> = emptyList(),
    ) = AiMessage(
        role = Role.ASSISTANT,
        text = "hi",
        toolCalls = toolCalls,
        reasoningText = reasoningText,
        reasoningReplay = reasoningReplay,
        reasoningProvider = reasoningProvider,
    )

    private fun status(
        m: AiMessage,
        sent: Boolean = true,
        metadata: MetadataParams = MetadataParams(),
        type: ProviderType = ProviderType.OPENAI,
        toolsOffered: Boolean = true,
    ) = reasoningSendStatus(AssembledMessage(m, sent), metadata, type, toolsOffered)

    @Test fun openAiToggleOnSendsReasoning() {
        assertEquals(ReasoningSendStatus.SENT, status(msg()))
    }

    @Test fun openAiToggleOffPlainTurnDoesNotSend() {
        assertEquals(
            ReasoningSendStatus.NOT_SENT_REASONING_OFF,
            status(msg(), metadata = MetadataParams(replayReasoning = false)),
        )
    }

    @Test fun openAiToggleOffToolCallTurnStillSends() {
        val call = AiToolCall(id = "call_1", name = "get_time", argsJson = "{}")
        assertEquals(
            ReasoningSendStatus.SENT,
            status(msg(toolCalls = listOf(call)), metadata = MetadataParams(replayReasoning = false)),
        )
    }

    @Test fun openAiToggleOffFunctionCallInReplayStillSends() {
        assertEquals(
            ReasoningSendStatus.SENT,
            status(msg(reasoningReplay = toolCallReplay), metadata = MetadataParams(replayReasoning = false)),
        )
    }

    @Test fun openAiReplayWithoutReasoningItemShowsNothing() {
        assertNull(status(msg(reasoningText = null, reasoningReplay = messageOnlyReplay)))
    }

    @Test fun openAiTextWithoutReplayIsNotSent() {
        assertEquals(
            ReasoningSendStatus.NOT_SENT_NO_REPLAY,
            status(msg(reasoningText = "some reasoning", reasoningReplay = null)),
        )
    }

    @Test fun openAiOtherProviderReasoningIsNotSent() {
        assertEquals(
            ReasoningSendStatus.NOT_SENT_OTHER_PROVIDER,
            status(msg(reasoningProvider = "custom")),
        )
    }

    @Test fun customWithToolsSendsReasoningText() {
        assertEquals(
            ReasoningSendStatus.SENT,
            status(
                msg(reasoningReplay = null, reasoningProvider = "custom"),
                type = ProviderType.CUSTOM,
                toolsOffered = true,
            ),
        )
    }

    @Test fun customWithoutToolsDoesNotSend() {
        assertEquals(
            ReasoningSendStatus.NOT_SENT_NO_TOOLS,
            status(
                msg(reasoningReplay = null, reasoningProvider = "custom"),
                type = ProviderType.CUSTOM,
                toolsOffered = false,
            ),
        )
    }

    @Test fun customOtherProviderReasoningIsNotSent() {
        assertEquals(
            ReasoningSendStatus.NOT_SENT_OTHER_PROVIDER,
            status(
                msg(reasoningReplay = null, reasoningProvider = "openai"),
                type = ProviderType.CUSTOM,
                toolsOffered = true,
            ),
        )
    }

    @Test fun customReplayOnlyShowsNothing() {
        assertNull(
            status(
                msg(reasoningText = null, reasoningReplay = reasoningReplay, reasoningProvider = "custom"),
                type = ProviderType.CUSTOM,
            ),
        )
    }

    @Test fun geminiShowsNothing() {
        assertNull(status(msg(), type = ProviderType.GEMINI))
    }

    @Test fun noReasoningShowsNothing() {
        assertNull(status(msg(reasoningText = null, reasoningReplay = null, reasoningProvider = null)))
    }

    @Test fun droppedMessageReasoningIsNotSent() {
        assertEquals(
            ReasoningSendStatus.NOT_SENT_MESSAGE_DROPPED,
            status(msg(), sent = false),
        )
    }

    @Test fun notSentReasonMentionsToggle() {
        assertEquals(
            "Resend saved reasoning is off",
            reasoningNotSentReason(ReasoningSendStatus.NOT_SENT_REASONING_OFF, null),
        )
    }

    @Test fun droppedReasonUsesDropReason() {
        assertEquals(
            "this message isn't sent (over budget)",
            reasoningNotSentReason(ReasoningSendStatus.NOT_SENT_MESSAGE_DROPPED, "over budget"),
        )
    }
}
