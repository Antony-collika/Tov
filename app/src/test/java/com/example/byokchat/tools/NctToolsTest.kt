package com.example.byokchat.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NhacCuaTui tools (2026-11-14): nct_search + nct_download, no info tool. */
class NctToolsTest {

    @Test
    fun twoToolsWithLockedUidsNamesAndDefaults() {
        val tools = NctTools.build()
        assertEquals(listOf("nct_search", "nct_download"), tools.map { it.uid })
        assertEquals(listOf("nct_search", "nct_download"), tools.map { it.defaultName })
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
        val tools = NctTools.build()
        val search = tools[0]
        assertTrue(search.defaultDescription.contains("default 20"))
        assertTrue(search.defaultDescription.contains("VIP only"))
        assertTrue(search.defaultDescription.contains("nct.media.play"))
        assertTrue(search.defaultDescription.contains("nct_download"))
        assertTrue(search.schema["required"]?.let { (it as JsonArray).map { e -> (e as JsonPrimitive).content } }?.contains("q") == true)
        val download = tools[1]
        assertTrue(download.defaultDescription.contains("ask the user"))
        assertTrue(download.defaultDescription.contains("FLAC"))
        assertTrue(download.defaultDescription.contains("VIP-only"))
        val quality = (download.schema["properties"] as JsonObject)["quality"] as JsonObject
        val qualities = (quality["enum"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("hq", "128", "flac"), qualities)
    }

    @Test
    fun toolsMdTemplateParsesBack() {
        val tools = NctTools.build()
        val template = ToolsMdParser.buildTemplate(tools)
        assertTrue(template.contains("## nct_search"))
        assertTrue(template.contains("## nct_download"))
        val origins = tools.associate { it.uid to it.defaultDescription }
        val parsed = ToolsMdParser.parse(template, origins)
        assertEquals(listOf("nct_search", "nct_download"), parsed.map { it.uid })
        assertEquals("nct_search", parsed[0].name)
        assertEquals("nct_download", parsed[1].name)
    }
}
