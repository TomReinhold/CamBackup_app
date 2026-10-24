package de.trei.cambackup.backup

import de.trei.cambackup.work.JobControl
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import de.trei.cambackup.freeup.asVerifiedOnly
import de.trei.cambackup.index.BackupIndex
import de.trei.cambackup.index.IndexCache
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.media.MediaScanner
import de.trei.cambackup.media.originalUri
import de.trei.cambackup.settings.SettingsRepository
import de.trei.cambackup.storage.DeviceRegistry
import de.trei.cambackup.storage.DirCache
import de.trei.cambackup.storage.DriveRepository
import de.trei.cambackup.storage.SafIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant
import de.trei.cambackup.R
import de.trei.cambackup.util.AppError
import de.trei.cambackup.util.Msg
import de.trei.cambackup.util.msg
import de.trei.cambackup.util.toMsg

/** Entscheidungen des Nutzers je Pfad auf dem Smartphone. */
@Serializable
data class MissingDecisions(val deviceFolder: String, val actions: Map<String, MissingAction>)

@Serializable
data class MissingSummary(
    val finishedAt: String,
    val restored: Int,
    val deleted: Int,
    val failures: List<RunSummary.Failure>,
    val abortReason: String?,
)

/** Ablage für Entscheidungen (zu groß für WorkManager-Daten) und das letzte Ergebnis. */
class MissingStore private constructor(context: Context) {
    private val dir = File(context.filesDir, "missing").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val decisionsFile = File(dir, "decisions.json")
    private val summaryFile = File(dir, "summary.json")

    private val _summary = MutableStateFlow(runCatching { json.decodeFromString(MissingSummary.serializer(), summaryFile.readText()) }.getOrNull())
    val summary: StateFlow<MissingSummary?> = _summary.asStateFlow()

    fun saveDecisions(d: MissingDecisions) = decisionsFile.writeText(json.encodeToString(MissingDecisions.serializer(), d))

    fun decisions(): MissingDecisions? = runCatching { json.decodeFromString(MissingDecisions.serializer(), decisionsFile.readText()) }.getOrNull()

    fun saveSummary(s: MissingSummary?) {
        if (s == null) summaryFile.delete() else summaryFile.writeText(json.encodeToString(MissingSummary.serializer(), s))
        _summary.value = s
    }

    companion object {
        @Volatile private var instance: MissingStore? = null
        fun get(context: Context): MissingStore =
            instance ?: synchronized(this) { instance ?: MissingStore(context.applicationContext).also { instance = it } }
    }
}

/**
 * Setzt die Entscheidungen zu auf dem Smartphone gelöschten Dateien um:
 * - [MissingAction.RESTORE]: Original vom Medium in den ursprünglichen Ordner zurückkopieren (geprüft per SHA-256)
 * - [MissingAction.DELETE_BACKUP]: alle Sicherungen dieses Pfads vom Medium löschen
 */
class MissingRunner(private val context: Context) {

    private val resolver = context.contentResolver
    private val drives = DriveRepository(context)
    private val io = SafIo(resolver)
    private val scanner = MediaScanner(resolver)
    private val settings = SettingsRepository(context)

