package com.example.byokchat.ui

import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.displayTitle
import com.example.byokchat.util.TextUtil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A piece of text around a match, with the character ranges to highlight. */
data class Snippet(val text: String, val highlights: List<IntRange>)

data class SearchHit(val messageId: String, val role: String, val snippet: Snippet)

/** Matches inside one conversation. [titleMatch] = the conversation title itself matches. */
data class SearchGroup(
    val conversationId: String,
    val title: String,
    val titleMatch: Boolean,
    val hits: List<SearchHit>,
    val totalHits: Int,
)

/** Result of a search for [query]; kept together with the query so the UI can tell when it is out of date. */
data class SearchState(val query: String = "", val groups: List<SearchGroup> = emptyList())

fun makeSnippet(text: String, query: String): Snippet {
    val clean = text.replace('\n', ' ')
    val first = TextUtil.findAll(clean, query).firstOrNull() ?: return Snippet(clean.take(120), emptyList())
    val from = maxOf(0, first - 40)
    val to = minOf(clean.length, first + query.length + 80)
    val prefix = if (from > 0) "…" else ""
    val piece = clean.substring(from, to) + if (to < clean.length) "…" else ""
    val highlights = TextUtil.findAll(piece, query).map { s ->
        val start = prefix.length + s
        start until minOf(prefix.length + piece.length, start + query.length)
    }
    return Snippet(prefix + piece, highlights)
}

/**
 * Groups matches by conversation (newest conversation first). Searches the current text of messages, edits included.
 * Only conversations that have messages are searched. At most [maxHitsPerGroup] hits are kept per group
 * ([SearchGroup.totalHits] still counts all of them).
 */
fun buildSearchGroups(
    conversations: List<ConversationEntity>,
    messages: List<MessageEntity>,
    query: String,
    maxGroups: Int = 50,
    maxHitsPerGroup: Int = 3,
): List<SearchGroup> {
    if (query.isBlank()) return emptyList()
    val messagesByConv = messages.groupBy { it.conversationId }
    val out = mutableListOf<SearchGroup>()
    for (c in conversations.sortedByDescending { it.updatedAt }) {
        val group = searchGroupFor(c, messagesByConv[c.id].orEmpty(), query, maxHitsPerGroup) ?: continue
        out += group
        if (out.size >= maxGroups) break
    }
    return out
}

/**
 * The search result of ONE conversation, or null if nothing in it matches (or it has no messages:
 * empty drafts are never offered). [msgs] are that conversation's messages only.
 */
fun searchGroupFor(
    c: ConversationEntity,
    msgs: List<MessageEntity>,
    query: String,
    maxHitsPerGroup: Int = 3,
): SearchGroup? {
    if (msgs.isEmpty()) return null
    val title = displayTitle(c.title)
    val titleMatch = TextUtil.findAll(title, query).isNotEmpty()
    val matching = msgs.sortedByDescending { it.createdAt }.filter { TextUtil.findAll(it.text, query).isNotEmpty() }
    if (!titleMatch && matching.isEmpty()) return null
    return SearchGroup(
        conversationId = c.id,
        title = title,
        titleMatch = titleMatch,
        hits = matching.take(maxHitsPerGroup).map { SearchHit(it.id, it.role, makeSnippet(it.text, query)) },
        totalHits = matching.size,
    )
}

/**
 * Same result as [buildSearchGroups], but reads the messages one conversation at a time through
 * [loadMessages], so only one conversation's messages are in memory at once. Stops as soon as the
 * coroutine is cancelled (for example when the user types a new search).
 */
suspend fun searchAllChats(
    conversations: List<ConversationEntity>,
    loadMessages: suspend (String) -> List<MessageEntity>,
    query: String,
    maxGroups: Int = 50,
    maxHitsPerGroup: Int = 3,
): List<SearchGroup> {
    if (query.isBlank()) return emptyList()
    val out = mutableListOf<SearchGroup>()
    for (c in conversations.sortedByDescending { it.updatedAt }) {
        currentCoroutineContext().ensureActive()
        val group = searchGroupFor(c, loadMessages(c.id), query, maxHitsPerGroup) ?: continue
        out += group
        if (out.size >= maxGroups) break
    }
    return out
}
