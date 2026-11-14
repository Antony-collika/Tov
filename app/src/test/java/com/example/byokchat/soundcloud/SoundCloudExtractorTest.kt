package com.example.byokchat.soundcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * SoundCloud result mapping tests (2026-11-14). No live network: the
 * mapping is tested against real NewPipe StreamInfoItem objects, the same
 * class the search extractor returns.
 */
class SoundCloudExtractorTest {

    private fun trackItem(
        url: String = "https://soundcloud.com/some-artist/some-track",
        name: String = "Some Track",
        artist: String = "Some Artist",
        duration: Long = 204,
        type: StreamType = StreamType.AUDIO_STREAM,
    ): StreamInfoItem {
        val item = StreamInfoItem(1, url, name, type)
        item.uploaderName = artist
        item.duration = duration
        return item
    }

    @Test
    fun audioTrackMapsToTrack() {
        val track = mapSearchItem(trackItem())
        assertEquals("https://soundcloud.com/some-artist/some-track", track?.url)
        assertEquals("Some Track", track?.title)
        assertEquals("Some Artist", track?.artist)
        // 204L, not 204: track?.durationSeconds is Long?, so the int literal
        // would resolve to the assertEquals(Object, Object) overload and
        // compare Integer(204) with Long(204) — always unequal.
        assertEquals(204L, track?.durationSeconds)
    }

    @Test
    fun nonAudioItemsAndBlankUrlsAreSkipped() {
        assertNull(mapSearchItem(trackItem(type = StreamType.LIVE_STREAM)))
        assertNull(mapSearchItem(trackItem(type = StreamType.VIDEO_STREAM)))
        assertNull(mapSearchItem(trackItem(url = "")))
        assertNull(mapSearchItem(trackItem(url = "   ")))
    }

    @Test
    fun parseSoundCloudLinkAcceptsTrackLinksOnly() {
        assertEquals(
            "https://soundcloud.com/some-artist/some-track",
            parseSoundCloudLink("https://soundcloud.com/some-artist/some-track?si=abc123"),
        )
        assertEquals(
            "https://soundcloud.com/some-artist/some-track",
            parseSoundCloudLink("https://soundcloud.com/some-artist/some-track"),
        )
        assertNull(parseSoundCloudLink("https://soundcloud.com/some-artist")) // artist page, no track
        assertNull(parseSoundCloudLink("https://soundcloud.com"))
        assertNull(parseSoundCloudLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(parseSoundCloudLink("some track name"))
    }

    @Test
    fun formatResultsShowLinksButNeverStreamUrls() {
        val track = mapSearchItem(trackItem())!!
        val text = formatSoundCloudResults(listOf(track))
        assertTrue(text.contains("Some Track"))
        assertTrue(text.contains("Some Artist"))
        assertTrue(text.contains("3:24"))
        assertTrue(text.contains("https://soundcloud.com/some-artist/some-track"))
        assertTrue(text.contains("[track]"))
        assertEquals("No results found.", formatSoundCloudResults(emptyList()))
    }

    @Test
    fun mapExtractorErrorNamesGoPlusAndRateLimit() {
        assertTrue(
            SoundCloudExtractor.mapExtractorError(Exception("This content is SoundCloud Go+ only"))
                .contains("Go+", ignoreCase = true),
        )
        assertTrue(
            SoundCloudExtractor.mapExtractorError(Exception("HTTP 429 Too Many Requests"))
                .contains("429"),
        )
        assertTrue(
            SoundCloudExtractor.mapExtractorError(Exception("HTTP 404"))
                .contains("404"),
        )
        assertTrue(
            SoundCloudExtractor.mapExtractorError(Exception("boom"))
                .contains("Could not load this track from SoundCloud"),
        )
    }
}
