package com.example.byokchat.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho "Recently played" (menu ⋮, 2026-11-14): ghi, chống trùng liên tiếp, giới hạn 50, tách theo cuộc trò chuyện, lưu bền qua store mới (fake storage thay Room). */
class RecentlyPlayedTest {

    private fun track(name: String, source: PlayedSource = PlayedSource.YOUTUBE, key: String = name) =
        PlayedTrack(source, key, name)

    /** Fake cho RecentlyPlayedStorage: giữ nguyên danh sách đã lưu theo cuộc. */
    private class FakeStorage : RecentlyPlayedStorage {
        val saved = mutableMapOf<String, List<PlayedTrack>>()
        override suspend fun load(conversationId: String): List<PlayedTrack> =
            saved[conversationId].orEmpty()

        override suspend fun save(conversationId: String, tracks: List<PlayedTrack>) {
            saved[conversationId] = tracks
        }
    }

    @Test
    fun newestFirstAndPerConversation() = runBlocking {
        val store = RecentlyPlayedStore()
        store.record("conv-a", track("song-a"))
        store.record("conv-a", track("song-b"))
        store.record("conv-b", track("song-c"))
        assertEquals(listOf("song-b", "song-a"), store.list("conv-a").map { it.name })
        assertEquals(listOf("song-c"), store.list("conv-b").map { it.name })
        assertTrue(store.list("conv-unknown").isEmpty())
    }

    @Test
    fun consecutiveDuplicateRecordsOnce() = runBlocking {
        val store = RecentlyPlayedStore()
        store.record("conv", track("song-a"))
        store.record("conv", track("song-a"))
        assertEquals(listOf("song-a"), store.list("conv").map { it.name })
        // Cùng tên nhưng khác nguồn/khóa thì không phải trùng: ghi thêm.
        store.record("conv", track("song-a", PlayedSource.YOUTUBE, "other-key"))
        assertEquals(2, store.list("conv").size)
    }

    @Test
    fun consecutiveDuplicateDoesNotSaveAgain() = runBlocking {
        val storage = FakeStorage()
        val store = RecentlyPlayedStore(storage)
        store.record("conv", track("song-a"))
        val savedOnce = storage.saved.getValue("conv")
        store.record("conv", track("song-a"))
        assertEquals(savedOnce, storage.saved.getValue("conv"))
    }

    @Test
    fun nonConsecutiveDuplicateMovesToFrontInsteadOfDuplicating() = runBlocking {
        val store = RecentlyPlayedStore()
        store.record("conv", track("song-a"))
        store.record("conv", track("song-b"))
        store.record("conv", track("song-a"))
        // Ghi lại bài cũ thì bài đó lên đầu, bản cũ mất — danh sách không
        // bao giờ có hai mục trùng (khóa LazyColumn trong hộp thoại).
        assertEquals(listOf("song-a", "song-b"), store.list("conv").map { it.name })
    }

    @Test
    fun loadDedupsStaleDuplicatesFromStorage() = runBlocking {
        val storage = FakeStorage()
        storage.saved["conv"] = listOf(track("song-a"), track("song-b"), track("song-a"))
        val store = RecentlyPlayedStore(storage)
        store.load("conv")
        assertEquals(listOf("song-a", "song-b"), store.list("conv").map { it.name })
        // Danh sách đã lọc cũng được ghi lại xuống DB để lần sau sạch hẳn.
        assertEquals(listOf("song-a", "song-b"), storage.saved.getValue("conv").map { it.name })
    }

    @Test
    fun batchKeepsPlayOrderAndCapsAt50() = runBlocking {
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

    @Test
    fun savedListIsCappedAt50() = runBlocking {
        val storage = FakeStorage()
        val store = RecentlyPlayedStore(storage)
        store.recordAll("conv", (1..60).map { track("song-$it") })
        val saved = storage.saved.getValue("conv")
        assertEquals(RecentlyPlayedStore.MAX_ENTRIES, saved.size)
        assertEquals("song-1", saved.first().name)
        assertEquals("song-50", saved.last().name)
    }

    @Test
    fun historySurvivesFreshStoreInstance() = runBlocking {
        val storage = FakeStorage()
        val first = RecentlyPlayedStore(storage)
        first.record("conv", track("song-a"))
        first.record("conv", track("song-b"))
        // Store mới trong "phiên chạy" mới, cùng bộ nhớ lâu dài: cache ban
        // đầu trống, nạp lại được y nguyên thứ tự.
        val second = RecentlyPlayedStore(storage)
        assertTrue(second.list("conv").isEmpty())
        second.load("conv")
        assertEquals(listOf("song-b", "song-a"), second.list("conv").map { it.name })
        // Nạp lần hai không đổi gì.
        second.load("conv")
        assertEquals(listOf("song-b", "song-a"), second.list("conv").map { it.name })
    }

    @Test
    fun loadDoesNotClobberNewerInMemoryHistory() = runBlocking {
        val storage = FakeStorage()
        val store = RecentlyPlayedStore(storage)
        store.record("conv", track("song-a"))
        // Bộ nhớ lâu dài đổi sau lưng store (trong app thật không xảy ra,
        // vì mọi lần ghi đều qua store): lần nạp sau không được ghi đè
        // cache mới hơn.
        storage.saved["conv"] = listOf(track("stale"))
        store.load("conv")
        assertEquals(listOf("song-a"), store.list("conv").map { it.name })
    }
}
