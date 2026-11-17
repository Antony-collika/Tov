package com.example.byokchat.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {
    private val plain = "backup zip bytes — pretend this is a ZIP".toByteArray(Charsets.UTF_8)

    @Test
    fun `round-trip with a one-character password`() {
        // Password rule (Duy, 2026-11-17): ANY password, no length
        // constraint — a 1-character password must work.
        val envelope = encryptBackupFile(plain, "x".toCharArray())
        val back = decryptBackupFile(envelope, "x".toCharArray())
        assertArrayEquals(plain, back)
    }

    @Test
    fun `round-trip with a long password`() {
        val envelope = encryptBackupFile(plain, "correct horse battery staple".toCharArray())
        val back = decryptBackupFile(envelope, "correct horse battery staple".toCharArray())
        assertArrayEquals(plain, back)
    }

    @Test
    fun `two encryptions of the same bytes never look alike`() {
        val first = encryptBackupFile(plain, "pw".toCharArray())
        val second = encryptBackupFile(plain, "pw".toCharArray())
        assertFalse(first == second)
    }

    @Test
    fun `wrong password fails`() {
        val envelope = encryptBackupFile(plain, "right".toCharArray())
        try {
            decryptBackupFile(envelope, "wrong".toCharArray())
            fail("expected WrongPasswordOrCorrupt")
        } catch (e: BackupOpenError.WrongPasswordOrCorrupt) {
            // expected
        }
    }

    @Test
    fun `tampered ciphertext fails`() {
        val envelope = encryptBackupFile(plain, "pw".toCharArray())
        // Flip one base64 character inside the ciphertext value (GCM tag must catch it).
        val marker = "\"ciphertext\": \""
        val at = envelope.indexOf(marker) + marker.length
        val tampered = envelope.substring(0, at) +
            (if (envelope[at] == 'A') 'B' else 'A') + envelope.substring(at + 1)
        try {
            decryptBackupFile(tampered, "pw".toCharArray())
            fail("expected WrongPasswordOrCorrupt")
        } catch (e: BackupOpenError.WrongPasswordOrCorrupt) {
            // expected
        }
    }

    @Test
    fun `not a backup file is rejected before any password is tried`() {
        assertFalse(isBackupFile("hello, this is not JSON"))
        assertFalse(isBackupFile("""{"format":"something-else","version":1}"""))
        try {
            decryptBackupFile("hello, this is not JSON", "pw".toCharArray())
            fail("expected NotABackup")
        } catch (e: BackupOpenError.NotABackup) {
            // expected
        }
    }

    @Test
    fun `future envelope version asks for an app update`() {
        val envelope = encryptBackupFile(plain, "pw".toCharArray())
            .replace("\"version\": 1", "\"version\": 99")
        assertEquals(99, backupFileVersion(envelope))
        try {
            decryptBackupFile(envelope, "pw".toCharArray())
            fail("expected NeedsAppUpdate")
        } catch (e: BackupOpenError.NeedsAppUpdate) {
            // expected
        }
    }

    @Test
    fun `envelope carries the ClipNest-style fields`() {
        // Fixed salt/nonce so the envelope is reproducible: the fields
        // (not the ciphertext) are what later versions must keep reading.
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val envelope = encryptBackupFile(plain, "pw".toCharArray(), salt, nonce)
        assertTrue(isBackupFile(envelope))
        assertEquals(1, backupFileVersion(envelope))
        assertTrue(envelope.contains("\"format\": \"$BACKUP_FORMAT\""))
        assertTrue(envelope.contains("\"cipher\": \"$BACKUP_CIPHER\""))
        assertTrue(envelope.contains("\"kdf\": \"$BACKUP_KDF\""))
        assertTrue(envelope.contains("\"iterations\": $BACKUP_KDF_ITERATIONS"))
        val back = decryptBackupFile(envelope, "pw".toCharArray())
        assertArrayEquals(plain, back)
    }

    @Test
    fun `decrypt wipes the password array`() {
        val password = "secret".toCharArray()
        val envelope = encryptBackupFile(plain, "secret".toCharArray())
        decryptBackupFile(envelope, password)
        assertTrue(password.all { it == '\u0000' })
    }
}
