package com.example.byokchat.backup

import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.NoteEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupCodecTest {
    @Test
    fun `backup data round-trips through json`() {
        val data = BackupData(
            createdAt = 1_700_000_000_000,
            defaultPrompt = "Be helpful.",
            conversations = listOf(
                ConversationEntity("c1", "Chat one", 100, 200, parentId = null, pinned = true),
            ),
            messages = listOf(
                MessageEntity("m1", "c1", "t1", 1, 1L, "user", "hello", createdAt = 100),
            ),
            notes = listOf(NoteEntity("n1", "Note", "content", 100, 100)),
            images = mapOf("/old/path/a.jpg" to "images/a.jpg"),
        )
        val decoded = decodeBackupData(encodeBackupData(data))
        assertEquals(data, decoded)
    }

    @Test
    fun `unknown fields are ignored and missing fields take defaults`() {
        val json = """{"version":1,"dbVersion":11,"futureField":{"x":1},"defaultPrompt":"hi"}"""
        val decoded = decodeBackupData(json.toByteArray(Charsets.UTF_8))
        assertEquals("hi", decoded.defaultPrompt)
        assertEquals(emptyList<ConversationEntity>(), decoded.conversations)
        assertEquals("SYSTEM", decoded.theme)
    }

    @Test
    fun `future backup data version asks for an app update`() {
        val json = """{"version":99,"dbVersion":11}"""
        try {
            decodeBackupData(json.toByteArray(Charsets.UTF_8))
            fail("expected NeedsAppUpdate")
        } catch (e: BackupOpenError.NeedsAppUpdate) {
            // expected
        }
    }

    @Test
    fun `broken backup json is damaged`() {
        try {
            decodeBackupData("{not json".toByteArray(Charsets.UTF_8))
            fail("expected Damaged")
        } catch (e: BackupOpenError.Damaged) {
            // expected
        }
    }

    @Test
    fun `zip round-trip keeps backup json and images`() {
        val json = encodeBackupData(BackupData(defaultPrompt = "hi"))
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        val zip = buildBackupZip(json, mapOf("images/a.jpg" to imageBytes))
        val content = readBackupZip(zip)
        assertArrayEquals(json, content.backupJson)
        assertArrayEquals(imageBytes, content.images["images/a.jpg"])
        assertEquals("hi", decodeBackupData(content.backupJson).defaultPrompt)
    }

    @Test
    fun `zip without backup json is damaged`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("images/a.jpg"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        try {
            readBackupZip(out.toByteArray())
            fail("expected Damaged")
        } catch (e: BackupOpenError.Damaged) {
            // expected
        }
    }

    @Test
    fun `zip-slip entries are rejected`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(encodeBackupData(BackupData()))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../evil.txt"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        try {
            readBackupZip(out.toByteArray())
            fail("expected Damaged")
        } catch (e: BackupOpenError.Damaged) {
            // expected
        }
    }

    @Test
    fun `full file round-trip through crypto and zip`() {
        val data = BackupData(defaultPrompt = "Be helpful.", notes = listOf(NoteEntity("n1", "T", "C", 1, 1)))
        val zip = buildBackupZip(encodeBackupData(data), mapOf("images/a.jpg" to byteArrayOf(9, 9)))
        val envelope = encryptBackupFile(zip, "pw".toCharArray())
        assertTrue(isBackupFile(envelope))
        val (decoded, images) = openBackupFile(envelope, "pw".toCharArray())
        assertEquals("Be helpful.", decoded.defaultPrompt)
        assertEquals(1, decoded.notes.size)
        assertArrayEquals(byteArrayOf(9, 9), images["images/a.jpg"])
    }
}
