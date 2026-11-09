package com.example.byokchat.ui

import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchTest {
    private fun conv(id: String, title: String, updatedAt: Long) = ConversationEntity(
        id = id, title = title, createdAt = 0, updatedAt = updatedAt,
    )

    private fun msg(id: String, cid: String, text: String, createdAt: Long, role: String = "user") = MessageEntity(
        id = id, conversationId = cid, turnId = id, seq = createdAt, role = role, text = text, createdAt = createdAt,
    )

    @Test fun groupsHitsByConversationNewestConversationFirst() {
        val convs = listOf(conv("c1", "Old", 1), conv("c2", "New", 2))
        val msgs = listOf(msg("m1", "c1", "hello world", 1), msg("m2", "c2", "say hello", 2), msg("m3", "c2", "HELLO again", 3))
        val g = buildSearchGroups(convs, msgs, "hello")
        assertEquals(listOf("c2", "c1"), g.map { it.conversationId })
        assertEquals(2, g[0].totalHits)
        assertEquals("m3", g[0].hits[0].messageId) // newest message first
    }

    @Test fun titleOnlyMatchHasNoHits() {
        val g = buildSearchGroups(listOf(conv("c1", "Recipes", 1)), listOf(msg("m1", "c1", "nothing", 1)), "recipe")
        assertEquals(1, g.size)
        assertTrue(g[0].titleMatch)
        assertTrue(g[0].hits.isEmpty())
    }

    @Test fun conversationsWithoutMessagesAreSkipped() {
        assertTrue(buildSearchGroups(listOf(conv("c1", "Recipes", 1)), emptyList(), "recipe").isEmpty())
    }

    @Test fun hitsPerGroupAreCappedButTotalCountsAll() {
        val msgs = (1..5).map { msg("m$it", "c1", "word $it", it.toLong()) }
        val g = buildSearchGroups(listOf(conv("c1", "T", 1)), msgs, "word", maxHitsPerGroup = 3)
        assertEquals(3, g[0].hits.size)
        assertEquals(5, g[0].totalHits)
    }

    @Test fun searchAllChatsGivesTheSameResultAsBuildSearchGroups() = runBlocking {
        val convs = listOf(conv("c1", "Old", 1), conv("c2", "New", 2), conv("c3", "Empty", 3))
        val msgs = listOf(
            msg("m1", "c1", "hello world", 1),
            msg("m2", "c2", "say hello", 2),
            msg("m3", "c2", "HELLO again", 3, role = "assistant"),
        )
        val byConv = msgs.groupBy { it.conversationId }
        val fast = searchAllChats(convs, { byConv[it].orEmpty() }, "hello")
        assertEquals(buildSearchGroups(convs, msgs, "hello"), fast)
    }

    @Test fun snippetHighlightsTheMatch() {
        val s = makeSnippet("one two three", "two")
        assertEquals("one two three", s.text)
        assertEquals(listOf(4..6), s.highlights)
    }
}
