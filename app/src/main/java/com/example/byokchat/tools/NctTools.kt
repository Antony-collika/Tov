package com.example.byokchat.tools

import com.example.byokchat.nct.NctApi
import com.example.byokchat.nct.formatNctResults
import com.example.byokchat.nct.isSignedUrlExpired
import com.example.byokchat.nct.parseNctSongKey
import com.example.byokchat.nct.selectStream
import com.example.byokchat.util.humanSize
import com.example.byokchat.youtube.sanitizeFileName
import kotlinx.serialization.json.JsonObject

fun nctTool(
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
 * NhacCuaTui tools (2026-11-14). Two tools; nct.media.play lives in
 * MediaTools, next to local.media.play / youtube.media.play.
 * No API key: the search / song pages are scraped (see NctApi). No
 * youtube-style transcript or video here — NCT downloads are audio only.
 */
object NctTools {
    fun build(): List<Tool> = listOf(
        nctTool(
            "nct_search", ToolGroup.MEDIA, Risk.READ, "nct_search",
            "Search NhacCuaTui (NCT) by keyword, or get the details of an NCT song link the user sent. No API key needed. Each result already has everything needed to choose: song key, title, artist, duration, and which qualities exist (128kbps, 320kbps, sometimes FLAC). Return at most 50 results per call, default 20. Fetch more only if the user asks for more. To play a song, pass its key to nct.media.play; to save it, pass its key to nct_download. Songs flagged VIP only cannot be played or downloaded — say so clearly, do not pick another song instead.",
            schema {
                str("q", "Search keywords, or a full NhacCuaTui song link.", required = true)
                int("max_results", "Results per call, 1-50, default 20. Fetch more only if the user asks for more.")
            },
            preview = { _, a -> "Search NhacCuaTui: ${a.reqStr("q")}" },
        ) { _, a ->
            val api = NctApi()
            val q = a.reqStr("q").trim()
            val max = (a.int("max_results") ?: 20).coerceIn(1, 50)
            if (q.contains("nhaccuatui.com")) {
                val key = parseNctSongKey(q)
                    ?: return@nctTool "No NCT song was found in '$q'. Give a link like 'https://www.nhaccuatui.com/song/...'."
                val song = api.songByKey(key)
                formatNctResults(listOf(song))
            } else {
                val songs = api.search(q, max)
                if (songs.isEmpty()) "No results for '$q'." else formatNctResults(songs)
            }
        },

        nctTool(
            "nct_download", ToolGroup.MEDIA, Risk.WRITE, "nct_download",
            "Download one NhacCuaTui song as audio and save it to a path inside the Playground folders the user granted. The path is relative to the virtual root, and its first segment must be a granted folder name, for example \"Nhac/NCT/\". Download one song at a time. If the target folder is not clear, ask the user which granted folder to use, do not guess. Missing subfolders are created automatically. Audio only — NCT has no video or transcript here. Quality: hq (320kbps, default) or 128 (128kbps). FLAC only when the user explicitly asks for it: it is much larger (about 34 MB per song). A VIP-only song cannot be downloaded — the tool will say so clearly. A path outside the granted folders will be refused, do not save it elsewhere instead.",
            schema {
                str("song", "NCT song key or full song link from nct_search.", required = true)
                str("path", "Destination in the Playground: a folder (the app names the file) or a full file path. The first segment must be a granted folder name.", required = true)
                str("quality", "hq (320kbps, default), 128 (128kbps), or flac (only when the user explicitly asks for FLAC).", enum = listOf("hq", "128", "flac"))
            },
            needsWorkspace = true,
            preview = { _, a -> "Download NCT audio to ${a.str("path") ?: "?"}" },
        ) { env, a ->
            val key = parseNctSongKey(a.reqStr("song"))
                ?: throw ToolException(
                    "Give an NCT song key or link from nct_search, e.g. 'https://www.nhaccuatui.com/song/PgrxRcNASk'.",
                )
            val rawPath = a.reqStr("path").trim()
            // Validate the path against the Playground virtual root first:
            // unknown first segment errors back with the granted folder list.
            env.fs.locate(rawPath)
            // Fresh song detail: a fresh signed URL at download time, never a stored one.
            val song = NctApi().songByKey(key)
            val quality = a.str("quality")
            val stream = selectStream(song, quality)
                ?: if (song.onlyVip) {
                    throw ToolException(
                        "Song '${song.title}' by ${song.artistName} is VIP-only on NhacCuaTui, " +
                            "so it cannot be downloaded.",
                    )
                } else {
                    throw ToolException(
                        "Song '${song.title}' has no ${quality ?: "hq"} audio stream. " +
                            "Available qualities: ${song.qualityLabels().joinToString(", ")}.",
                    )
                }
            if (stream.onlyVip) {
                throw ToolException(
                    "Song '${song.title}' is VIP-only on NhacCuaTui at ${stream.typeUi.ifBlank { stream.type }}, " +
                        "so it cannot be downloaded. Its other qualities: " +
                        song.streams.filter { !it.onlyVip }.map { it.typeUi.ifBlank { it.type } }
                            .distinct().joinToString(", ").ifBlank { "none" } + ".",
                )
            }
            val url = stream.downloadUrl ?: stream.streamUrl
            if (isSignedUrlExpired(url)) {
                throw ToolException(
                    "NhacCuaTui gave a download link that is already expired. Try again.",
                )
            }
            val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
                .takeIf { it == "mp3" || it == "flac" } ?: "mp3"
            val baseName = sanitizeFileName("${song.title} - ${song.artistName}")
            val fileName = "$baseName.$ext"
            val target = resolveTargetFile(env, rawPath, fileName)
            downloadStreamTo(env, url, target)
            "Saved audio '${song.title}' by ${song.artistName} (${stream.typeUi.ifBlank { stream.type }}) " +
                "to '$target' (${humanSize(env.fs.sizeOf(env.fs.require(target)))})."
        },
    )
}
