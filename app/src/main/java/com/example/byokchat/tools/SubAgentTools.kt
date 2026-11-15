package com.example.byokchat.tools

import com.example.byokchat.chat.SubAgentRunner
import com.example.byokchat.chat.SubAgentStore
import com.example.byokchat.chat.SubRunStatus

/**
 * Tool that lets Main delegate a task to a sub-agent (Phase 2: synchronous
 * — Main waits for the result). The sub runs inside this tool call: a
 * detailed report comes back as the tool's result (Main reads it in the
 * next round), and a brief report is shown to the user as the sub-agent's
 * own message. Dismiss is implicit: when the tool returns, the run is
 * over and the sub leaves the member list (it stays in the team).
 *
 * Risk is READ: the sub's own tools are read-only (web search, web fetch),
 * so delegating never writes a file or changes anything outside the chat.
 */
object SubAgentTools {
    fun build(subRunner: () -> SubAgentRunner?): List<Tool> = listOf(
        Tool(
            uid = "sub.delegate",
            group = ToolGroup.AGENTS,
            risk = Risk.READ,
            defaultName = "delegate_to_subagent",
            defaultDescription = "Delegate a task to a sub-agent (a specialist team member). The sub-agent runs synchronously — you wait for it to finish. It returns a detailed report as this tool's result, and shows a brief report to the user as its own message. Available agents: research — web research (has web_search and fetch_url). Give a clear brief with exactly what to find and what to include. Also pass a short greeting the sub-agent uses when it joins the chat. Do not delegate tasks the agent's tools cannot do.",
            schema = schema {
                str("agent", "Which sub-agent to call: research (web research).", required = true, enum = listOf("research"))
                str("brief", "The task: exactly what the sub-agent should do and what to include in its report.", required = true)
                str("greeting", "Short greeting the sub-agent says to the user when it joins the chat (e.g. \"Hi, I'm Research — I'll look into this right away.\"). If omitted, the agent uses its default greeting.")
            },
        ) { _, args ->
            val runner = subRunner() ?: throw ToolException("Sub-agents are not available right now.")
            val agent = SubAgentStore.snapshot.byId(args.reqStr("agent"))
                ?: throw ToolException("Unknown sub-agent '${args.reqStr("agent")}'. Available: research.")
            val brief = args.reqStr("brief")
            val conversationId = currentConversationId() ?: throw ToolException("No conversation is open.")
            val turnId = currentTurnId() ?: throw ToolException("No turn is running.")
            val result = runner.run(agent, brief, args.str("greeting"), conversationId, turnId)
            if (result.status == SubRunStatus.FAILED) throw ToolException(result.detailed)
            result.detailed
        },
    )
}
