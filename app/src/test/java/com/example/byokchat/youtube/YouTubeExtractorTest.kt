package com.example.byokchat.youtube

import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeExtractorTest {

    @Test
    fun mapExtractorErrorNamesBotCheckAndRateLimit() {
        assertTrue(
            YouTubeExtractor.mapExtractorError(Exception("Sign in to confirm you're not a bot"))
                .contains("bot check", ignoreCase = true),
        )
        assertTrue(
            YouTubeExtractor.mapExtractorError(Exception("HTTP 429 Too Many Requests"))
                .contains("429"),
        )
        assertTrue(
            YouTubeExtractor.mapExtractorError(Exception("HTTP 403"))
                .contains("403"),
        )
        assertTrue(
            YouTubeExtractor.mapExtractorError(Exception("boom"))
                .contains("Could not load this video"),
        )
    }
}
