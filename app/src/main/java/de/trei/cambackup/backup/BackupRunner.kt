package de.trei.cambackup.backup

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import de.trei.cambackup.storage.DirCache
import androidx.documentfile.provider.DocumentFile
import de.trei.cambackup.index.BackupIndex
import de.trei.cambackup.index.IndexCache
import de.trei.cambackup.media.MediaScanner
import de.trei.cambackup.settings.SettingsRepository
import de.trei.cambackup.storage.DeviceRegistry
import de.trei.cambackup.storage.DriveRepository
import de.trei.cambackup.storage.SafIo
import de.trei.cambackup.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

/** Planen und Ausführen eines Backups – gemeinsam genutzt von Oberfläche und Hintergrund-Job. */
class BackupRunner(private val context: Context) {

    private val drives = DriveRepository(context)
    private val io = SafIo(context.contentResolver)
    private val scanner = MediaScanner(context.contentResolver)
    private val engine = BackupEngine(context)
    private val settings = SettingsRepository(context)
    private val registry = DeviceRegistry(context, DeviceRegistry.appVersion(context))

    class Prepared internal constructor(
        internal val deviceDir: DocumentFile,
        internal val index: BackupIndex,
        val plan: BackupPlan,
        val space: SpaceCheck,
        val indexedCount: Int,
        /** Gesichert, aber auf dem Smartphone gelöscht – der Nutzer entscheidet, was damit geschieht. */
        val missing: List<MissingItem>,
        /** Adresse der jeweils neuesten Sicherung auf dem Medium (für Vorschaubilder), je Pfad. */
        val missingUris: Map<String, Uri>,
    )

    /** Lädt Index und Mediendateien und berechnet, was zu sichern ist. */
    suspend fun prepare(folderUri: String): Prepared {
        val deviceDir = withContext(Dispatchers.IO) { drives.requireFolder(folderUri) }
        val metadata = withContext(Dispatchers.IO) { deviceDir.findFile(DeviceRegistry.METADATA_DIR) }
            ?: throw IOException("Ordner Metadata fehlt")
        val index = BackupIndex.open(io, metadata)
        withContext(Dispatchers.IO) { flushPendingLog(deviceDir, metadata) }
        IndexCache.get(context).update(index.state)
        // Reste abgebrochener Läufe vor der Platzprüfung entfernen
        engine.cleanupStaging(deviceDir)
        withContext(Dispatchers.IO) { registry.ensureNoMedia(deviceDir) }
        val info = drives.info(deviceDir.uri)
        val folders = settings.current().mediaFolders
        val items = scanner.scan(folders)
        val plan = planBackup(items, index.state, info.fsType)
        val missing = findMissing(items, index.state, folders)
        val uris = withContext(Dispatchers.IO) {
            val dirs = DirCache(deviceDir)
            missing.mapNotNull { m -> MissingRunner.backupFile(dirs, m.latest)?.let { m.phonePath to it.uri } }.toMap()
        }
        return Prepared(
            deviceDir, index, plan, checkSpace(plan.bytesToCopy, info.space?.freeBytes), index.state.size,
            missing = missing,
            missingUris = uris,
        )
    }

