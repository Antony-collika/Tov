package com.example.byokchat.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SoundCloud tools (2026-11-14): soundcloud_search + soundcloud_download, no key. */
class SoundCloudToolsTest {

    @Test
    fun twoToolsWithLockedUidsNamesAndDefaults() {
        val tools = SoundCloudTools.build()
        assertEquals(listOf("soundcloud_search", "soundcloud_download"), tools.map { it.uid })
        assertEquals(listOf("soundcloud_search", "soundcloud_download"), tools.map { it.defaultName })
        assertEquals(ToolGroup.MEDIA, tools[0].group)
        assertEquals(ToolGroup.MEDIA, tools[1].group)
        assertEquals(Risk.READ, tools[0].risk)
        assertEquals(Risk.WRITE, tools[1].risk) // download writes files -> ASK by default
        assertEquals(ToolMode.AUTO, tools[0].defaultMode)
        assertEquals(ToolMode.ASK, tools[1].defaultMode)
        assertFalse(tools[0].needsWorkspace) // search needs no API key and no folder
        assertTrue(tools[1].needsWorkspace) // download needs a Playground folder
    }

    @Test
    fun searchAndDownloadSchemasHaveLockedDefaultsInDescriptions() {
        val tools = SoundCloudTools.build()
        val search = tools[0]
        assertTrue(search.defaultDescription.contains("default 20"))
        assertTrue(search.defaultDescription.contains("No API key needed"))
        assertTrue(search.defaultDescription.contains("soundcloud.media.play"))
        assertTrue(search.defaultDescription.contains("soundcloud_download"))
        assertTrue(search.schema["required"]?.let { (it as JsonArray).map { e -> (e as JsonPrimitive).content } }?.contains("q") == true)
        val download = tools[1]
        assertTrue(download.defaultDescription.contains("ask the user"))
        val props = download.schema["properties"] as JsonObject
        assertTrue(props.containsKey("track"))
        assertTrue(props.containsKey("path"))
    }

    @Test
    fun toolsMdTemplateParsesBack() {
        val tools = SoundCloudTools.build()
        val template = ToolsMdParser.buildTemplate(tools)
        assertTrue(template.contains("## soundcloud_search"))
        assertTrue(template.contains("## soundcloud_download"))
        val origins = tools.associate { it.uid to it.defaultDescription }
        val parsed = ToolsMdParser.parse(template, origins)
        assertEquals(listOf("soundcloud_search", "soundcloud_download"), parsed.map { it.uid })
        assertEquals("soundcloud_search", parsed[0].name)
        assertEquals("soundcloud_download", parsed[1].name)
    }
}
