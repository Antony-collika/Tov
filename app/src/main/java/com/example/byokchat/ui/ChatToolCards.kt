package com.example.byokchat.ui

import androidx.compose.runtime.Immutable
import com.example.byokchat.data.MessageEntity

/**
 * The tool results of a chat, keyed by tool-call id. Wrapped in a stable class so that a message whose
 * content did not change is not redrawn just because some other message in the chat changed.
 * The per-tool cards that used to render from this are gone (17/11/2026): each agent's work
 * now shows as inline lines ([WorkStream]), built from the same rows.
 */
@Immutable
data class ToolResults(val byCallId: Map<String, MessageEntity>)
