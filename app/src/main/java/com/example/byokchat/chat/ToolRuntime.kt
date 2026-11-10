package com.example.byokchat.chat

import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.ai.ToolDef

data class ToolOutcome(
    val uid: String,
    val resultText: String,
    val isError: Boolean = false,
)

/** Cầu nối giữa vòng chat và các công cụ. */
interface ToolRuntime {
    suspend fun definitions(): List<ToolDef>
    suspend fun execute(call: AiToolCall, turnId: String): ToolOutcome
    /** The run for this turn is over: forget turn-scoped permissions ("Allow all in this turn"). */
    fun endTurn(turnId: String)
    fun uidForName(name: String): String?
    fun nameForUid(uid: String): String?
    fun estimateToolTokens(): Int
}
