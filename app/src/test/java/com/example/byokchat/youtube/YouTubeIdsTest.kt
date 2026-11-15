package com.example.byokchat.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeIdsTest {

    @Test
    fun parseVideoIdFromLinksAndBareId() {
        assertEquals("dQw4w9WgXcQ", parseVideoId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", parseVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", parseVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s&list=PL123"))
        assertEquals("dQw4w9WgXcQ", parseVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", parseVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", parseVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(parseVideoId("https://www.youtube.com/channel/UC1234567890123456789012"))
        assertNull(parseVideoId("not a link"))
        assertNull(parseVideoId("https://www.youtube.com/watch?v=short"))
    }

    @Test
    fun parseChannelIdAndHandle() {
        val uc = "UC1234567890123456789012"
        assertEquals(uc, parseChannelId(uc))
        assertEquals(uc, parseChannelId("https://www.youtube.com/channel/$uc"))
        assertEquals("somechannel", parseChannelHandle("https://www.youtube.com/@somechannel"))
        assertEquals("somechannel", parseChannelHandle("https://www.youtube.com/c/somechannel"))
        assertNull(parseChannelHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun parsePlaylistId() {
        assertEquals("PL123456", parsePlaylistId("https://www.youtube.com/playlist?list=PL123456"))
        assertEquals("PL123456", parsePlaylistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123456"))
        assertNull(parsePlaylistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun parseYouTubeLinkPrefersVideoOverPlaylist() {
        val link = parseYouTubeLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123456")
        assertEquals(YouTubeLink.Video("dQw4w9WgXcQ"), link)
        assertEquals(
            YouTubeLink.Channel("UC1234567890123456789012"),
            parseYouTubeLink("https://www.youtube.com/channel/UC1234567890123456789012"),
        )
        assertEquals(
            YouTubeLink.ChannelHandle("somechannel"),
            parseYouTubeLink("https://www.youtube.com/@somechannel"),
        )
        assertEquals(YouTubeLink.Playlist("PL123456"), parseYouTubeLink("https://www.youtube.com/playlist?list=PL123456"))
        assertEquals(YouTubeLink.Unknown, parseYouTubeLink("hello"))
    }

    @Test
    fun iso8601DurationAndFormat() {
        assertEquals(204L, parseIso8601Duration("PT3M24S"))
        assertEquals(3723L, parseIso8601Duration("PT1H2M3S"))
        assertEquals(45L, parseIso8601Duration("PT45S"))
        assertEquals(86400L, parseIso8601Duration("P1DT0S"))
        assertEquals(0L, parseIso8601Duration("nonsense"))
        assertEquals(0L, parseIso8601Duration(null))
        assertEquals("3:24", formatDuration(204))
        assertEquals("1:02:03", formatDuration(3723))
        assertEquals("0:45", formatDuration(45))
        assertEquals("0:00", formatDuration(-5))
    }

    @Test
    fun sanitizeFileName() {
        assertEquals("My Song- live-", sanitizeFileName("My Song: live?"))
        assertEquals("video", sanitizeFileName("   "))
        assertEquals("a".repeat(80), sanitizeFileName("a".repeat(200)))
    }
}
