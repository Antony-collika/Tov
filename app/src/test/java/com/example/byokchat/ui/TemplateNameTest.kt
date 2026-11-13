package com.example.byokchat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Test logic thuần chọn tên file mẫu chưa trùng ("Create template" — quyết định 13/11/2026). */
class TemplateNameTest {

    @Test
    fun freeBaseNameIsUsedAsIs() {
        assertEquals("tools_template.md", uniqueTemplateName("tools_template.md") { false })
    }

    @Test
    fun firstConflictGets01() {
        val taken = setOf("tools_template.md")
        assertEquals("tools_template_(01).md", uniqueTemplateName("tools_template.md") { it in taken })
    }

    @Test
    fun skipsTakenNumbersInOrder() {
        val taken = setOf("tools_template.md", "tools_template_(01).md", "tools_template_(02).md")
        assertEquals("tools_template_(03).md", uniqueTemplateName("tools_template.md") { it in taken })
    }

    @Test
    fun firstFreeNumberWinsOverLaterGap() {
        val taken = setOf("tools_template.md", "tools_template_(02).md")
        assertEquals("tools_template_(01).md", uniqueTemplateName("tools_template.md") { it in taken })
    }

    @Test
    fun padsToTwoDigitsThenGrows() {
        val taken = (0..9).map {
            if (it == 0) "tools_template.md" else "tools_template_(%02d).md".format(it)
        }.toSet()
        assertEquals("tools_template_(10).md", uniqueTemplateName("tools_template.md") { it in taken })
    }

    @Test
    fun keepsExtensionForOtherKinds() {
        val taken = setOf("system_template.md")
        assertEquals("system_template_(01).md", uniqueTemplateName("system_template.md") { it in taken })
    }

    @Test
    fun nameWithoutExtensionGetsSuffixAtEnd() {
        val taken = setOf("notes_template")
        assertEquals("notes_template_(01)", uniqueTemplateName("notes_template") { it in taken })
    }
}
