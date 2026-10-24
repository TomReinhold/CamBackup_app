package de.trei.cambackup.freeup

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import de.trei.cambackup.backup.RunSummary
import de.trei.cambackup.backup.sha256
import de.trei.cambackup.index.BackupIndex
import de.trei.cambackup.index.IndexCache
import de.trei.cambackup.media.MediaScanner
import de.trei.cambackup.media.originalUri
import de.trei.cambackup.settings.SettingsRepository
import de.trei.cambackup.storage.DeviceRegistry
import de.trei.cambackup.storage.DirCache
import de.trei.cambackup.storage.DriveRepository
import de.trei.cambackup.storage.SafIo
import de.trei.cambackup.util.formatBytes
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

data class RestoreOverview(
    val candidates: List<RestoreCandidate>,
    /** Benötigter Platz auf dem Smartphone inkl. Reserve. */
    val spaceNeeded: Long,
    val phoneFree: Long,
) {
    val enoughSpace: Boolean get() = phoneFree >= spaceNeeded
}

@Serializable
data class RestoreSummary(
    val finishedAt: String,
    val restored: Int,
    /** Wieder belegter Platz auf dem Smartphone. */
    val bytesAdded: Long,
    val failures: List<RunSummary.Failure>,
    val abortReason: String?,
)

/** Letztes Ergebnis des Zurückholens (beobachtbar für die Oberfläche). */
class RestoreStore private constructor(context: Context) {
    private val file = File(context.filesDir, "restore_summary.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _summary = MutableStateFlow(runCatching { json.decodeFromString(RestoreSummary.serializer(), file.readText()) }.getOrNull())
    val summary: StateFlow<RestoreSummary?> = _summary.asStateFlow()

    fun save(s: RestoreSummary?) {
        if (s == null) file.delete() else file.writeText(json.encodeToString(RestoreSummary.serializer(), s))
        _summary.value = s
    }

    companion object {
        @Volatile private var instance: RestoreStore? = null
        fun get(context: Context): RestoreStore =
            instance ?: synchronized(this) { instance ?: RestoreStore(context.applicationContext).also { instance = it } }
    }
}

/**
 * Holt die Originale verkleinerter Dateien vom Speichermedium zurück aufs Smartphone. Ersetzt wird
 * nur, was auf dem Smartphone bitgenau noch die verkleinerte Version ist (SHA-256), und erst nach
 * Zustimmung des Nutzers (MediaStore.createWriteRequest).
 */
class RestoreRunner(private val context: Context) {

    private val resolver = context.contentResolver
    private val drives = DriveRepository(context)
    private val io = SafIo(resolver)
    private val scanner = MediaScanner(resolver)
    private val settings = SettingsRepository(context)

    private suspend fun openIndex(folderUri: String): Pair<androidx.documentfile.provider.DocumentFile, BackupIndex> =
        withContext(Dispatchers.IO) {
            val dir = drives.requireFolder(folderUri)
            dir to BackupIndex.open(io, dir.findFile(DeviceRegistry.METADATA_DIR)!!)
        }

    suspend fun overview(folderUri: String): RestoreOverview {
        val (_, index) = openIndex(folderUri)
        val candidates = restoreCandidates(scanner.scan(settings.current().mediaFolders), index.state)
        return RestoreOverview(candidates, restoreSpaceNeeded(candidates), phoneFreeBytes())
    }

    /** Freier Platz im internen Speicher des Smartphones. */
    fun phoneFreeBytes(): Long = StatFs(Environment.getExternalStorageDirectory().path).availableBytes

    suspend fun restoreAll(folderUri: String, keepOriginal: Boolean, onProgress: (done: Int, total: Int, name: String) -> Unit): RestoreSummary {
        val (dir, index) = openIndex(folderUri)
        val candidates = restoreCandidates(scanner.scan(settings.current().mediaFolders), index.state)
        if (phoneFreeBytes() < restoreSpaceNeeded(candidates)) {
            return RestoreSummary(Instant.now().toString(), 0, 0, emptyList(), "Zu wenig Platz auf dem Smartphone")
        }
        val dirs = DirCache(dir)
        val caller = currentCoroutineContext()

        return withContext(NonCancellable + Dispatchers.IO) {
            var restored = 0
            var added = 0L
            val failures = mutableListOf<RunSummary.Failure>()
            var abort: String? = null
            for ((i, c) in candidates.withIndex()) {
                if (!caller.isActive) {
                    abort = "Abgebrochen"
                    break
                }
                onProgress(i, candidates.size, c.item.displayName)
                try {
                    restoreOne(c, dirs, index, keepOriginal)
                    restored++
                    added += c.extraBytes
                } catch (e: Exception) {
                    if (!dir.exists()) {
                        abort = "Speichermedium wurde getrennt"
                        break
                    }
                    failures += RunSummary.Failure(c.item.displayName, e.message ?: e.javaClass.simpleName)
                }
            }
            if (dir.exists()) {
                index.compact()
                val line = "${Instant.now()}  Originale zurückgeholt: $restored (${formatBytes(added)} wieder belegt), ${failures.size} Fehler" +
                    (abort?.let { " – abgebrochen: $it" } ?: "") + "\n" +
                    failures.joinToString("") { "    Fehler: ${it.name}: ${it.reason}\n" }
                runCatching { io.appendText(dir.findFile(DeviceRegistry.METADATA_DIR)!!, "backup_log.txt", line) }
            }
            IndexCache.get(context).update(index.state)
            RestoreSummary(Instant.now().toString(), restored, added, failures, abort)
        }
    }

    private suspend fun restoreOne(c: RestoreCandidate, dirs: DirCache, index: BackupIndex, keepOriginal: Boolean) {
        val entry = c.entry
        val backup = dirs.files(entry.backupPath.substringBeforeLast('/', ""))[entry.backupPath.substringAfterLast('/')]
            ?: throw IOException("Original auf dem Medium fehlt")
        if (backup.length() != entry.originalSize) throw IOException("Original auf dem Medium unvollständig")
        val uri = c.item.uri
        // Sicherheitsregel: nur die unveränderte verkleinerte Version wird überschrieben
        if (entry.compressedSha256 != null && hashOf(uri) != entry.compressedSha256) {
            throw IOException("Datei wurde nach dem Verkleinern verändert – bleibt unangetastet")
        }
        // Bis zu zwei Versuche: ein abgebrochener Schreibvorgang wird sofort wiederholt
        var lastError: Exception? = null
        repeat(2) {
            try {
                io.writeSynced(uri, c.item.displayName) { out ->
                    (resolver.openInputStream(backup.uri) ?: throw IOException("Original nicht lesbar")).use { it.copyTo(out, 1 shl 20) }
                }
                if (hashOf(uri) == entry.originalSha256) {
                    index.record(entry.asVerifiedOnly().copy(keepOriginal = keepOriginal))
                    return
                }
                lastError = IOException("Prüfsumme nach dem Zurückholen falsch")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Zurückholen fehlgeschlagen (${lastError?.message}) – Original bleibt auf dem Medium, bitte erneut versuchen")
    }

    private suspend fun hashOf(uri: Uri): String =
        withContext(Dispatchers.IO) { (resolver.openInputStream(originalUri(uri)) ?: throw IOException("nicht lesbar")).use { sha256(it).sha256 } }
}
