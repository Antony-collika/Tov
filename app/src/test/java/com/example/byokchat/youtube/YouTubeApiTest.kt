package com.example.byokchat.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseVideosListReadsStatisticsAndDuration() {
        val root = json.parseToJsonElement(
            """{"items":[{"id":"abc123def45","snippet":{"title":"Song A","channelId":"UCx","channelTitle":"Chan","description":"desc","publishedAt":"2026-01-02T03:04:05Z","thumbnails":{"medium":{"url":"https://i.yt/img.jpg"}}},"contentDetails":{"duration":"PT3M24S"},"statistics":{"viewCount":"1234","likeCount":"56","commentCount":"7"}}]}""",
        ).jsonObject
        val videos = parseVideosList(root)
        assertEquals(1, videos.size)
        val v = videos[0]
        assertEquals("abc123def45", v.videoId)
        assertEquals(204L, v.durationSeconds)
        assertEquals(1234L, v.viewCount)
        assertEquals(56L, v.likeCount)
        assertEquals(7L, v.commentCount)
        assertEquals("https://i.yt/img.jpg", v.thumbnailUrl)
    }

    @Test
    fun parseSearchListKeepsKindsAndNextPageToken() {
        val root = json.parseToJsonElement(
            """{"nextPageToken":"CAUQAA","items":[
              {"id":{"kind":"youtube#video","videoId":"abc123def45"},"snippet":{"title":"Song A","channelId":"UCx","channelTitle":"Chan","description":"d","publishedAt":"2026-01-02T00:00:00Z"}},
              {"id":{"kind":"youtube#channel","channelId":"UCx"},"snippet":{"title":"Chan","description":"d","publishedAt":"2026-01-01T00:00:00Z"}},
              {"id":{"kind":"youtube#playlist","playlistId":"PL1"},"snippet":{"title":"List","channelTitle":"Chan","description":"d","publishedAt":"2026-01-01T00:00:00Z"}}
            ]}""",
        ).jsonObject
        val results = parseSearchList(root)
        assertEquals("CAUQAA", results.nextPageToken)
        assertEquals(3, results.items.size)
        assertTrue(results.items[0] is SearchItem.Video)
        assertTrue(results.items[1] is SearchItem.Channel)
        assertTrue(results.items[2] is SearchItem.Playlist)
        // Bare search items have no enrichment yet.
        assertEquals(0L, (results.items[0] as SearchItem.Video).video.durationSeconds)
        assertNull((results.items[1] as SearchItem.Channel).channel.subscriberCount)
    }

    @Test
    fun parseCommentThreadsReadsRepliesObject() {
        val root = json.parseToJsonElement(
            """{"pageInfo":{"totalResults":42},"nextPageToken":"NEXT","items":[
              {"snippet":{"totalReplyCount":2,"topLevelComment":{"snippet":{"authorDisplayName":"Ana","textDisplay":"Great song","likeCount":10,"publishedAt":"2026-01-03T00:00:00Z"}},
              "replies":{"comments":[
                {"snippet":{"authorDisplayName":"Bo","textDisplay":"Agreed","likeCount":1,"publishedAt":"2026-01-03T01:00:00Z"}},
                {"snippet":{"authorDisplayName":"Cy","textDisplay":"+1","likeCount":0,"publishedAt":"2026-01-03T02:00:00Z"}}
              ]}}}
            ]}""",
        ).jsonObject
        val result = parseCommentThreads(root)
        assertEquals(42, result.totalResults)
        assertEquals("NEXT", result.nextPageToken)
        assertEquals(1, result.threads.size)
        val t = result.threads[0]
        assertEquals("Ana", t.top.author)
        assertEquals("Great song", t.top.text)
        assertEquals(2, t.replyCount)
        assertEquals(2, t.replies.size)
        assertEquals("Bo", t.replies[0].author)
    }

    @Test
    fun mapApiErrorNamesQuotaAndCommentsDisabled() {
        val api = YouTubeApi("key")
        val quota = api.mapApiError(403, """{"error":{"message":"Quota exceeded","errors":[{"reason":"quotaExceeded"}]}}""", "search")
        assertTrue(quota.contains("quota", ignoreCase = true))
        val disabled = api.mapApiError(403, """{"error":{"message":"disabled","errors":[{"reason":"commentsDisabled"}]}}""", "comments")
        assertTrue(disabled.contains("comments disabled", ignoreCase = true))
        val rate = api.mapApiError(429, "{}", "search")
        assertTrue(rate.contains("429"))
    }

    @Test
    fun formatSearchResultsShowsEnrichedFields() {
        val results = SearchResults(
            listOf(
                SearchItem.Video(
                    SearchVideo("abc123def45", "Song A", "UCx", "Chan", "A cheerful song", "2026-01-02T00:00:00Z", null, 204, 1234, 56, 7),
                ),
            ),
            "NEXT",
        )
        val text = formatSearchResults(results)
        assertTrue(text.contains("Song A"))
        assertTrue(text.contains("watch?v=abc123def45"))
        assertTrue(text.contains("3:24"))
        assertTrue(text.contains("1,234"))
        assertTrue(text.contains("Only fetch the next page if the user asks for more"))
    }
}
