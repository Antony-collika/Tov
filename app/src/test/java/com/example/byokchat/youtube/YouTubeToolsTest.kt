package com.example.byokchat.youtube

import com.example.byokchat.tools.Risk
import com.example.byokchat.tools.ToolGroup
import com.example.byokchat.tools.ToolMode
import com.example.byokchat.tools.ToolsMdParser
import com.example.byokchat.tools.YouTubeTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** YouTube tools (2026-11-13, FINAL): 3 API/download tools, no info, no in-chat transcript. */
class YouTubeToolsTest {

    @Test
    fun threeToolsWithLockedUidsNamesAndDefaults() {
        val tools = YouTubeTools.build()
        assertEquals(listOf("youtube_search", "youtube_comments", "youtube_download"), tools.map { it.uid })
        assertEquals(listOf("youtube_search", "youtube_comments", "youtube_download"), tools.map { it.defaultName })
        assertEquals(ToolGroup.MEDIA, tools[0].group)
        assertEquals(ToolGroup.MEDIA, tools[1].group)
        assertEquals(ToolGroup.MEDIA, tools[2].group)
        assertEquals(Risk.READ, tools[0].risk)
        assertEquals(Risk.READ, tools[1].risk)
        assertEquals(Risk.WRITE, tools[2].risk) // download writes files -> ASK by default
        assertEquals(ToolMode.AUTO, tools[0].defaultMode)
        assertEquals(ToolMode.ASK, tools[2].defaultMode)
        assertFalse(tools[0].needsWorkspace) // search needs only the API key
        assertTrue(tools[2].needsWorkspace) // download needs a Playground folder
    }

    @Test
    fun searchAndCommentsSchemasHaveLockedDefaultsInDescriptions() {
        val tools = YouTubeTools.build()
        val search = tools[0]
        assertTrue(search.defaultDescription.contains("default 20"))
        // The locked description does not name itself; it documents channel
        // listing via channel_id instead (draft FINAL, byte-identical).
        assertTrue(search.defaultDescription.contains("channel_id"))
        // q is optional since the channel-listing fix (2026-11-14): listing
        // a channel's videos needs only channel_id, and a channel/playlist
        // link alone is enough — so q must NOT be required any more.
        assertFalse(search.schema["required"]?.let { (it as JsonArray).map { e -> (e as JsonPrimitive).content } }?.contains("q") == true)
        assertTrue(search.defaultDescription.contains("that link alone is enough"))
        assertTrue(search.defaultDescription.contains("and no q"))
        val comments = tools[1]
        assertTrue(comments.defaultDescription.contains("Default 20 threads"))
        val download = tools[2]
        assertTrue(download.defaultDescription.contains("youtube.media.play"))
        assertTrue(download.defaultDescription.contains("ask the user"))
        // Transcript fix (2026-11-14): the app lists tracks first and picks
        // one itself; the AI must read the returned file, not re-call with
        // another language, and txt is the default format.
        assertTrue(download.defaultDescription.contains("lists the available subtitle tracks first"))
        assertTrue(download.defaultDescription.contains("do not call again with a different language"))
        assertTrue(download.defaultDescription.contains("plain text (txt) by default"))
        val kind = (download.schema["properties"] as JsonObject)["kind"] as JsonObject
        val kinds = (kind["enum"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("audio", "video", "transcript"), kinds)
        val format = (download.schema["properties"] as JsonObject)["format"] as JsonObject
        val formats = (format["enum"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("txt", "srt"), formats)
    }

    @Test
    fun toolsMdTemplateParsesBack() {
        val tools = YouTubeTools.build()
        val template = ToolsMdParser.buildTemplate(tools)
        assertTrue(template.contains("## youtube_search"))
        assertTrue(template.contains("## youtube_comments"))
        assertTrue(template.contains("## youtube_download"))
        val origins = tools.associate { it.uid to it.defaultDescription }
        val parsed = ToolsMdParser.parse(template, origins)
        assertEquals(listOf("youtube_search", "youtube_comments", "youtube_download"), parsed.map { it.uid })
    }
}
