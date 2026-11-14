package com.example.byokchat.tools

import com.example.byokchat.chat.ModelFacingText
import com.example.byokchat.container
import com.example.byokchat.media.MediaPlayException
import com.example.byokchat.media.MediaPlayback
import com.example.byokchat.media.QueueTrack
import com.example.byokchat.media.formatMediaTime
import com.example.byokchat.youtube.YouTubeExtractor
import com.example.byokchat.youtube.parseVideoId
import kotlinx.serialization.json.JsonObject

fun mediaTool(
    uid: String,
    risk: Risk,
    name: String,
    description: String,
    schema: JsonObject,
    needsWorkspace: Boolean = true,
    preview: (suspend (ToolEnv, JsonObject) -> String)? = null,
    run: suspend (ToolEnv, JsonObject) -> String,
) = Tool(uid, ToolGroup.MEDIA, risk, name, description, schema, needsWorkspace, previewFn = preview, run = run)

/**
 * Nhóm 5: mở nhạc. Hai tool, một player:
 * - local.media.play (tên cũ: media.play / play_music): file nhạc local trong playground.
 * - youtube.media.play: audio của video YouTube, không lưu file.
 * Không có tool dừng/tạm dừng cho AI — muốn dừng thì user bấm nút X trên
 * thanh mini-player (hoặc nút dừng trong thông báo nhạc).
 */
object MediaTools {
    fun build(): List<Tool> = listOf(
        mediaTool(
            "local.media.play", Risk.READ, "local_media_play",
            "Play local music files from the AI playground. Give one or more file paths; they play in order, one after another. Playback keeps going in the background, and the chat shows a small player bar (previous / pause / next / time / progress bar / close).",
            schema { strArray("paths", "Music file paths in the playground, e.g. ['Music/song A.mp3', 'Music/song B.mp3'].", required = true) },
            preview = { _, a ->
                val paths = a.strList("paths").orEmpty()
                if (paths.isEmpty()) "Play music: (no files given)"
                else "Phát nhạc: " + paths.joinToString(", ") { it.substringAfterLast('/') }
            },
        ) { env, a ->
            val paths = a.strList("paths").orEmpty()
            if (paths.isEmpty()) throw ToolException(ModelFacingText.MEDIA_NEED_PATHS)
            // Kiểm tra hết cả batch trước khi phát bài nào: file nào lỗi thì
            // chưa phát gì cả, AI báo lại đúng file đó.
            val tracks = paths.map { path ->
                val display = env.fs.display(path)
                val doc = env.fs.find(path) ?: throw ToolException(ModelFacingText.mediaNotFound(display))
                if (env.fs.isDir(doc)) throw ToolException(ModelFacingText.mediaIsFolder(display))
                QueueTrack(display, doc.uri.toString(), env.fs.nameOf(doc))
            }
            playTracks(env, tracks)
        },

        mediaTool(
            "youtube.media.play", Risk.READ, "youtube_media_play",
            "Play the audio of one or more YouTube videos right away, in sequence, in the background, with the mini-player like local.media.play. Audio only, no video. This tool does not search: to play something, search with youtube_search first, choose a video, then call this tool with the video ID or link of the chosen video. Use it when the user asks to play music or listen to a video. To play a local music file in the Playground, use local.media.play, not this tool.",
            schema { strArray("videos", "Video IDs or full YouTube links, e.g. ['dQw4w9WgXcQ', 'https://www.youtube.com/watch?v=...']. At least one.", required = true) },
            needsWorkspace = false,
            preview = { _, a ->
                val videos = a.strList("videos").orEmpty()
                if (videos.isEmpty()) "Play YouTube audio: (no videos given)"
                else "Phát audio YouTube: " + videos.joinToString(", ")
            },
        ) { env, a ->
            val videos = a.strList("videos").orEmpty()
            if (videos.isEmpty()) throw ToolException("Give at least one video ID or YouTube link to play.")
            // Resolve every video's audio stream before playing any of them,
            // so one bad video does not leave the player half-started.
            val tracks = videos.map { input ->
                val videoId = parseVideoId(input)
                    ?: throw ToolException("'$input' is not a YouTube video ID or link.")
                val resolved = YouTubeExtractor.fetch(videoId)
                val stream = YouTubeExtractor.bestAudioStream(resolved.audioStreams)
                    ?: throw ToolException("Video '${resolved.title.ifBlank { videoId }}' has no audio stream to play.")
                QueueTrack(
                    displayPath = resolved.title.ifBlank { videoId },
                    uri = stream.content,
                    name = resolved.title.ifBlank { videoId },
                )
            }
            playTracks(env, tracks)
        },
    )

    private suspend fun playTracks(env: ToolEnv, tracks: List<QueueTrack>): String {
        val media: MediaPlayback = env.app.container.media
        val durationMs = try {
            media.play(tracks)
        } catch (e: MediaPlayException) {
            return e.message ?: ModelFacingText.mediaPlayFailed(tracks[0].name)
        }
        val first = tracks[0]
        return ModelFacingText.mediaPlaying(first.name, formatMediaTime(durationMs), tracks.drop(1).map { it.name })
    }
}
