package com.example.byokchat.weather

import com.example.byokchat.tools.Risk
import com.example.byokchat.tools.ToolGroup
import com.example.byokchat.tools.ToolMode
import com.example.byokchat.tools.ToolsMdParser
import com.example.byokchat.tools.WeatherTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** weather_forecast (2026-11-14): one tool, Daily group, READ, no key. */
class WeatherToolsTest {

    @Test
    fun oneToolWithLockedUidNameAndDefaults() {
        val tools = WeatherTools.build()
        assertEquals(listOf("weather_forecast"), tools.map { it.uid })
        assertEquals(listOf("weather_forecast"), tools.map { it.defaultName })
        assertEquals(ToolGroup.DAILY, tools[0].group)
        assertEquals(Risk.READ, tools[0].risk)
        assertEquals(ToolMode.AUTO, tools[0].defaultMode) // READ -> AUTO by default
        assertFalse(tools[0].needsWorkspace) // needs only the network, no Playground folder
    }

    @Test
    fun schemaRequiresPlaceAndDescriptionNamesNoKey() {
        val tool = WeatherTools.build()[0]
        assertTrue(tool.defaultDescription.contains("no API key"))
        assertTrue(tool.defaultDescription.contains("lat,lon"))
        assertTrue(tool.defaultDescription.contains("at most 7"))
        val required = (tool.schema["required"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("place"), required)
    }

    @Test
    fun toolsMdTemplateParsesBack() {
        val tools = WeatherTools.build()
        val template = ToolsMdParser.buildTemplate(tools)
        assertTrue(template.contains("## weather_forecast"))
        val origins = tools.associate { it.uid to it.defaultDescription }
        val parsed = ToolsMdParser.parse(template, origins)
        assertEquals(listOf("weather_forecast"), parsed.map { it.uid })
    }
}
