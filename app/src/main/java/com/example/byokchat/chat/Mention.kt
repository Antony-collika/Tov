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

/** What regenerating a turn does: re-run the sub-agent it mentions, or Main. */
sealed interface RegenDecision {
    /** No mention — Main's turn, exactly as today. */
    data object RunMain : RegenDecision

    /** Re-run this agent with this brief. */
    data class RunSub(val agent: SubAgentConfig, val brief: String) : RegenDecision

    /** The mentioned agent is turned off — tell the user, run nothing. */
    data class AgentOff(val agent: SubAgentConfig) : RegenDecision

    /** The mentioned agent is already running a task — tell the user, run nothing. */
    data class AgentBusy(val agent: SubAgentConfig) : RegenDecision

    /** The stored mention points at an agent no longer in the team — tell the user, run nothing. */
    data class AgentGone(val agentId: String) : RegenDecision
}

/**
 * The brief for a stored mention: the user text with the leading
 * "@Name" token stripped (the agent's name or id, case-insensitive,
 * word boundary — the same match as [parseMention]). Null when the text
 * no longer starts with that agent's token (e.g. edited outside the
 * app) or nothing but the token follows it; the caller then falls back
 * to the whole text as the brief.
 */
fun briefAfterMention(text: String, agent: SubAgentConfig): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("@")) return null
    val afterAt = trimmed.drop(1)
    val key = listOf(agent.name, agent.id).firstOrNull { k ->
        k.isNotBlank() && afterAt.startsWith(k, ignoreCase = true) &&
            (afterAt.length == k.length || afterAt[k.length].isWhitespace())
    } ?: return null
    return afterAt.drop(key.length).trim().ifBlank { null }
}

/**
 * Decide what regenerating a turn does. [userText] is the turn's user
 * text (after any edit), [storedMentionAgentId] the mention saved on
 * the user row, [textEdited] whether this regenerate carries new text
 * (Edit & regenerate — plain Regenerate / Retry pass false).
 * [isBusy] answers whether the agent is already running a task in this
 * conversation (the runner's [SubAgentRunner.activeRun]); it is a
 * parameter so the decision stays pure and unit-testable.
 *
 * - Edited text is the source of truth: it is re-read with
 *   [parseMention] — same agent → same agent, new brief; a different
 *   @Name → that agent; no leading @Name → [RegenDecision.RunMain]
 *   (the mention is dropped; the caller clears the stored state).
 * - Plain Regenerate / Retry reads the current text first (it may have
 *   changed by plain Edit or an outside edit since the state was
 *   saved); otherwise the stored state decides, so a turn still belongs
 *   to its agent even if the @ token is gone from the text.
 * - Off / busy / gone never silently falls back to Main or another
 *   agent: the caller shows one short notice and runs nothing.
 */
fun decideRegenerate(
    userText: String,
    storedMentionAgentId: String?,
    textEdited: Boolean,
    team: SubAgentTeam,
    isBusy: (String) -> Boolean,
): RegenDecision {
    if (textEdited) {
        val target = parseMention(userText, team) ?: return RegenDecision.RunMain
        if (!target.agent.enabled) return RegenDecision.AgentOff(target.agent)
        if (isBusy(target.agent.id)) return RegenDecision.AgentBusy(target.agent)
        return RegenDecision.RunSub(target.agent, target.brief)
    }
    val target = parseMention(userText, team)
    if (target != null) {
        if (!target.agent.enabled) return RegenDecision.AgentOff(target.agent)
        if (isBusy(target.agent.id)) return RegenDecision.AgentBusy(target.agent)
        return RegenDecision.RunSub(target.agent, target.brief)
    }
    val storedId = storedMentionAgentId ?: return RegenDecision.RunMain
    val agent = team.byId(storedId) ?: return RegenDecision.AgentGone(storedId)
    if (!agent.enabled) return RegenDecision.AgentOff(agent)
    if (isBusy(agent.id)) return RegenDecision.AgentBusy(agent)
    val brief = briefAfterMention(userText, agent) ?: userText.trim()
    return RegenDecision.RunSub(agent, brief)
}

/**
 * End offset of the leading "@Name" token in [text] (first whitespace,
 * or the end of the text) — the span the user bubble styles for a
 * stored mention. Null when the text does not start with "@", or the
 * token is just "@" on its own.
 */
fun mentionTokenEnd(text: String): Int? {
    if (!text.startsWith("@")) return null
    val end = text.indexOfFirst { it.isWhitespace() }.let { if (it == -1) text.length else it }
    return if (end > 1) end else null
}

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
