package com.example.byokchat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the tool-picker summary line on the agent settings screen. */
class ToolPickerSheetTest {

    private val names = mapOf(
        "web.search" to "web_search",
        "web.fetch" to "fetch_url",
    )

    @Test fun emptySelectionSaysSo() {
        assertEquals("No tools selected", toolSelectionSummary(emptySet(), names))
    }

    @Test fun oneToolUsesTheSingular() {
        assertEquals("1 tool: web_search", toolSelectionSummary(setOf("web.search"), names))
    }

    @Test fun severalToolsAreListedByName() {
        assertEquals(
            "2 tools: web_search, fetch_url",
            toolSelectionSummary(setOf("web.search", "web.fetch"), names),
        )
    }

    @Test fun unknownUidIsShownAsIs() {
        assertEquals(
            "2 tools: web_search, future.tool",
            toolSelectionSummary(setOf("web.search", "future.tool"), names),
        )
    }
}
