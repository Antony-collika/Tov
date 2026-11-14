package com.example.byokchat.tools

import com.example.byokchat.AppContainer
import com.example.byokchat.chat.ModelFacingText
import com.example.byokchat.container
import com.example.byokchat.media.MediaPlayException
import com.example.byokchat.media.MediaPlayback
import com.example.byokchat.media.PlayedSource
import com.example.byokchat.media.PlayedTrack
import com.example.byokchat.media.QueueTrack
import com.example.byokchat.media.formatMediaTime
import com.example.byokchat.nct.NctApi
import com.example.byokchat.nct.isSignedUrlExpired
import com.example.byokchat.nct.parseNctSongKey
import com.example.byokchat.nct.selectStream
import com.example.byokchat.soundcloud.SoundCloudExtractor
import com.example.byokchat.soundcloud.parseSoundCloudLink
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
 * Nhóm 5: mở nhạc. Bốn tool, một player:
 * - local.media.play (tên cũ: media.play / play_music): file nhạc local trong playground.
 * - youtube.media.play: audio của video YouTube, không lưu file.
 * - nct.media.play: audio của bài hát NhacCuaTui, không lưu file.
 * - soundcloud.media.play: audio của track SoundCloud, không lưu file.
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
            val tracks = paths.map { path -> resolveLocalTrack(env.fs, path) }
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
                resolveYouTubeTrack(videoId)
            }
            playTracks(env, tracks)
        },

        mediaTool(
            "nct.media.play", Risk.READ, "nct_media_play",
            "Play the audio of one or more NhacCuaTui songs right away, in sequence, in the background, with the mini-player like local.media.play. Audio only, no video. This tool does not search: to play something, search with nct_search first, choose a song, then call this tool with the song key or link of the chosen song. The app fetches a fresh stream link for each song when it plays. A VIP-only song cannot be played — the tool will say so clearly. To play a local music file in the Playground, use local.media.play, not this tool.",
            schema { strArray("songs", "NCT song keys or full song links from nct_search, e.g. ['PgrxRcNASk', 'https://www.nhaccuatui.com/song/...']. At least one.", required = true) },
            needsWorkspace = false,
            preview = { _, a ->
                val songs = a.strList("songs").orEmpty()
                if (songs.isEmpty()) "Play NCT audio: (no songs given)"
                else "Phát audio NCT: " + songs.joinToString(", ")
            },
        ) { env, a ->
            val songs = a.strList("songs").orEmpty()
            if (songs.isEmpty()) throw ToolException("Give at least one NCT song key or link to play.")
            // Resolve every song's fresh stream link before playing any of them,
            // so one bad or VIP-only song does not leave the player half-started.
            val tracks = songs.map { input ->
                val key = parseNctSongKey(input)
                    ?: throw ToolException("'$input' is not an NCT song key or link.")
                resolveNctTrack(key)
            }
            playTracks(env, tracks)
        },

        mediaTool(
            "soundcloud.media.play", Risk.READ, "soundcloud_media_play",
            "Play the audio of one or more SoundCloud tracks right away, in sequence, in the background, with the mini-player like local.media.play. Audio only. This tool does not search: to play something, search with soundcloud_search first, choose a track, then call this tool with the SoundCloud link of the chosen track. To play a local music file in the Playground, use local.media.play, not this tool.",
            schema { strArray("tracks", "SoundCloud track links from soundcloud_search, e.g. ['https://soundcloud.com/artist/track']. At least one.", required = true) },
            needsWorkspace = false,
            preview = { _, a ->
                val tracks = a.strList("tracks").orEmpty()
                if (tracks.isEmpty()) "Play SoundCloud audio: (no tracks given)"
                else "Phát audio SoundCloud: " + tracks.joinToString(", ")
            },
        ) { env, a ->
            val tracks = a.strList("tracks").orEmpty()
            if (tracks.isEmpty()) throw ToolException("Give at least one SoundCloud track link to play.")
            // Resolve every track's audio stream before playing any of them,
            // so one bad track does not leave the player half-started.
            val queue = tracks.map { input ->
                val url = parseSoundCloudLink(input)
                    ?: throw ToolException("'$input' is not a SoundCloud track link.")
                resolveSoundCloudTrack(url)
            }
            playTracks(env, queue)
        },
    )

    private suspend fun playTracks(env: ToolEnv, tracks: List<QueueTrack>): String {
        val media: MediaPlayback = env.app.container.media
        val durationMs = try {
            media.play(tracks)
        } catch (e: MediaPlayException) {
            return e.message ?: ModelFacingText.mediaPlayFailed(tracks[0].name)
        }
        // Ghi vào "Recently played" của đúng cuộc trò chuyện này (chỉ ghi
        // khi đã phát được — lỗi mở bài thì không ghi). Lịch sử sống riêng
        // khỏi hàng đợi nên bấm X không xóa nó.
        currentConversationId()?.let { conversationId ->
            val store = env.app.container.recentlyPlayed
            store.recordAll(
                conversationId,
                tracks.mapNotNull { track ->
                    val source = track.replaySource ?: return@mapNotNull null
                    val key = track.replayKey ?: return@mapNotNull null
                    PlayedTrack(source, key, track.name)
                },
            )
        }
        val first = tracks[0]
        return ModelFacingText.mediaPlaying(first.name, formatMediaTime(durationMs), tracks.drop(1).map { it.name })
    }

    /**
     * Resolve một bài thành QueueTrack, dùng chung cho tool phát nhạc và
     * cho phát lại từ "Recently played". Mỗi lần gọi đều resolve lại link
     * stream mới — không bao giờ dùng lại link cũ đã lưu (link YouTube/NCT
     * có hạn dùng, NCT ~7 ngày). Lỗi nào cũng ném ToolException với câu
     * báo rõ, không bao giờ tự đổi sang bài khác.
     */
    private suspend fun resolveLocalTrack(fs: WorkspaceFs, path: String): QueueTrack {
        val display = fs.display(path)
        val doc = fs.find(path) ?: throw ToolException(ModelFacingText.mediaNotFound(display))
        if (fs.isDir(doc)) throw ToolException(ModelFacingText.mediaIsFolder(display))
        return QueueTrack(display, doc.uri.toString(), fs.nameOf(doc), PlayedSource.LOCAL, path)
    }

    private suspend fun resolveYouTubeTrack(videoId: String): QueueTrack {
        val resolved = YouTubeExtractor.fetch(videoId)
        val stream = YouTubeExtractor.bestAudioStream(resolved.audioStreams)
            ?: throw ToolException("Video '${resolved.title.ifBlank { videoId }}' has no audio stream to play.")
        val name = resolved.title.ifBlank { videoId }
        return QueueTrack(name, stream.content, name, PlayedSource.YOUTUBE, videoId)
    }

    private suspend fun resolveNctTrack(key: String): QueueTrack {
        val song = NctApi().songByKey(key)
        val stream = selectStream(song, null)
            ?: if (song.onlyVip) {
                throw ToolException(
                    "Song '${song.title}' by ${song.artistName} is VIP-only on NhacCuaTui, " +
                        "so it cannot be played.",
                )
            } else {
                throw ToolException("Song '${song.title}' has no audio stream to play.")
            }
        if (isSignedUrlExpired(stream.streamUrl)) {
            throw ToolException(
                "NhacCuaTui gave a stream link for '${song.title}' that is already expired. Try again.",
            )
        }
        val display = if (song.artistName.isBlank()) song.title.ifBlank { key }
        else "${song.title.ifBlank { key }} - ${song.artistName}"
        return QueueTrack(display, stream.streamUrl, display, PlayedSource.NCT, key)
    }

    private suspend fun resolveSoundCloudTrack(url: String): QueueTrack {
        val resolved = SoundCloudExtractor.fetch(url)
        val stream = YouTubeExtractor.bestAudioStream(resolved.audioStreams)
            ?: throw ToolException("Track '${resolved.track.title.ifBlank { url }}' has no audio stream to play.")
        val display = if (resolved.track.artist.isBlank()) resolved.track.title.ifBlank { url }
        else "${resolved.track.title.ifBlank { url }} - ${resolved.track.artist}"
        return QueueTrack(display, stream.content, display, PlayedSource.SOUNDCLOUD, url)
    }

    /**
     * Phát lại một bài từ "Recently played": resolve lại stream mới từ khóa
     * rồi phát như tool phát nhạc. Trả về câu "đang phát" cho UI; lỗi (file
     * đã xóa, video không còn, bài VIP) thì ném Exception với câu báo rõ
     * để UI hiện lên, không phát bài khác thay thế. Phát lại thành công
     * cũng ghi vào lịch sử (bài đó lại thành mới nhất).
     */
    suspend fun replayPlayedTrack(container: AppContainer, conversationId: String, track: PlayedTrack): String {
        val queueTrack = when (track.source) {
            PlayedSource.LOCAL -> resolveLocalTrack(container.fs, track.key)
            PlayedSource.YOUTUBE -> resolveYouTubeTrack(track.key)
            PlayedSource.NCT -> resolveNctTrack(track.key)
            PlayedSource.SOUNDCLOUD -> resolveSoundCloudTrack(track.key)
        }
        val durationMs = try {
            container.media.play(listOf(queueTrack))
        } catch (e: MediaPlayException) {
            throw ToolException(e.message ?: ModelFacingText.mediaPlayFailed(queueTrack.name))
        }
        container.recentlyPlayed.record(conversationId, track)
        return ModelFacingText.mediaPlaying(queueTrack.name, formatMediaTime(durationMs), emptyList())
    }
}
