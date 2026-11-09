package com.example.byokchat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortDescriptionTest {
    @Test fun takesTheFirstSentence() {
        assertEquals("List the files in a folder.", shortDescription("List the files in a folder. Leave path empty for the root."))
    }

    @Test fun keepsTextWithoutFullStop() {
        assertEquals("Count words", shortDescription("Count words"))
    }

    @Test fun longSentenceIsCut() {
        val s = shortDescription("a".repeat(300) + ".", max = 50)
        assertEquals(50, s.length)
        assertTrue(s.endsWith("…"))
    }

    @Test fun lineBreaksBecomeSpaces() {
        assertEquals("Read a note by id.", shortDescription("Read a note\nby id."))
    }
}
