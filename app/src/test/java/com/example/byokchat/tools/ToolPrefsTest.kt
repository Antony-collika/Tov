package com.example.byokchat.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the per-tool overrides in ToolPrefs (RCP, 17/11/2026). Pure only — ToolPrefs itself needs a Context. */
class ToolPrefsTest {

    @Test
    fun stripOverridesKeepsDescriptionButDropsNameAndParamDescriptions() {
        val pref = ToolPref(
            mode = "ASK",
            name = "renamed",
            description = "Edited description.",
            paramDescriptions = mapOf("path" to "Edited param."),
        )
        val stripped = ToolPrefs.stripOverrides(pref)
        assertEquals("ASK", stripped.mode)
        assertNull(stripped.name)
        assertEquals("Edited description.", stripped.description)
        assertTrue(stripped.paramDescriptions.isEmpty())
    }

    @Test
    fun entryWithOnlyADescriptionIsKept() {
        // update()/load() must keep an entry that only has a description
        // (no mode) — otherwise an edited description would vanish.
        assertFalse(ToolPref(description = "Edited.").isEmpty())
        assertFalse(ToolPref(mode = "OFF").isEmpty())
        assertTrue(ToolPref().isEmpty())
        assertTrue(ToolPref(mode = null, description = null).isEmpty())
    }

    @Test
    fun effectiveDescriptionPrefersANonBlankOverride() {
        assertEquals("Edited.", resolveEffectiveDescription("Original.", "Edited."))
        assertEquals("Original.", resolveEffectiveDescription("Original.", null))
        assertEquals("Original.", resolveEffectiveDescription("Original.", "   "))
        assertEquals("Original.", resolveEffectiveDescription("Original.", ""))
    }
}