    suspend fun apply(decisions: MissingDecisions, onProgress: (done: Int, total: Int, name: String) -> Unit): MissingSummary {
        val deviceDir = withContext(Dispatchers.IO) { drives.requireFolder(decisions.deviceFolder) }
        val metadata = withContext(Dispatchers.IO) { deviceDir.findFile(DeviceRegistry.METADATA_DIR) }
            ?: throw AppError(R.string.err_no_metadata)
        val index = BackupIndex.open(io, metadata)
        val folders = settings.current().mediaFolders
        // Erneut prüfen: nur was jetzt noch fehlt, wird behandelt (eine wieder aufgetauchte Datei
        // darf ihre Sicherung keinesfalls verlieren)
        val missing = findMissing(scanner.scan(folders), index.state, folders).associateBy { it.phonePath }
        val dirs = DirCache(deviceDir)
        val caller = currentCoroutineContext()

        return withContext(NonCancellable + Dispatchers.IO) {
            var restored = 0
            var deleted = 0
            val failures = mutableListOf<Pair<String, Msg>>()
            var abort: Msg? = null
            val todo = decisions.actions.entries.toList()
            for ((i, decision) in todo.withIndex()) {
                JobControl.waitWhilePaused(JobControl.MISSING) { !caller.isActive }
                if (!caller.isActive) {
                    abort = msg(R.string.abort_cancelled)
                    break
                }
                val item = missing[decision.key] ?: continue
                onProgress(i, todo.size, item.displayName)
                try {
                    when (decision.value) {
                        MissingAction.RESTORE -> {
                            restore(item.latest, dirs, index)
                            restored++
                        }
                        MissingAction.DELETE_BACKUP -> {
                            item.versions.forEach { v ->
                                // Erst den Index, dann die Datei: bricht es dazwischen ab, bleibt
                                // höchstens eine nicht mehr verzeichnete Datei auf dem Medium liegen
                                index.remove(v)
                                backupFile(dirs, v)?.delete()
                            }
                            deleted++
                        }
                    }
                } catch (e: Exception) {
                    if (!deviceDir.exists()) {
                        abort = msg(R.string.abort_disconnected)
                        break
                    }
                    failures += item.displayName to e.toMsg()
                }
            }
            if (deviceDir.exists()) {
                index.compact()
                // Protokoll auf dem Medium: immer englisch
                val line = "${Instant.now()}  Deleted files: $restored restored, $deleted deleted from the backup, " +
                    "${failures.size} errors${abort?.let { " – aborted: ${it.en}" } ?: ""}\n" +
                    failures.joinToString("") { (name, reason) -> "    Error: $name: ${reason.en}\n" }
                runCatching { io.appendText(metadata, "backup_log.txt", line) }
            }
            IndexCache.get(context).update(index.state)
            MissingSummary(Instant.now().toString(), restored, deleted, failures.map { RunSummary.Failure(it.first, it.second.ui) }, abort?.ui)
        }
    }

    /** Kopiert das Original zurück in seinen Ordner auf dem Smartphone. */
    private suspend fun restore(entry: IndexEntry, dirs: DirCache, index: BackupIndex) {
        val backup = backupFile(dirs, entry) ?: throw AppError(R.string.err_backup_missing)
        val isVideo = entry.mimeType.startsWith("video/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.phonePath.substringAfterLast('/'))
            put(MediaStore.MediaColumns.RELATIVE_PATH, folderOf(entry.phonePath))
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
            put(MediaStore.MediaColumns.DATE_TAKEN, entry.dateTakenMillis)
            // Bis zur Prüfung unsichtbar für andere Apps
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(if (isVideo) MediaScanner.VIDEOS else MediaScanner.IMAGES, values)
            ?: throw AppError(R.string.err_create_on_phone)
        try {
            io.writeSynced(uri, entry.phonePath) { out ->
                (resolver.openInputStream(backup.uri) ?: throw AppError(R.string.err_backup_unreadable)).use { it.copyTo(out, 1 shl 20) }
            }
            val hash = (resolver.openInputStream(originalUri(uri)) ?: throw AppError(R.string.err_unreadable)).use { sha256(it).sha256 }
            if (hash != entry.originalSha256) throw AppError(R.string.err_checksum_after_restore)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // Gibt es den Namen schon (z. B. im Papierkorb), vergibt Android einen neuen – Index anpassen
        val actualName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: entry.phonePath.substringAfterLast('/')
        // Auf dem Smartphone liegt jetzt wieder das Original in voller Qualität
        index.record(entry.asVerifiedOnly().copy(phonePath = folderOf(entry.phonePath) + actualName))
    }

    companion object {
        /** Sicherungsdatei eines Eintrags auf dem Medium (auch für Vorschaubilder in der Oberfläche). */
        fun backupFile(dirs: DirCache, entry: IndexEntry): DocumentFile? =
            dirs.files(entry.backupPath.substringBeforeLast('/', ""))[entry.backupPath.substringAfterLast('/')]
    }
}
