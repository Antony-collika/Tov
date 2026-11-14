package com.example.byokchat.tools

import com.example.byokchat.soundcloud.SoundCloudExtractor
import com.example.byokchat.soundcloud.formatSoundCloudResults
import com.example.byokchat.soundcloud.parseSoundCloudLink
import com.example.byokchat.util.humanSize
import com.example.byokchat.youtube.YouTubeExtractor
import com.example.byokchat.youtube.sanitizeFileName
import kotlinx.serialization.json.JsonObject

fun soundCloudTool(
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
 * SoundCloud tools (2026-11-14). Two tools; soundcloud.media.play lives
 * in MediaTools, next to local.media.play / youtube.media.play.
 * No API key: search and streams go through NewPipeExtractor (SoundCloud
 * is a supported NewPipe service with full streams). Audio only.
 */
object SoundCloudTools {
    fun build(): List<Tool> = listOf(
        soundCloudTool(
            "soundcloud_search", ToolGroup.MEDIA, Risk.READ, "soundcloud_search",
            "Search SoundCloud by keyword, or get the details of a SoundCloud track link the user sent. No API key needed. Each result already has everything needed to choose: track link, title, artist, duration. Return at most 50 results per call, default 20. Fetch more only if the user asks for more. To play a track, pass its link to soundcloud.media.play; to save it, pass its link to soundcloud_download.",
            schema {
                str("q", "Search keywords, or a full SoundCloud track link.", required = true)
                int("max_results", "Results per call, 1-50, default 20. Fetch more only if the user asks for more.")
            },
            preview = { _, a -> "Search SoundCloud: ${a.reqStr("q")}" },
        ) { _, a ->
            val q = a.reqStr("q").trim()
            val max = (a.int("max_results") ?: 20).coerceIn(1, 50)
            if (q.contains("soundcloud.com")) {
                val url = parseSoundCloudLink(q)
                    ?: return@soundCloudTool "No SoundCloud track was found in '$q'. Give a link like 'https://soundcloud.com/artist/track'."
                val track = SoundCloudExtractor.fetch(url).track
                formatSoundCloudResults(listOf(track))
            } else {
                val tracks = SoundCloudExtractor.search(q, max)
                if (tracks.isEmpty()) "No results for '$q'." else formatSoundCloudResults(tracks)
            }
        },

        soundCloudTool(
            "soundcloud_download", ToolGroup.MEDIA, Risk.WRITE, "soundcloud_download",
            "Download one SoundCloud track as audio and save it to a path inside the Playground folders the user granted. The path is relative to the virtual root, and its first segment must be a granted folder name, for example \"Nhac/SoundCloud/\". Download one track at a time. If the target folder is not clear, ask the user which granted folder to use, do not guess. Missing subfolders are created automatically. Audio only. A path outside the granted folders will be refused, do not save it elsewhere instead.",
            schema {
                str("track", "SoundCloud track link from soundcloud_search.", required = true)
                str("path", "Destination in the Playground: a folder (the app names the file) or a full file path. The first segment must be a granted folder name.", required = true)
            },
            needsWorkspace = true,
            preview = { _, a -> "Download SoundCloud audio to ${a.str("path") ?: "?"}" },
        ) { env, a ->
            val url = parseSoundCloudLink(a.reqStr("track"))
                ?: throw ToolException(
                    "Give a SoundCloud track link from soundcloud_search, e.g. 'https://soundcloud.com/artist/track'.",
                )
            val rawPath = a.reqStr("path").trim()
            // Validate the path against the Playground virtual root first:
            // unknown first segment errors back with the granted folder list.
            env.fs.locate(rawPath)
            val resolved = SoundCloudExtractor.fetch(url)
            val stream = YouTubeExtractor.bestAudioStream(resolved.audioStreams)
                ?: throw ToolException("Track '${resolved.track.title.ifBlank { url }}' has no audio stream to download.")
            val fileName = "${sanitizeFileName("${resolved.track.title} - ${resolved.track.artist}")}" +
                ".${YouTubeExtractor.audioExtension(stream.format)}"
            val target = resolveTargetFile(env, rawPath, fileName)
            downloadStreamTo(env, stream.content, target)
            "Saved audio '${resolved.track.title}' by ${resolved.track.artist} " +
                "to '$target' (${humanSize(env.fs.sizeOf(env.fs.require(target)))})."
        },
    )
}
