package com.example.byokchat.tools

import com.example.byokchat.chat.ModelFacingText
import com.example.byokchat.container
import com.example.byokchat.media.MediaPlayException
import com.example.byokchat.media.MediaPlayback
import com.example.byokchat.media.QueueTrack
import com.example.byokchat.media.formatMediaTime
import kotlinx.serialization.json.JsonObject

fun mediaTool(
    uid: String,
    risk: Risk,
    name: String,
    description: String,
    schema: JsonObject,
    preview: (suspend (ToolEnv, JsonObject) -> String)? = null,
    run: suspend (ToolEnv, JsonObject) -> String,
) = Tool(uid, ToolGroup.MEDIA, risk, name, description, schema, needsWorkspace = true, previewFn = preview, run = run)

/**
 * Nhóm 5: mở nhạc. Chỉ 1 tool (kế hoạch media player, Đợt 1): media.play.
 * Không có tool dừng/tạm dừng cho AI — muốn dừng thì user bấm nút X trên
 * thanh mini-player (hoặc nút dừng trong thông báo nhạc).
 */
object MediaTools {
    fun build(): List<Tool> = listOf(
        mediaTool(
            "media.play", Risk.READ, "play_music",
            "Play music files from the AI playground. Give one or more file paths; they play in order, one after another. Playback keeps going in the background, and the chat shows a small player bar (previous / pause / next / time / progress bar / close).",
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
            val media: MediaPlayback = env.app.container.media
            val durationMs = try {
                media.play(tracks)
            } catch (e: MediaPlayException) {
                return@mediaTool e.message ?: ModelFacingText.mediaPlayFailed(tracks[0].name)
            }
            val first = tracks[0]
            ModelFacingText.mediaPlaying(first.name, formatMediaTime(durationMs), tracks.drop(1).map { it.name })
        },
    )
}
