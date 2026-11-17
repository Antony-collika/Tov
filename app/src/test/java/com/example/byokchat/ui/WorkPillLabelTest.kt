package com.example.byokchat.ui

import com.example.byokchat.ai.AiToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pill labels (17/11/2026, mockup v2): one short Vietnamese sentence per
 * step, naming the concrete object from the call's own arguments — never
 * file contents or tool results. Tools in the same group share one
 * sentence pattern; the model-facing tool descriptions are untouched.
 */
class WorkPillLabelTest {

    private fun tool(uid: String, args: String) = WorkStep.Tool(AiToolCall("c1", "some_name", args), null)

    @Test
    fun readFileNamesTheFile() {
        val step = tool("txt.read", """{"path":"note/cách tìm kiếm.md"}""")
        assertEquals("Đọc file note/cách tìm kiếm.md", stepPhrase(step))
        assertEquals("Đang đọc file note/cách tìm kiếm.md…", pillLabel(step, running = true, done = false))
        assertEquals("Đã đọc file note/cách tìm kiếm.md", pillLabel(step, running = false, done = true))
    }

    @Test
    fun listFolderNamesTheFolder() {
        val step = tool("fs.list", """{"path":"note"}""")
        assertEquals("Xem danh sách file trong thư mục note", stepPhrase(step))
        assertEquals("Đang xem danh sách file trong thư mục note…", pillLabel(step, running = true, done = false))
    }

    @Test
    fun listWithNoPathHasNoFolder() {
        assertEquals("Xem danh sách file", stepPhrase(tool("fs.list", "{}")))
    }

    @Test
    fun webSearchNamesTheQuery() {
        val step = tool("web.search", """{"query":"thời tiết Hà Nội"}""")
        assertEquals("Tìm kiếm “thời tiết Hà Nội” trên web", stepPhrase(step))
    }

    @Test
    fun youtubeSearchNamesTheQuery() {
        val step = tool("youtube_search", """{"q":"nhạc vui"}""")
        assertEquals("Tìm “nhạc vui” trên YouTube", stepPhrase(step))
        assertEquals("Đang tìm “nhạc vui” trên YouTube…", pillLabel(step, running = true, done = false))
        assertEquals("Đã tìm “nhạc vui” trên YouTube", pillLabel(step, running = false, done = true))
    }

    @Test
    fun youtubePlayHasNoTitleInArgs() {
        // The play call only carries video ids/links, so the pill must not
        // invent a title — the track name lives in the tool result, which
        // the pill never streams.
        val step = tool("youtube.media.play", """{"videos":["dQw4w9WgXcQ"]}""")
        assertEquals("Phát nhạc trên YouTube", stepPhrase(step))
    }

    @Test
    fun localPlayNamesTheFirstFile() {
        val step = tool("local.media.play", """{"paths":["Music/song A.mp3","Music/song B.mp3"]}""")
        assertEquals("Phát 2 bài nhạc", stepPhrase(step))
        val one = tool("local.media.play", """{"paths":["Music/song A.mp3"]}""")
        assertEquals("Phát song A.mp3", stepPhrase(one))
    }

    @Test
    fun weatherNamesThePlace() {
        val step = tool("weather_forecast", """{"place":"Hà Nội"}""")
        assertEquals("Xem thời tiết Hà Nội", stepPhrase(step))
        assertEquals("Đã xem thời tiết Hà Nội", pillLabel(step, running = false, done = true))
    }

    @Test
    fun delegateNamesTheAgent() {
        val step = tool("sub.delegate", """{"agent":"bob","title":"Tra thời tiết","brief":"..."}""")
        assertEquals("Giao việc cho Bob", stepPhrase(step) { if (it == "bob") "Bob" else it })
        assertEquals("Đã giao việc cho Bob", pillLabel(step, running = false, done = true) { if (it == "bob") "Bob" else it })
    }

    @Test
    fun reasoningHasItsOwnLabels() {
        val step = WorkStep.Reasoning("Tôi nên tìm tin mới nhất.")
        assertEquals("Suy nghĩ", stepPhrase(step))
        assertEquals("Đang suy nghĩ…", pillLabel(step, running = true, done = false))
        assertEquals("Đã suy nghĩ xong", pillLabel(step, running = false, done = true))
    }

    @Test
    fun unknownToolFallsBackToItsUid() {
        val step = tool("some.unknown", "{}")
        assertEquals("Gọi some.unknown", stepPhrase(step))
    }

    @Test
    fun longObjectIsCutShort() {
        val step = tool("txt.read", """{"path":"${"x".repeat(100)}.md"}""")
        assertEquals("Đọc file ${"x".repeat(39)}…", stepPhrase(step))
    }

    @Test
    fun targetIsTheConcreteObject() {
        assertEquals("note/cách tìm kiếm.md", stepTarget(tool("txt.read", """{"path":"note/cách tìm kiếm.md"}""")))
        assertEquals("Hà Nội", stepTarget(tool("weather_forecast", """{"place":"Hà Nội"}""")))
        assertEquals("Bob", stepTarget(tool("sub.delegate", """{"agent":"bob"}""")) { if (it == "bob") "Bob" else it })
        assertEquals(null, stepTarget(WorkStep.Reasoning("...")))
    }
}
