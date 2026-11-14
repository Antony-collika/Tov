package com.example.byokchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho "Recently played" (menu ⋮, 2026-11-14): ghi, chống trùng liên tiếp, giới hạn 50, tách theo cuộc trò chuyện. */
class RecentlyPlayedTest {

    private fun track(name: String, source: PlayedSource = PlayedSource.YOUTUBE, key: String = name) =
        PlayedTrack(source, key, name)

    @Test
    fun newestFirstAndPerConversation() {
        val store = RecentlyPlayedStore()
        store.record("conv-a", track("song-a"))
        store.record("conv-a", track("song-b"))
        store.record("conv-b", track("song-c"))
        assertEquals(listOf("song-b", "song-a"), store.list("conv-a").map { it.name })
        assertEquals(listOf("song-c"), store.list("conv-b").map { it.name })
        assertTrue(store.list("conv-unknown").isEmpty())
    }

    @Test
    fun consecutiveDuplicateRecordsOnce() {
        val store = RecentlyPlayedStore()
        store.record("conv", track("song-a"))
        store.record("conv", track("song-a"))
        assertEquals(listOf("song-a"), store.list("conv").map { it.name })
        // Cùng tên nhưng khác nguồn/khóa thì không phải trùng: ghi thêm.
        store.record("conv", track("song-a", PlayedSource.NCT, "other-key"))
        assertEquals(2, store.list("conv").size)
    }

    @Test
    fun nonConsecutiveDuplicateRecordsAgain() {
        val store = RecentlyPlayedStore()
        store.record("conv", track("song-a"))
        store.record("conv", track("song-b"))
        store.record("conv", track("song-a"))
        assertEquals(listOf("song-a", "song-b", "song-a"), store.list("conv").map { it.name })
    }

    @Test
    fun batchKeepsPlayOrderAndCapsAt50() {
        val store = RecentlyPlayedStore()
        val batch = (1..3).map { track("batch-$it") }
        store.recordAll("conv", batch)
        assertEquals(listOf("batch-1", "batch-2", "batch-3"), store.list("conv").map { it.name })
        val many = (1..60).map { track("song-$it") }
        store.recordAll("conv", many)
        val listed = store.list("conv")
        assertEquals(RecentlyPlayedStore.MAX_ENTRIES, listed.size)
        assertEquals("song-1", listed.first().name)
        assertEquals("song-50", listed.last().name)
    }
}
