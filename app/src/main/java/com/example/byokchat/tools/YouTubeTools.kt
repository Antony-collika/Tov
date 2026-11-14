package com.example.byokchat.tools

import com.example.byokchat.youtube.CommentsResult
import com.example.byokchat.youtube.SearchItem
import com.example.byokchat.youtube.SearchResults
import com.example.byokchat.youtube.YouTubeApi
import com.example.byokchat.youtube.YouTubeExtractor
import com.example.byokchat.youtube.YouTubeLink
import com.example.byokchat.youtube.formatComments
import com.example.byokchat.youtube.formatSearchResults
import com.example.byokchat.youtube.parseVideoId
import com.example.byokchat.youtube.parseYouTubeLink
import com.example.byokchat.youtube.sanitizeFileName
import com.example.byokchat.youtube.selectSubtitleTrack
import com.example.byokchat.youtube.subtitleFileSuffix
import com.example.byokchat.youtube.webVttToPlainText
import com.example.byokchat.youtube.webVttToSrt
import com.example.byokchat.util.humanSize
import kotlinx.serialization.json.JsonObject

fun youtubeTool(
    uid: String,
    group: ToolGroup,
    risk: Risk,
    name: String,
    description: String,
    schema: JsonObject,
    needsWorkspace: Boolean = false,
    isAvailable: (ToolEnv) -> Boolean = { true },
    preview: (suspend (ToolEnv, JsonObject) -> String)? = null,
    run: suspend (ToolEnv, JsonObject) -> String,
) = Tool(uid, group, risk, name, description, schema, needsWorkspace, null, isAvailable, preview, run)

/**
 * YouTube tools (2026-11-13, FINAL design). Four new tools only:
 * youtube_search, youtube_comments, youtube_download, youtube.media.play
 * (the last lives in MediaTools, next to local.media.play).
 * No youtube_info and no in-chat transcript tool — search returns enough
 * to choose, and transcripts are download-only because they are long.
 *
 * - youtube_search / youtube_comments use the official YouTube Data API v3
 *   (key in Settings → Web search) and are not offered without a key.
 * - youtube_download / youtube.media.play use NewPipeExtractor only, so
 *   they need no API key — but they do need a Playground folder.
 */
