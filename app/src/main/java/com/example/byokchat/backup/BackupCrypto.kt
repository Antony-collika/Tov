package com.example.byokchat.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup file crypto (2026-11-17): one encrypted file, no online sync.
 *
 * The format follows Duy's ClipNest backup file (he showed one on
 * 2026-11-17): a plain JSON envelope a text editor can read the
 * structure of — format, version, cipher, kdf, iterations, salt, nonce,
 * ciphertext (all base64) — opened only by the app with the password.
 * The plaintext inside the ciphertext is a ZIP (see BackupCodec).
 *
 * Password rule (Duy, 2026-11-17 ~15:14): ANY password, no length or
 * character constraints — the user decides. The only rule is that it
 * must not be empty, because the file is always password-encrypted.
 * The password is never stored anywhere in the app.
 */
const val BACKUP_FORMAT = "tov-encrypted-document"
const val BACKUP_FORMAT_VERSION = 1
const val BACKUP_CIPHER = "AES-256-GCM"
const val BACKUP_KDF = "PBKDF2WithHmacSHA256"
const val BACKUP_KDF_ITERATIONS = 210_000
private const val SALT_BYTES = 16
private const val NONCE_BYTES = 12
private const val KEY_BITS = 256
private const val GCM_TAG_BITS = 128

/** The JSON envelope of one backup file. Field names match ClipNest's. */
@Serializable
data class BackupEnvelope(
    val format: String,
    val version: Int,
    val cipher: String,
    val kdf: String,
    val iterations: Int,
    /** Base64 salt for the key derivation. */
    val salt: String,
    /** Base64 nonce (IV) for AES-GCM. */
    val nonce: String,
    /** Base64 ciphertext (AES-GCM tag appended by the cipher). */
    val ciphertext: String,
)

/** Why a backup file could not be opened. Messages are shown to the user as-is. */
sealed class BackupOpenError(message: String) : Exception(message) {
    /** Not a Tov backup at all (wrong format, unreadable JSON, unknown cipher/kdf). */
    class NotABackup : BackupOpenError("This is not a Tov backup file.")

    /** Made by a newer version of the app than this one. */
    class NeedsAppUpdate : BackupOpenError(
        "This backup was made by a newer version of the app. Update the app to restore it."
    )

    /**
     * AES-GCM authentication failed: with GCM a wrong password and a
     * damaged file are indistinguishable, so the message says exactly
     * that instead of inventing a reason.
     */
    class WrongPasswordOrCorrupt : BackupOpenError("Wrong password or the file is damaged.")

    /** Decrypted fine, but the content inside (ZIP / backup.json) is broken. */
    class Damaged : BackupOpenError("This backup file is damaged.")
}

private val envelopeJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
    return try {
        SecretKeyFactory.getInstance(BACKUP_KDF).generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

/**
 * Encrypt [plainBytes] (the backup ZIP) under [password] and return the
 * JSON envelope text to save as the backup file. Random salt + nonce
 * every time, so two backups with the same password never look alike.
 * The password array is wiped before returning.
 */
fun encryptBackupFile(plainBytes: ByteArray, password: CharArray): String {
    val random = SecureRandom()
    val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
    val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
    return encryptBackupFile(plainBytes, password, salt, nonce)
}

/** Same as [encryptBackupFile] but with a fixed salt/nonce — for tests only, so a fixture is reproducible. */
internal fun encryptBackupFile(plainBytes: ByteArray, password: CharArray, salt: ByteArray, nonce: ByteArray): String {
    val key = deriveKey(password, salt, BACKUP_KDF_ITERATIONS)
    try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plainBytes)
        val envelope = BackupEnvelope(
            format = BACKUP_FORMAT,
            version = BACKUP_FORMAT_VERSION,
            cipher = BACKUP_CIPHER,
            kdf = BACKUP_KDF,
            iterations = BACKUP_KDF_ITERATIONS,
            salt = b64(salt),
            nonce = b64(nonce),
            ciphertext = b64(ciphertext),
        )
        return envelopeJson.encodeToString(BackupEnvelope.serializer(), envelope)
    } finally {
        password.fill('\u0000')
        key.fill(0)
    }
}

/**
 * Is this text a Tov backup file at all? Checks only the envelope's
 * format field — no password needed. Used to reject a wrong file
 * BEFORE asking for the password.
 */
fun isBackupFile(text: String): Boolean = runCatching {
    val envelope = envelopeJson.decodeFromString<BackupEnvelope>(text)
    envelope.format == BACKUP_FORMAT && envelope.cipher == BACKUP_CIPHER && envelope.kdf == BACKUP_KDF
}.getOrDefault(false)

/** Envelope version of a backup file, or null when the text is not a backup at all. */
fun backupFileVersion(text: String): Int? = runCatching {
    envelopeJson.decodeFromString<BackupEnvelope>(text)
        .takeIf { it.format == BACKUP_FORMAT }?.version
}.getOrNull()

/**
 * Decrypt a backup file's envelope text under [password] and return the
 * plaintext ZIP bytes. The password array is wiped before returning.
 * Throws [BackupOpenError]: [BackupOpenError.NotABackup] when the
 * file is not a Tov backup, [BackupOpenError.NeedsAppUpdate] when it
 * was made by a newer app version, [BackupOpenError.WrongPasswordOrCorrupt]
 * when the password is wrong or the file was damaged.
 */
fun decryptBackupFile(envelopeText: String, password: CharArray): ByteArray {
    val envelope = try {
        envelopeJson.decodeFromString<BackupEnvelope>(envelopeText)
    } catch (e: Exception) {
        throw BackupOpenError.NotABackup()
    }
    if (envelope.format != BACKUP_FORMAT || envelope.cipher != BACKUP_CIPHER || envelope.kdf != BACKUP_KDF) {
        throw BackupOpenError.NotABackup()
    }
    if (envelope.version > BACKUP_FORMAT_VERSION) throw BackupOpenError.NeedsAppUpdate()
    if (envelope.version < BACKUP_FORMAT_VERSION) throw BackupOpenError.NotABackup()
    val salt: ByteArray
    val nonce: ByteArray
    val ciphertext: ByteArray
    try {
        salt = unb64(envelope.salt)
        nonce = unb64(envelope.nonce)
        ciphertext = unb64(envelope.ciphertext)
    } catch (e: IllegalArgumentException) {
        throw BackupOpenError.NotABackup()
    }
    if (salt.size != SALT_BYTES || nonce.size != NONCE_BYTES) throw BackupOpenError.NotABackup()
    val key = deriveKey(password, salt, envelope.iterations)
    try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    } catch (e: AEADBadTagException) {
        throw BackupOpenError.WrongPasswordOrCorrupt()
    } catch (e: Exception) {
        throw BackupOpenError.WrongPasswordOrCorrupt()
    } finally {
        password.fill('\u0000')
        key.fill(0)
    }
}
