package com.example.byokchat.soundcloud

import com.example.byokchat.tools.ToolException
import com.example.byokchat.youtube.YouTubeExtractor
import com.example.byokchat.youtube.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * SoundCloud via NewPipeExtractor 0.26.5 (2026-11-14). SoundCloud is a
 * supported NewPipe service with full streams and no API key, so — unlike
 * YouTube search, which must use the official API — search here also goes
 * through the extractor.
 *
 * Reuses the extractor setup from YouTubeExtractor (same OkHttpDownloader,
 * same NewPipe.init — never a second init) and its download helper.
 * Method signatures were verified against the real 0.26.5 jar:
 * NewPipe.getService("SoundCloud"), service.getSearchExtractor(query),
 * SearchExtractor.fetchPage() / initialPage.items, StreamInfoItem
 * url/name/uploaderName/duration, StreamExtractor.audioStreams.
 */

data class SoundCloudTrack(
    /** Durable link, e.g. https://soundcloud.com/artist/track */
    val url: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
)

data class ResolvedSoundCloudTrack(
    val track: SoundCloudTrack,
    val audioStreams: List<AudioStream>,
)

// ---------- Pure mapping (testable without network) ----------

/** One search-result item -> a playable track, or null when it is not an audio track. */
fun mapSearchItem(item: InfoItem): SoundCloudTrack? {
    val s = item as? StreamInfoItem ?: return null
    if (s.streamType != StreamType.AUDIO_STREAM) return null
    val url = s.url.orEmpty().ifBlank { return null }
    return SoundCloudTrack(
        url = url,
        title = s.name.orEmpty().ifBlank { url },
        artist = s.uploaderName.orEmpty(),
        durationSeconds = s.duration.coerceAtLeast(0),
    )
}

/** A SoundCloud track link, query/fragment stripped. Null when not a track link. */
fun parseSoundCloudLink(input: String): String? {
    val s = input.trim()
    if (!s.contains("soundcloud.com")) return null
    val clean = s.substringBefore('?').substringBefore('#').trimEnd('/')
    if (!clean.startsWith("http")) return null
    val path = clean.substringAfter("://", "").substringAfter('/', "")
    // A track link is /<user>/<track> — a bare artist page is not playable here.
    if (path.split('/').filter { it.isNotEmpty() }.size < 2) return null
    return clean
}

/** Model-facing search results: durable track links only, never a stream URL. */
fun formatSoundCloudResults(tracks: List<SoundCloudTrack>): String {
    if (tracks.isEmpty()) return "No results found."
    val lines = mutableListOf<String>()
    tracks.forEachIndexed { i, t ->
        lines += "${i + 1}. [track] ${t.title}\n" +
            "   ${t.url}\n" +
            "   Artist: ${t.artist.ifBlank { "—" }} | Duration: ${if (t.durationSeconds > 0) formatDuration(t.durationSeconds) else "—"}"
    }
    return lines.joinToString("\n")
}

// ---------- Extractor ----------

object SoundCloudExtractor {

    /** Keyword search, at most [maxResults] tracks (audio tracks only). */
    suspend fun search(query: String, maxResults: Int = 20): List<SoundCloudTrack> =
        withContext(Dispatchers.IO) {
            YouTubeExtractor.ensureInitialized()
            try {
                val service = NewPipe.getService("SoundCloud")
                val extractor = service.getSearchExtractor(query)
                extractor.fetchPage()
                extractor.initialPage.items.mapNotNull { mapSearchItem(it) }
                    .take(maxResults.coerceIn(1, 50))
            } catch (e: Exception) {
                throw ToolException(mapExtractorError(e))
            }
        }

    /** Resolve one track link to its title/artist/duration + audio streams. */
    suspend fun fetch(url: String): ResolvedSoundCloudTrack = withContext(Dispatchers.IO) {
        YouTubeExtractor.ensureInitialized()
        try {
            val extractor = NewPipe.getServiceByUrl(url).getStreamExtractor(url)
            extractor.fetchPage()
            val title = runCatching { extractor.name }.getOrNull().orEmpty()
            ResolvedSoundCloudTrack(
                track = SoundCloudTrack(
                    url = url,
                    title = title,
                    artist = runCatching { extractor.uploaderName }.getOrNull().orEmpty(),
                    durationSeconds = runCatching { extractor.length }.getOrDefault(0),
                ),
                audioStreams = runCatching { extractor.audioStreams }.getOrDefault(emptyList()),
            )
        } catch (e: Exception) {
            throw ToolException(mapExtractorError(e))
        }
    }

    fun mapExtractorError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("bot", ignoreCase = true) || msg.contains("captcha", ignoreCase = true) ->
                "SoundCloud blocked this request with a bot check, so the track could not be loaded. " +
                    "Try another track, or try again later."
            msg.contains("Go+", ignoreCase = true) || msg.contains("Go Plus", ignoreCase = true) ->
                "This track needs SoundCloud Go+ and cannot be played or downloaded."
            msg.contains("429", ignoreCase = true) || msg.contains("Too Many Requests", ignoreCase = true) ->
                "SoundCloud is rate-limiting requests (429). Wait a moment and try again."
            msg.contains("403", ignoreCase = true) ->
                "SoundCloud refused this track (403). It may be private or region-blocked."
            msg.contains("404", ignoreCase = true) ->
                "SoundCloud could not find this track (404). It may have been removed."
            else ->
                "Could not load this track from SoundCloud: ${msg.ifBlank { e.javaClass.simpleName }}. Try again."
        }
    }
}