object YouTubeTools {
    fun build(): List<Tool> = listOf(
        youtubeTool(
            "youtube_search", ToolGroup.MEDIA, Risk.READ, "youtube_search",
            "Search YouTube by keyword, or get the details of a YouTube link the user sent, via the official YouTube Data API. Use it when the user wants something without naming a specific video, for example \"a cheerful song by singer Nguyen Van A\", or when the user sends a YouTube link, or when the user asks to check a channel or play the newest video in a channel. Each result already has everything needed to choose: link, video ID, title, channel name, description, duration, view count, like count, comment count. Return at most 50 results per page, default 20. Fetch more only if the user asks for more, using page_token. If the user sends a channel or playlist link, that link alone is enough — pass it as q and nothing else. To list videos in a channel, pass its channel_id with type video and order date and no q. No other tool is needed to get the details of a video you already found. Do not search again when you already have search results.",
            schema {
                str("q", "Search keywords, or a full YouTube link (video, playlist or channel). Not needed when channel_id is given.")
                str("type", "video, channel, playlist, or several joined with commas. Default: all three.")
                str("channel_id", "Only search videos in this channel (from a previous channel search).")
                int("max_results", "Results per page, 1-50, default 20. Fetch more only if the user asks for more.")
                str("order", "relevance (default), date, viewCount, rating, title, videoCount.", enum = listOf("relevance", "date", "viewCount", "rating", "title", "videoCount"))
                str("page_token", "Next page token from a previous search, only when the user asks for more.")
            },
            isAvailable = { it.settings.youtubeApiKey.value.isNotBlank() },
            preview = { _, a -> "Search YouTube: ${a.str("q")?.takeIf { it.isNotBlank() } ?: a.str("channel_id")?.let { "channel $it" } ?: ""}" },
        ) { env, a ->
            val api = requireApi(env)
            val q = a.str("q")?.trim().orEmpty()
            val max = (a.int("max_results") ?: 20).coerceIn(1, 50)
            val order = a.str("order") ?: "relevance"
            val pageToken = a.str("page_token")
            when (val link = parseYouTubeLink(q)) {
                is YouTubeLink.Video -> {
                    val video = api.videoById(link.videoId)
                        ?: return@youtubeTool "No YouTube video was found with ID '${link.videoId}'. It may have been removed."
                    formatSearchResults(SearchResults(listOf(SearchItem.Video(video)), null))
                }
                is YouTubeLink.Playlist -> {
                    val playlist = api.playlistById(link.playlistId)
                        ?: return@youtubeTool "No YouTube playlist was found with ID '${link.playlistId}'. It may have been removed."
                    val videos = api.playlistVideos(link.playlistId, max, pageToken)
                    val head = formatSearchResults(SearchResults(listOf(SearchItem.Playlist(playlist)), null))
                    if (videos.items.isEmpty()) head else head + "\nVideos in this playlist:\n" + formatSearchResults(videos)
                }
                is YouTubeLink.Channel -> channelResults(api, link.channelId, max, pageToken)
                is YouTubeLink.ChannelHandle -> {
                    val channel = api.channelByHandle(link.handle)
                        ?: return@youtubeTool "No YouTube channel was found for '@${link.handle}'."
                    channelResults(api, channel.channelId, max, pageToken)
                }
                is YouTubeLink.Unknown -> {
                    val channelId = a.str("channel_id")
                    if (q.isBlank() && channelId.isNullOrBlank()) {
                        throw ToolException("Give search keywords, a YouTube link, or a channel_id to list a channel's videos.")
                    }
                    val results = api.search(q, a.str("type"), channelId, max, order, pageToken)
                    if (results.items.isEmpty()) {
                        if (q.isBlank()) "No videos found in this channel." else "No results for '$q'."
                    } else formatSearchResults(results)
                }
            }
        },

        youtubeTool(
            "youtube_comments", ToolGroup.MEDIA, Risk.READ, "youtube_comments",
            "Read the comments of a YouTube video: author, text, like count, date, with up to 5 replies per thread. Default 20 threads per call, at most 100 per call. Fetch more only if the user asks for more, using page_token. Use it when the user asks what people say about a video. If comments are disabled on the video, the tool will say so clearly, do not invent comments.",
            schema {
                str("video", "Video ID or full YouTube link.", required = true)
                int("max_results", "Comment threads per call, 1-100, default 20. Fetch more only if the user asks for more.")
                str("order", "relevance (best comments, default) or time (newest).", enum = listOf("relevance", "time"))
                str("page_token", "Next page token from a previous call, only when the user asks for more.")
                str("search_terms", "Only show comments containing this text.")
            },
            isAvailable = { it.settings.youtubeApiKey.value.isNotBlank() },
            preview = { _, a -> "Comments for: ${a.reqStr("video")}" },
        ) { env, a ->
            val api = requireApi(env)
            val videoId = parseVideoId(a.reqStr("video"))
                ?: throw ToolException("Give a YouTube video ID or link, e.g. 'https://www.youtube.com/watch?v=...'.")
            val max = (a.int("max_results") ?: 20).coerceIn(1, 100)
            val result: CommentsResult = api.comments(
                videoId, max, a.str("order") ?: "relevance", a.str("page_token"), a.str("search_terms"),
            )
            formatComments(result)
        },

        youtubeTool(
            "youtube_download", ToolGroup.MEDIA, Risk.WRITE, "youtube_download",
            "Download one file from one YouTube video: audio, video, or transcript, and save it to a path inside the Playground folders the user granted. The path is relative to the virtual root, and its first segment must be a granted folder name, for example \"Nhac/YouTube/\". Download one video at a time. If the target folder is not clear, ask the user which granted folder to use, do not guess. Missing subfolders are created automatically. For a transcript, the app lists the available subtitle tracks first and picks one by itself, in this order: manual in the requested language, auto-generated in the requested language, manual in the original language, auto-generated in the original language, manual in English, manual in Vietnamese, auto-generated in English, auto-generated in Vietnamese, then any manual track, then any auto-generated track. Auto-translated tracks are used only as a last resort. The tool reports which language and type it actually took. Read the returned file first; do not call again with a different language just because the returned language differs from the video language. Transcripts are saved as plain text (txt) by default, without timestamps; pass format srt only when the user asks for timestamps. Use it when the user asks to save a file, for example \"download the audio of this song to Nhac\". Do not use it to listen right away without saving — use youtube.media.play. A path outside the granted folders will be refused, do not save it elsewhere instead.",
            schema {
                str("video", "Video ID or full YouTube link.", required = true)
                str("kind", "audio, video, or transcript.", required = true, enum = listOf("audio", "video", "transcript"))
                str("path", "Destination in the Playground: a folder (the app names the file) or a full file path. The first segment must be a granted folder name.", required = true)
                str("language", "Subtitle language for kind=transcript, e.g. vi or en. Only set it when the user names a language; otherwise the tool picks the best available track.")
                str("format", "Transcript format for kind=transcript: txt (default, plain text without timestamps, for reading and analysis) or srt (with timestamps). Only set it when the user names a format; otherwise txt is used.", enum = listOf("txt", "srt"))
                str("quality", "Video quality, e.g. 720p (default) or best. Audio is always the best quality.")
            },
            needsWorkspace = true,
            preview = { _, a -> "Download ${a.str("kind") ?: "?"} to ${a.str("path") ?: "?"}" },
        ) { env, a ->
            val kind = a.reqStr("kind").trim().lowercase()
            if (kind !in setOf("audio", "video", "transcript")) {
                throw ToolException("kind must be audio, video or transcript.")
            }
            val videoId = parseVideoId(a.reqStr("video"))
                ?: throw ToolException("Give a YouTube video ID or link, e.g. 'https://www.youtube.com/watch?v=...'.")
            val rawPath = a.reqStr("path").trim()
            // Validate the path against the Playground virtual root first:
            // unknown first segment errors back with the granted folder list.
            env.fs.locate(rawPath)
            val resolved = YouTubeExtractor.fetch(videoId)
            val title = resolved.title.ifBlank { videoId }
            val baseName = sanitizeFileName(title)
            when (kind) {
                "transcript" -> downloadTranscript(env, rawPath, baseName, resolved, a.str("language"), a.str("format"))
                "audio" -> {
                    val stream = YouTubeExtractor.bestAudioStream(resolved.audioStreams)
                        ?: throw ToolException("This video has no audio stream to download.")
                    val fileName = "$baseName.${YouTubeExtractor.audioExtension(stream.format)}"
                    val target = resolveTargetFile(env, rawPath, fileName)
                    downloadStreamTo(env, stream.content, target)
                    "Saved audio '${title}' to '$target' (${humanSize(env.fs.sizeOf(env.fs.require(target)))})."
                }
                else -> {
                    val stream = YouTubeExtractor.videoStreamForQuality(resolved.videoStreams, a.str("quality"))
                        ?: throw ToolException("This video has no video stream to download.")
                    val fileName = "$baseName.${YouTubeExtractor.videoExtension(stream.format)}"
                    val target = resolveTargetFile(env, rawPath, fileName)
                    downloadStreamTo(env, stream.content, target)
                    "Saved video '${title}' (${stream.resolution}) to '$target' (${humanSize(env.fs.sizeOf(env.fs.require(target)))})."
                }
            }
        },
    )

