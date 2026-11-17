package com.example.byokchat.ui

import com.example.byokchat.tools.Tool
import com.example.byokchat.tools.ToolGroup
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the tool-picker summary line on the agent settings screen, plus the grouped selection logic (RCP, 17/11/2026). */
class ToolPickerSheetTest {

    private fun tool(uid: String, group: ToolGroup) = Tool(
        uid = uid,
        group = group,
        risk = com.example.byokchat.tools.Risk.READ,
        defaultName = uid,
        defaultDescription = "Tool $uid.",
        schema = buildJsonObject {},
        run = { _, _ -> "" },
    )

    private val tools = listOf(
        tool("fs.list", ToolGroup.FILES),
        tool("fs.tree", ToolGroup.FILES),
        tool("txt.read", ToolGroup.TEXT),
        tool("time.now", ToolGroup.DAILY),
    )

    @Test
    fun groupsKeepGroupOrderAndRegistryOrderInside() {
        val grouped = groupTools(tools)
        assertEquals(
            listOf(ToolGroup.FILES, ToolGroup.TEXT, ToolGroup.DAILY),
            grouped.map { it.first },
        )
        assertEquals(listOf("fs.list", "fs.tree"), grouped[0].second.map { it.uid })
        assertEquals(listOf("txt.read"), grouped[1].second.map { it.uid })
    }

    @Test
    fun selectionStateCountsCorrectly() {
        assertEquals(SelectionState.NONE, selectionState(3, 0))
        assertEquals(SelectionState.SOME, selectionState(3, 1))
        assertEquals(SelectionState.SOME, selectionState(3, 2))
        assertEquals(SelectionState.ALL, selectionState(3, 3))
        assertEquals(SelectionState.NONE, selectionState(0, 0))
    }

    @Test
    fun toggleGroupSelectsAllUnlessAllAreSelected() {
        val group = listOf("fs.list", "fs.tree")
        assertEquals(setOf("fs.list", "fs.tree"), toggleGroup(emptySet(), group))
        assertEquals(setOf("fs.list", "fs.tree", "txt.read"), toggleGroup(setOf("fs.list", "txt.read"), group))
        assertEquals(setOf("txt.read"), toggleGroup(setOf("fs.list", "fs.tree", "txt.read"), group))
        // A group toggle never touches tools outside the group.
        assertEquals(setOf("time.now"), toggleGroup(setOf("time.now"), group) - setOf("fs.list", "fs.tree"))
    }

    @Test
    fun toggleAllSelectsOrDeselectsEverything() {
        val all = listOf("fs.list", "fs.tree", "txt.read")
        assertEquals(setOf("fs.list", "fs.tree", "txt.read"), toggleAll(emptySet(), all))
        assertEquals(setOf("fs.list", "fs.tree", "txt.read"), toggleAll(setOf("fs.list"), all))
        assertEquals(emptySet<String>(), toggleAll(setOf("fs.list", "fs.tree", "txt.read"), all))
    }

    @Test
    fun bulkSelectionNeedsAnEmptySearchBox() {
        assertTrue(bulkSelectionAllowed(""))
        assertTrue(bulkSelectionAllowed("   "))
        assertFalse(bulkSelectionAllowed("csv"))
        assertFalse(bulkSelectionAllowed(" csv "))
    }

    @Test
    fun toggleOneFlipsASingleTool() {
        assertEquals(setOf("fs.list"), toggleOne(emptySet(), "fs.list"))
        assertEquals(emptySet<String>(), toggleOne(setOf("fs.list"), "fs.list"))
    }


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
