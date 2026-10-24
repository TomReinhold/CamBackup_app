package de.trei.cambackup.backup

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import de.trei.cambackup.index.BackupIndex
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
import java.io.IOException
import java.time.Instant

/** Planen und Ausführen eines Backups – gemeinsam genutzt von Oberfläche und Hintergrund-Job. */
class BackupRunner(private val context: Context) {

    private val drives = DriveRepository(context)
    private val io = SafIo(context.contentResolver)
    private val scanner = MediaScanner(context.contentResolver)
    private val engine = BackupEngine(context)
    private val settings = SettingsRepository(context)
    private val registry = DeviceRegistry(
        context,
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?",
    )

    class Prepared internal constructor(
        internal val deviceDir: DocumentFile,
        internal val index: BackupIndex,
        val plan: BackupPlan,
        val space: SpaceCheck,
        val indexedCount: Int,
    )

    /** Lädt Index und Kamera-Dateien und berechnet, was zu sichern ist. */
    suspend fun prepare(deviceFolderName: String): Prepared {
        val root = drives.openRoot() ?: throw IOException("Speichermedium nicht verbunden")
        val deviceDir = withContext(Dispatchers.IO) { root.findFile(deviceFolderName) }
            ?: throw IOException("Geräteordner „$deviceFolderName“ fehlt")
        val metadata = withContext(Dispatchers.IO) { deviceDir.findFile(DeviceRegistry.METADATA_DIR) }
            ?: throw IOException("Ordner Metadata fehlt")
        val index = BackupIndex.open(io, metadata)
        // Reste abgebrochener Läufe vor der Platzprüfung entfernen
        engine.cleanupStaging(deviceDir)
        val info = drives.info(drives.selectedTree!!)
        val plan = planBackup(scanner.scanCameraFolder(), index.state, info.fsType)
        return Prepared(deviceDir, index, plan, checkSpace(plan.bytesToCopy, info.space?.freeBytes), index.state.size)
    }

    /**
     * Führt das Backup aus. Ein Abbruch (über [userCancelled] oder durch Abbrechen der Coroutine)
     * beendet nur die laufende Datei; Index, Protokoll und Zusammenfassung werden trotzdem geschrieben.
     */
    suspend fun execute(
        prepared: Prepared,
        userCancelled: () -> Boolean,
        onProgress: (BackupProgress) -> Unit,
    ): RunSummary {
        if (prepared.space is SpaceCheck.NotEnough) {
            return summaryOf(null, "Zu wenig Platz auf dem Speichermedium")
        }
        val caller = currentCoroutineContext()
        // NonCancellable: das Ende des Laufs wird immer sauber abgeschlossen; der Abbruch wird
        // stattdessen zwischen den Datenblöcken über isCancelled abgefragt.
        return withContext(NonCancellable) {
            val result = engine.run(
                prepared.deviceDir, prepared.plan, prepared.index, settings.current().folderLayout,
                isCancelled = { userCancelled() || !caller.isActive },
                onProgress = onProgress,
            )
            val finishError = runCatching { finish(prepared, result) }.exceptionOrNull()
            summaryOf(result, finishError?.let { "Abschluss fehlgeschlagen: ${it.message}" })
        }
    }

    private suspend fun finish(p: Prepared, result: BackupResult) = withContext(Dispatchers.IO) {
        if (!p.deviceDir.exists()) return@withContext
        p.index.compact()
        engine.cleanupStaging(p.deviceDir)
        val now = Instant.now()
        if (result.copied + result.adopted > 0) registry.markBackup(p.deviceDir, now)
        val metadata = p.deviceDir.findFile(DeviceRegistry.METADATA_DIR) ?: return@withContext
        val line = buildString {
            append("$now  Backup: ${result.copied} kopiert (${formatBytes(result.bytesCopied)})")
            if (result.adopted > 0) append(", ${result.adopted} übernommen")
            append(", ${result.failures.size} Fehler")
            result.abortReason?.let { append(" – abgebrochen: $it") }
            append("\n")
            result.failures.forEach { append("    Fehler: ${it.item.phonePath}: ${it.reason}\n") }
        }
        io.appendText(metadata, LOG_FILE, line)
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

        /**
         * Nur ein Lauf gleichzeitig: Nach einem Abbruch schließt der alte Job seine aktuelle Datei
         * noch ab – ein sofort neu gestarteter Job wartet so lange, statt parallel in den Index zu schreiben.
         */
        val lock = Mutex()
    }
}
