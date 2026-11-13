package com.example.byokchat.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test logic thuần của playground nhiều folder (handoff §7). */
class PlaygroundTest {

    private val roots = listOf("folder_01", "Reports")

    // ---------- Tách đoạn đường dẫn ----------

    @Test
    fun emptyAndDotAreNoSegments() {
        assertEquals(emptyList<String>(), parsePlaygroundSegments(""))
        assertEquals(emptyList<String>(), parsePlaygroundSegments("."))
        assertEquals(emptyList<String>(), parsePlaygroundSegments("  "))
    }

    @Test
    fun segmentsSplitAndIgnoreEmptyAndDotParts() {
        assertEquals(listOf("folder_01", "a", "b.md"), parsePlaygroundSegments("folder_01/a/b.md"))
        assertEquals(listOf("folder_01", "a"), parsePlaygroundSegments("/folder_01/./a/"))
        assertEquals(listOf("folder_01", "a"), parsePlaygroundSegments("folder_01\\a"))
    }

    @Test
    fun dotDotIsBlocked() {
        assertThrows(ToolException::class.java) { parsePlaygroundSegments("folder_01/../secret.md") }
        assertThrows(ToolException::class.java) { parsePlaygroundSegments("..") }
    }

    @Test
    fun colonAndNulAreBlocked() {
        assertThrows(ToolException::class.java) { parsePlaygroundSegments("folder_01/a:b.md") }
        assertThrows(ToolException::class.java) { parsePlaygroundSegments("folder_01/a\u0000b.md") }
    }

    // ---------- Xác định vị trí ----------

    @Test
    fun emptyPathIsVirtualRoot() {
        assertEquals(PlaygroundPath.VirtualRoot, resolvePlaygroundPath(roots, emptyList()))
        assertEquals(PlaygroundPath.VirtualRoot, resolvePlaygroundPath(roots, parsePlaygroundSegments("")))
        assertEquals(PlaygroundPath.VirtualRoot, resolvePlaygroundPath(roots, parsePlaygroundSegments(".")))
    }

    @Test
    fun singleSegmentIsThePlaygroundFolderItself() {
        assertEquals(
            PlaygroundPath.PlaygroundFolder(0, "folder_01"),
            resolvePlaygroundPath(roots, parsePlaygroundSegments("folder_01")),
        )
        assertEquals(
            PlaygroundPath.PlaygroundFolder(1, "Reports"),
            resolvePlaygroundPath(roots, parsePlaygroundSegments("Reports")),
        )
    }

    @Test
    fun deeperPathIsInsideAFolder() {
        assertEquals(
            PlaygroundPath.InsideFolder(0, "folder_01", listOf("a", "b.md")),
            resolvePlaygroundPath(roots, parsePlaygroundSegments("folder_01/a/b.md")),
        )
    }

    @Test
    fun firstSegmentMatchesCaseInsensitively() {
        assertEquals(
            PlaygroundPath.PlaygroundFolder(0, "folder_01"),
            resolvePlaygroundPath(roots, parsePlaygroundSegments("FOLDER_01")),
        )
        assertEquals(
            PlaygroundPath.InsideFolder(1, "Reports", listOf("x.md")),
            resolvePlaygroundPath(roots, parsePlaygroundSegments("reports/x.md")),
        )
    }

    @Test
    fun unknownFirstSegmentFailsWithFolderList() {
        val e = assertThrows(ToolException::class.java) {
            resolvePlaygroundPath(roots, parsePlaygroundSegments("nope/x.md"))
        }
        assertTrue(e.message!!.contains("nope"))
        assertTrue(e.message!!.contains("folder_01"))
        assertTrue(e.message!!.contains("Reports"))
    }

    // ---------- Trùng tên ----------

    @Test
    fun nameConflictIsCaseInsensitive() {
        assertTrue(playgroundNameConflict(roots, "folder_01"))
        assertTrue(playgroundNameConflict(roots, "FOLDER_01"))
        assertTrue(playgroundNameConflict(roots, "reports"))
        assertFalse(playgroundNameConflict(roots, "other"))
        assertFalse(playgroundNameConflict(emptyList(), "folder_01"))
    }

    // ---------- Ma trận thao tác (§4.5) ----------

    @Test
    fun virtualRootAllowsOnlyReading() {
        val loc = PlaygroundPath.VirtualRoot
        assertTrue(isPlaygroundOpAllowed(PlaygroundOp.READ, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.READ_FILE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.WRITE_FILE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.MAKE_DIR, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.DELETE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.RENAME, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.MOVE_SOURCE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.MOVE_DEST, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.COPY_SOURCE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.COPY_DEST, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.ZIP_SOURCE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.ZIP_DEST, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.UNZIP_DEST, loc))
    }

    @Test
    fun playgroundFolderAllowsReadingAndUnzipIntoIt() {
        val loc = PlaygroundPath.PlaygroundFolder(0, "folder_01")
        assertTrue(isPlaygroundOpAllowed(PlaygroundOp.READ, loc))
        assertTrue(isPlaygroundOpAllowed(PlaygroundOp.UNZIP_DEST, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.READ_FILE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.WRITE_FILE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.MAKE_DIR, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.DELETE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.RENAME, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.MOVE_SOURCE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.MOVE_DEST, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.COPY_SOURCE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.COPY_DEST, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.ZIP_SOURCE, loc))
        assertFalse(isPlaygroundOpAllowed(PlaygroundOp.ZIP_DEST, loc))
    }

    @Test
    fun insideAFolderAllowsEverything() {
        val loc = PlaygroundPath.InsideFolder(0, "folder_01", listOf("notes.md"))
        for (op in PlaygroundOp.entries) {
            assertTrue("expected $op to be allowed inside a folder", isPlaygroundOpAllowed(op, loc))
        }
    }

    // ---------- Allowed write subfolder (§4.6, §7) ----------

    @Test
    fun underSubfolderChecksFolderNameFirst() {
        assertTrue(underWriteSubfolder("folder_01/notes/x.md", "notes"))
        assertTrue(underWriteSubfolder("folder_01/notes", "notes"))
        assertFalse(underWriteSubfolder("notes/x.md", "notes"))
        assertFalse(underWriteSubfolder("folder_01/other/x.md", "notes"))
        assertFalse(underWriteSubfolder("folder_01", "notes"))
        assertFalse(underWriteSubfolder("", "notes"))
    }

    @Test
    fun emptySubfolderAllowsAnythingInsideFolders() {
        assertTrue(underWriteSubfolder("folder_01/x.md", ""))
        assertTrue(underWriteSubfolder("folder_01/notes/x.md", ""))
    }

    @Test
    fun underSubfolderSupportsNestedSubfolders() {
        assertTrue(underWriteSubfolder("folder_01/a/b/x.md", "a/b"))
        assertFalse(underWriteSubfolder("folder_01/a/x.md", "a/b"))
    }
}
