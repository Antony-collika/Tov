package com.example.byokchat.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho Đợt D: thư mục bảo vệ Tov/. */
class ProtectedPathTest {

    @Test
    fun tovFolderIsProtected() {
        assertTrue(isProtectedPath("Tov/prompts/system.md"))
        assertTrue(isProtectedPath("Tov"))
        assertTrue(isProtectedPath("Tov/"))
        assertTrue(isProtectedPath("/Tov/notes/x.md"))
        assertTrue(isProtectedPath("./Tov/chats/2026-11-10_ab12cd34/history.md"))
    }

    @Test
    fun protectedFolderIsDetectedBelowVirtualWorkspaceMount() {
        assertTrue(isProtectedPath("folder_01/Tov/prompts/system.md", listOf("folder_01")))
        assertTrue(isProtectedPath("folder_02/Tov", listOf("folder_02")))
        assertFalse(isProtectedPath("folder_01/Tov2/notes.md", listOf("folder_01")))
    }

    @Test
    fun otherPathsAreNotProtected() {
        assertFalse(isProtectedPath("notes/x.md"))
        assertFalse(isProtectedPath("Tov2/x.md"))
        assertFalse(isProtectedPath("myTov/x.md"))
        assertFalse(isProtectedPath(""))
        assertFalse(isProtectedPath("./notes/x.md"))
        assertFalse(isProtectedPath("tov/prompts/x.md"))
    }
}