    private fun requireApi(env: ToolEnv): YouTubeApi {
        val key = env.settings.youtubeApiKey.value
        if (key.isBlank()) {
            throw ToolException(
                "No YouTube Data API key has been entered. Enter it in Settings → Web search, then try again.",
            )
        }
        return YouTubeApi(key)
    }

    /** Channel info + its newest videos, in one tool call (checking a channel). */
    private suspend fun channelResults(api: YouTubeApi, channelId: String, max: Int, pageToken: String?): String {
        val channel = api.channelById(channelId)
            ?: return "No YouTube channel was found with ID '$channelId'."
        val head = formatSearchResults(SearchResults(listOf(SearchItem.Channel(channel)), null))
        val videos = api.search(q = "", channelId = channelId, maxResults = max, order = "date", pageToken = pageToken)
        return if (videos.items.isEmpty()) head
        else head + "\nNewest videos in this channel:\n" + formatSearchResults(videos)
    }

    /** Folder target (app names the file) or full file path -> the final file path. */
    private fun resolveTargetFile(env: ToolEnv, rawPath: String, fileName: String): String {
        val trimmed = rawPath.trimEnd('/')
        val last = trimmed.substringAfterLast('/')
        val isFolder = rawPath.endsWith("/") ||
            runCatching { env.fs.find(rawPath) }.getOrNull()?.let { env.fs.isDir(it) } == true ||
            !last.contains('.')
        val finalPath = if (isFolder) "$trimmed/$fileName" else trimmed
        // The final file must sit inside a granted folder (never at the virtual root).
        env.fs.checkOp(PlaygroundOp.WRITE_FILE, finalPath)
        return finalPath
    }

    private suspend fun downloadTranscript(
        env: ToolEnv,
        rawPath: String,
        baseName: String,
        resolved: com.example.byokchat.youtube.ResolvedStreams,
        language: String?,
        format: String?,
    ): String {
        val track = selectSubtitleTrack(resolved.subtitles, language)
            ?: throw ToolException("This video has no subtitles to download.")
        val vtt = YouTubeExtractor.fetchText(track.contentUrl)
        // txt is the default: plain text without timestamps, for reading and
        // analysis. srt keeps the timestamps; only when the user asks for it.
        val ext = if (format?.trim()?.lowercase() == "srt") "srt" else "txt"
        val text = if (ext == "srt") webVttToSrt(vtt) else webVttToPlainText(vtt)
        if (text.isBlank()) throw ToolException("The subtitles for this video could not be read. Try again.")
        val fileName = "$baseName-${subtitleFileSuffix(track)}.$ext"
        val target = resolveTargetFile(env, rawPath, fileName)
        env.fs.writeText(target, text)
        val kind = if (track.isAutoGenerated) "auto-generated" else "manual"
        return "Saved transcript of '${resolved.title.ifBlank { resolved.videoId }}' to '$target' " +
            "(language: ${track.languageTag}, $kind subtitles, format: $ext)."
    }

    private suspend fun downloadStreamTo(env: ToolEnv, url: String, target: String) {
        val segs = env.fs.segments(target)
        val root = env.fs.rootForPath(target)
        val parent = env.fs.ensureDir(segs.drop(1).dropLast(1), root = root.requireDoc())
        val name = segs.last()
        var doc = env.fs.childByName(parent, name)
        if (doc != null && env.fs.isDir(doc)) throw ToolException("'$target' is a folder.")
        if (doc == null) doc = env.fs.createFileNamed(parent, name)
        // Live progress for the tool card ("Running. Progress 42%"): reported
        // through the call's progress scope, never into the result text.
        env.fs.openOut(doc).use { out ->
            YouTubeExtractor.downloadTo(url, out) { downloaded, total ->
                env.reportProgress(downloaded, total)
            }
        }
    }
}
