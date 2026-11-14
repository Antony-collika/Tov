package com.example.byokchat.media

import com.example.byokchat.chat.ModelFacingText
import com.example.byokchat.tools.MediaTools
import com.example.byokchat.tools.Risk
import com.example.byokchat.tools.ToolGroup
import com.example.byokchat.tools.ToolMode
import com.example.byokchat.tools.ToolsMdParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho media player (kế hoạch Đợt 4): luật hàng đợi, giờ phát, câu trả về cho AI, tools.md. */
class MediaQueueTest {

    private val tracks = listOf(
        QueueTrack("Music/a.mp3", "content://x/a", "a.mp3"),
        QueueTrack("Music/b.mp3", "content://x/b", "b.mp3"),
        QueueTrack("Music/c.mp3", "content://x/c", "c.mp3"),
    )

    @Test
    fun queueNextPreviousAndEndOfBatch() {
        val q0 = MediaQueue(tracks, 0)
        assertEquals("a.mp3", q0.current?.name)
        assertEquals("1/3", q0.label)
        val q1 = q0.next()!!
        assertEquals("b.mp3", q1.current?.name)
        assertEquals("2/3", q1.label)
        val q2 = q1.next()!!
        assertEquals("c.mp3", q2.current?.name)
        assertNull(q2.next()) // hết batch thì dừng
        assertEquals("b.mp3", q2.previous().current?.name)
        assertEquals("a.mp3", q0.previous().current?.name) // ở bài đầu thì ở lại (phát lại từ đầu)
        assertTrue(MediaQueue(emptyList()).isEmpty)
        assertEquals("", MediaQueue(emptyList()).label)
        assertNull(MediaQueue(emptyList()).current)
    }

    @Test
    fun formatMediaTime() {
        assertEquals("0:00", formatMediaTime(0))
        assertEquals("0:01", formatMediaTime(1000))
        assertEquals("1:01", formatMediaTime(61_000))
        assertEquals("3:24", formatMediaTime(204_000))
        assertEquals("62:03", formatMediaTime(3_723_000))
        assertEquals("0:00", formatMediaTime(-500))
    }

    @Test
    fun mediaStateVisibilityAndLabel() {
        assertFalse(MediaState().visible)
        val playing = MediaState(queue = tracks, index = 1, playing = true, positionMs = 12_000, durationMs = 204_000)
        assertTrue(playing.visible)
        assertEquals("b.mp3", playing.current?.name)
        assertEquals("2/3", playing.label)
    }

    @Test
    fun mediaToolDefinition() {
        val tools = MediaTools.build()
        assertEquals(listOf("local.media.play", "youtube.media.play", "soundcloud.media.play"), tools.map { it.uid })
        val tool = tools[0]
        assertEquals(ToolGroup.MEDIA, tool.group)
        assertEquals(Risk.READ, tool.risk)
        assertEquals("local_media_play", tool.defaultName)
        assertEquals(ToolMode.AUTO, tool.defaultMode) // READ -> AUTO: bảo bật là bật
        assertTrue(tool.needsWorkspace)
        val props = tool.schema["properties"] as JsonObject
        val paths = props["paths"] as JsonObject
        assertEquals("array", (paths["type"] as JsonPrimitive).content)
        val required = tool.schema["required"] as JsonArray
        assertTrue(required.map { (it as JsonPrimitive).content }.contains("paths"))
        // youtube.media.play needs no Playground folder (streams from the network).
        val yt = tools[1]
        assertEquals("youtube_media_play", yt.defaultName)
        assertFalse(yt.needsWorkspace)
        val ytProps = yt.schema["properties"] as JsonObject
        assertTrue(ytProps.containsKey("videos"))
        assertFalse(ytProps.containsKey("q"))
        // soundcloud.media.play: same shape, SoundCloud track links.
        val sc = tools[2]
        assertEquals("soundcloud_media_play", sc.defaultName)
        assertFalse(sc.needsWorkspace)
        val scProps = sc.schema["properties"] as JsonObject
        assertTrue(scProps.containsKey("tracks"))
        assertTrue(sc.defaultDescription.contains("soundcloud_search"))
    }

    @Test
    fun mediaToolInToolsMdTemplateAndParsesBack() {
        val tools = MediaTools.build()
        val template = ToolsMdParser.buildTemplate(tools)
        assertTrue(template.contains("## local.media.play"))
        assertTrue(template.contains("## youtube.media.play"))
        assertTrue(template.contains("## soundcloud.media.play"))
        val origins = tools.associate { it.uid to it.defaultDescription }
        val parsed = ToolsMdParser.parse(template, origins)
        assertEquals(listOf("local.media.play", "youtube.media.play", "soundcloud.media.play"), parsed.map { it.uid })
        assertEquals("local_media_play", parsed[0].name)
        assertEquals("youtube_media_play", parsed[1].name)
        assertEquals("soundcloud_media_play", parsed[2].name)
    }

    @Test
    fun mediaPlayingResultStrings() {
        assertEquals("Đang phát: a.mp3 (3:24)", ModelFacingText.mediaPlaying("a.mp3", "3:24", emptyList()))
        assertEquals(
            "Đang phát: a.mp3 (3:24), tiếp theo: b.mp3, c.mp3",
            ModelFacingText.mediaPlaying("a.mp3", "3:24", listOf("b.mp3", "c.mp3")),
        )
        assertEquals("Không phát được file 'a.mp3'.", ModelFacingText.mediaPlayFailed("a.mp3"))
        assertEquals("Không tìm thấy file 'Music/x.mp3'.", ModelFacingText.mediaNotFound("Music/x.mp3"))
        assertEquals("'Music' là thư mục, không phải file nhạc.", ModelFacingText.mediaIsFolder("Music"))
        assertEquals("Không phát được nhạc lúc này vì âm thanh đang bận.", ModelFacingText.mediaFocusBusy())
    }
}
