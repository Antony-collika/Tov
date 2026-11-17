package com.example.byokchat.backup

import com.example.byokchat.ai.MetadataParams
import com.example.byokchat.chat.SubAgentConfig
import com.example.byokchat.data.ConversationEntity
import com.example.byokchat.data.MessageEntity
import com.example.byokchat.data.NoteEntity
import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.RecentlyPlayedEntity
import com.example.byokchat.data.SubRunEntity
import com.example.byokchat.data.TodoEntity
import com.example.byokchat.tools.ToolPref
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Format version of backup.json. Bump when its shape changes incompatibly. */
const val BACKUP_DATA_VERSION = 1

/** DB version the backup was taken from (Room version at export time). */
const val BACKUP_DB_VERSION = 11

/**
 * The whole backup, as stored in backup.json inside the encrypted ZIP.
 * DB only (Duy, 2026-11-17): no files from the playground, no session
 * files, no SAF permissions. The Request Control Panel layout is NOT
 * backed up at all (Duy, 2026-11-17 ~15:16: it is easy to set up again,
 * not worth backing up) — no `layout`, no selected tool uids. An agent's
 * `configFile` link is kept as a plain string only — the file itself is
 * not in the backup.
 *
 * Every field has a default so an older backup still reads on a newer
 * app (the loader uses ignoreUnknownKeys too).
 */
@Serializable
data class BackupData(
    val version: Int = BACKUP_DATA_VERSION,
    val dbVersion: Int = BACKUP_DB_VERSION,
    val createdAt: Long = 0,
    val appName: String = "Tov",
    /** API providers (with keys). */
    val providers: List<ProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
    val currentModelProviderId: String? = null,
    val currentModel: String? = null,
    val metadata: MetadataParams = MetadataParams(),
    /** Web search keys, one per engine ("BRAVE" / "TAVILY" / "SERPER"). */
    val searchKeys: Map<String, String> = emptyMap(),
    val searchProvider: String = "BRAVE",
    val youtubeKey: String = "",
    /** Per-tool edits (mode + edited description), by tool uid. */
    val toolPrefs: Map<String, ToolPref> = emptyMap(),
    /** The sub-agent team (agents.json content). */
    val agents: List<SubAgentConfig> = emptyList(),
    val defaultPrompt: String = "",
    /** ThemeMode name ("SYSTEM" / "LIGHT" / "DARK"). */
    val theme: String = "SYSTEM",
    val maxToolRounds: Int = 12,
    val requestLogEnabled: Boolean = true,
    /** Display names only — SAF folder permissions cannot be restored, so
     * these only remind the user which folders to pick again. */
    val playgroundFolderNames: List<String> = emptyList(),
    val sessionFolderName: String? = null,
    // --- Room tables (DB only) ---
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val subRuns: List<SubRunEntity> = emptyList(),
    val recentlyPlayed: List<RecentlyPlayedEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val todos: List<TodoEntity> = emptyList(),
    /**
     * Attached images: old absolute path -> ZIP entry name ("images/<file>").
     * The message rows keep their old paths; restore rewrites them to the
     * new filesDir.
     */
    val images: Map<String, String> = emptyMap(),
)

private val backupJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun encodeBackupData(data: BackupData): ByteArray =
    backupJson.encodeToString(BackupData.serializer(), data).toByteArray(Charsets.UTF_8)

/**
 * Decode backup.json bytes. Throws [BackupOpenError.NeedsAppUpdate] when
 * the backup is from a newer app version, [BackupOpenError.Damaged]
 * when the JSON is broken.
 */
fun decodeBackupData(bytes: ByteArray): BackupData {
    val text = try {
        bytes.toString(Charsets.UTF_8)
    } catch (e: Exception) {
        throw BackupOpenError.Damaged()
    }
    val data = try {
        backupJson.decodeFromString<BackupData>(text)
    } catch (e: Exception) {
        throw BackupOpenError.Damaged()
    }
    if (data.version > BACKUP_DATA_VERSION) throw BackupOpenError.NeedsAppUpdate()
    if (data.version < BACKUP_DATA_VERSION) throw BackupOpenError.Damaged()
    return data
}

/** One backup ZIP: the backup.json bytes plus the image entries (name -> bytes). */
data class BackupZipContent(val backupJson: ByteArray, val images: Map<String, ByteArray>)

/**
 * Build the plaintext ZIP: backup.json at the root plus the attached
 * images under images/. Pure, so it can be unit-tested.
 */
fun buildBackupZip(backupJsonBytes: ByteArray, images: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry("backup.json"))
        zip.write(backupJsonBytes)
        zip.closeEntry()
        for ((name, bytes) in images) {
            require(name.startsWith("images/") && ".." !in name) { "Bad image entry name: $name" }
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/**
 * Read a backup ZIP back. Rejects entries that escape the ZIP (zip-slip:
 * "..", absolute paths) and a ZIP without backup.json.
 * Throws [BackupOpenError.Damaged] on any problem.
 */
fun readBackupZip(zipBytes: ByteArray): BackupZipContent {
    var backupJsonBytes: ByteArray? = null
    val images = mutableMapOf<String, ByteArray>()
    try {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name.startsWith("/") || name.split("/").any { it == ".." || it == "." }) {
                    throw BackupOpenError.Damaged()
                }
                val bytes = zip.readBytes()
                when {
                    name == "backup.json" -> backupJsonBytes = bytes
                    name.startsWith("images/") && !entry.isDirectory -> images[name] = bytes
                    // Unknown entries are ignored: a newer app may add some.
                }
                zip.closeEntry()
            }
        }
    } catch (e: BackupOpenError) {
        throw e
    } catch (e: Exception) {
        throw BackupOpenError.Damaged()
    }
    return BackupZipContent(
        backupJson = backupJsonBytes ?: throw BackupOpenError.Damaged(),
        images = images,
    )
}

/** Decrypt a backup file and decode the backup inside it. */
fun openBackupFile(envelopeText: String, password: CharArray): Pair<BackupData, Map<String, ByteArray>> {
    val zipBytes = decryptBackupFile(envelopeText, password)
    val content = readBackupZip(zipBytes)
    return decodeBackupData(content.backupJson) to content.images
}