    /**
     * Führt das Backup aus. Ein Abbruch (über [abortReason] oder durch Abbrechen der Coroutine)
     * beendet nur die laufende Datei; Index, Protokoll und Zusammenfassung werden trotzdem geschrieben.
     */
    suspend fun execute(
        prepared: Prepared,
        /** Grund für einen vorzeitigen Abbruch (Nutzer, Medium wird ausgeworfen) oder null. */
        abortReason: () -> String?,
        onProgress: (BackupProgress) -> Unit,
    ): RunSummary {
        if (prepared.space is SpaceCheck.NotEnough) {
            return summaryOf(null, "Zu wenig Platz auf dem Speichermedium")
        }
        val caller = currentCoroutineContext()
        // NonCancellable: das Ende des Laufs wird immer sauber abgeschlossen; der Abbruch wird
        // stattdessen zwischen den Datenblöcken über abortReason abgefragt.
        val s = settings.current()
        val cache = IndexCache.get(context)
        var lastShown = 0L
        return withContext(NonCancellable) {
            val result = engine.run(
                prepared.deviceDir, prepared.plan, prepared.index,
                targetDir = { backupDirFor(it.relativePath, it.dateTakenMillis, s.folderLayout, s.separateFolders) },
                abortReason = { abortReason() ?: if (!caller.isActive) "Abgebrochen" else null },
                onProgress = { p ->
                    // Übersicht und Galerie zeigen den Fortschritt live (etwa alle 2 Sekunden)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastShown >= LIVE_INTERVAL_MS) {
                        lastShown = now
                        cache.show(prepared.index.state)
                    }
                    onProgress(p)
                },
            )
            val finishError = runCatching { finish(prepared, result) }.exceptionOrNull()
            summaryOf(result, finishError?.let { "Abschluss fehlgeschlagen: ${it.message}" })
        }
    }

    private suspend fun finish(p: Prepared, result: BackupResult) = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val line = logLine(now, result)
        if (!p.deviceDir.exists()) {
            // Medium schon getrennt: Protokollzeile merken und beim nächsten Lauf nachtragen
            pendingLog(p.deviceDir).appendText(line)
            return@withContext
        }
        p.index.compact()
        IndexCache.get(context).update(p.index.state)
        engine.cleanupStaging(p.deviceDir)
        if (result.copied + result.adopted > 0) registry.markBackup(p.deviceDir, now)
        val written = runCatching {
            io.appendText(p.deviceDir.findFile(DeviceRegistry.METADATA_DIR) ?: throw IOException("Metadata fehlt"), LOG_FILE, line)
        }
        if (written.isFailure) pendingLog(p.deviceDir).appendText(line)
    }

    /** Protokollzeilen von Läufen, die nach dem Trennen des Mediums nicht mehr geschrieben werden konnten. */
    private fun pendingLog(deviceDir: DocumentFile) = File(context.filesDir, "pending_log_${deviceDir.uri.toString().hashCode()}.txt")

    private fun flushPendingLog(deviceDir: DocumentFile, metadata: DocumentFile) {
        val pending = pendingLog(deviceDir).takeIf { it.exists() } ?: return
        runCatching { io.appendText(metadata, LOG_FILE, pending.readText()) }.onSuccess { pending.delete() }
    }

    private fun logLine(now: Instant, result: BackupResult) = buildString {
        append("$now  Backup: ${result.copied} kopiert (${formatBytes(result.bytesCopied)})")
        if (result.adopted > 0) append(", ${result.adopted} übernommen")
        append(", ${result.failures.size} Fehler")
        result.abortReason?.let { append(" – abgebrochen: $it") }
        append("\n")
        result.failures.forEach { append("    Fehler: ${it.item.phonePath}: ${it.reason}\n") }
    }

    private fun summaryOf(result: BackupResult?, extraAbort: String?) = RunSummary(
        finishedAt = Instant.now().toString(),
        copied = result?.copied ?: 0,
        adopted = result?.adopted ?: 0,
        bytesCopied = result?.bytesCopied ?: 0,
        failures = result?.failures.orEmpty().map { RunSummary.Failure(it.item.displayName, it.reason) },
        abortReason = result?.abortReason ?: extraAbort,
    )

    companion object {
        private const val LOG_FILE = "backup_log.txt"
        private const val LIVE_INTERVAL_MS = 2_000L

        /**
         * Nur ein Lauf gleichzeitig: Nach einem Abbruch schließt der alte Job seine aktuelle Datei
         * noch ab – ein sofort neu gestarteter Job wartet so lange, statt parallel in den Index zu schreiben.
         */
        val lock = Mutex()
    }
}
