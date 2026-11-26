package com.example.byokchat.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * @mention (GĐ5, 26/11/2026): the user talks straight to one sub-agent
 * from the chat input, bypassing Main.
 *
 * - Routing: the FIRST token of the trimmed draft must be "@" + an
 *   agent's name or id (case-insensitive), followed by a space or the
 *   end of the text. Anything else — a leading @Name that is not a team
 *   member, a @Name with nothing after it, a @Name mid-message — is
 *   plain text and flows to Main exactly as today. More @mentions after
 *   the first token are plain text too: one agent per message.
 * - The brief the sub receives is the message with the leading @Name
 *   token stripped; the user message row keeps the text as typed.
 * - The run uses the same machinery as a delegate (own tools, caps,
 *   greeting, pill, journal) — only the starter is the user
 *   ([Mention.STARTED_BY]/[Mention.STARTED_BY_NAME]), and its outcome
 *   never wakes Main (the runner excludes user-started runs from its
 *   wake bookkeeping).
 * - The picker (typing "@") is a separate, wider aid: it offers an
 *   agent for the @word being typed anywhere in the draft, and only
 *   inserts "@Name " — routing still needs the leading token.
 */
object Mention {
    /** startedBy of a run the user started with @mention. */
    const val STARTED_BY = "user"

    /**
     * startedByName of a run the user started: "You", the name the
     * member list uses for the user (ui/GroupChat.kt).
     */
    const val STARTED_BY_NAME = "You"

    /** Title cap, same as conversation titles (take(40)). */
    const val TITLE_MAX_CHARS = 40
}

/** A routed @mention: which agent, and the brief it receives. */
data class MentionTarget(val agent: SubAgentConfig, val brief: String)

/**
 * The @mention at the start of a message, if any. The name match is
 * case-insensitive and accepts the agent's name or id; when two names
 * match ("Bob" and "Bob Smith"), the longest wins. Null when the text
 * does not start with a known @Name followed by a space (or the end
 * of the text), or when nothing but the @Name follows it.
 */
fun parseMention(text: String, team: SubAgentTeam): MentionTarget? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("@")) return null
    val afterAt = trimmed.drop(1)
    if (afterAt.isBlank()) return null
    val match = team.agents
        .flatMap { agent -> listOf(agent.name, agent.id).map { key -> agent to key } }
        .filter { (_, key) ->
            key.isNotBlank() && afterAt.startsWith(key, ignoreCase = true) &&
                (afterAt.length == key.length || afterAt[key.length].isWhitespace())
        }
        .maxByOrNull { (_, key) -> key.length }
        ?: return null
    val (agent, key) = match
    val brief = afterAt.drop(key.length).trim()
    if (brief.isBlank()) return null
    return MentionTarget(agent, brief)
}

/** What send() does with a draft: route it, refuse it, or send it to Main. */
sealed interface MentionDecision {
    /** No leading @mention — the normal message to Main. */
    data object NoMention : MentionDecision

    /** Run this agent directly with this brief. */
    data class Route(val agent: SubAgentConfig, val brief: String) : MentionDecision

    /** The named agent is turned off — tell the user, keep the draft. */
    data class AgentOff(val agent: SubAgentConfig) : MentionDecision

    /** The named agent is already running a task — tell the user, keep the draft. */
    data class AgentBusy(val agent: SubAgentConfig) : MentionDecision
}

/**
 * Decide what a draft does. [isBusy] answers whether the agent is
 * already running a task in this conversation (the runner's
 * [SubAgentRunner.activeRun]); it is a parameter so the decision stays
 * pure and unit-testable.
 */
fun decideMention(
    text: String,
    team: SubAgentTeam,
    isBusy: (String) -> Boolean,
): MentionDecision {
    val target = parseMention(text, team) ?: return MentionDecision.NoMention
    if (!target.agent.enabled) return MentionDecision.AgentOff(target.agent)
    if (isBusy(target.agent.id)) return MentionDecision.AgentBusy(target.agent)
    return MentionDecision.Route(target.agent, target.brief)
}

/**
 * The one-line title a @mention run carries: the brief's first
 * non-blank line, capped like conversation titles. The brief is never
 * blank here (parseMention guarantees it); the agent name is only a
 * defensive fallback.
 */
fun mentionTitle(brief: String, agent: SubAgentConfig): String =
    brief.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(Mention.TITLE_MAX_CHARS)
        ?: agent.name

/**
 * The @word being typed at the end of the draft, without the "@" —
 * null when the draft does not end with an @word. "@" alone gives an
 * empty query, which matches every agent.
 */
fun mentionQuery(draft: String): String? {
    val tail = draft.substringAfterLast(' ').substringAfterLast('\n')
    return if (tail.startsWith("@")) tail.drop(1) else null
}

/**
 * Agents matching the typed query: name or id starts with it,
 * case-insensitive. An empty query matches all. Off agents stay in
 * the list — the picker shows them dimmed, never hides them.
 */
fun filterMentionAgents(query: String, agents: List<SubAgentConfig>): List<SubAgentConfig> {
    if (query.isEmpty()) return agents
    return agents.filter {
        it.name.startsWith(query, ignoreCase = true) || it.id.startsWith(query, ignoreCase = true)
    }
}

/**
 * Replace the @word at the end of the draft with "@Name " — and put
 * the cursor at the very end, so the user types on straight away.
 * (26/11/2026: with a plain-String draft the field kept the old
 * cursor offset, so "@" + pick Bob gave "@|Bob" and the user had to
 * tap to move the cursor. Carrying the selection in the returned
 * TextFieldValue fixes that.) A draft with no trailing @word is
 * returned unchanged, selection included.
 */
fun insertMention(draft: TextFieldValue, name: String): TextFieldValue {
    val text = draft.text
    val tail = text.substringAfterLast(' ').substringAfterLast('\n')
    if (!tail.startsWith("@")) return draft
    val inserted = text.dropLast(tail.length) + "@$name "
    return TextFieldValue(inserted, TextRange(inserted.length))
}
