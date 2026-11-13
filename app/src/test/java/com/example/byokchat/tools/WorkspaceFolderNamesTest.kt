package com.example.byokchat.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFolderNamesTest {
    @Test
    fun sameFolderNameIsRejectedCaseInsensitively() {
        assertTrue(hasWorkspaceFolderNameConflict(listOf("Projects", "Notes"), "projects"))
    }

    @Test
    fun differentFolderNameIsAccepted() {
        assertFalse(hasWorkspaceFolderNameConflict(listOf("Projects", "Notes"), "Downloads"))
    }

    @Test
    fun emptyWorkspaceAcceptsFirstFolder() {
        assertFalse(hasWorkspaceFolderNameConflict(emptyList(), "Projects"))
    }
}
