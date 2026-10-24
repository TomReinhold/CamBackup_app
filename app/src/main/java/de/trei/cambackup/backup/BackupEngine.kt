package de.trei.cambackup.backup

import android.content.ContentResolver
import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import de.trei.cambackup.index.BackupIndex
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.FolderLayout
import de.trei.cambackup.storage.DeviceRegistry
import de.trei.cambackup.storage.DirCache
import de.trei.cambackup.storage.SafIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

data class BackupProgress(
    val fileIndex: Int,
    val fileCount: Int,
    val currentName: String,
    /** Bereits verarbeitete Bytes aller Dateien (Kopieren und Prüfen je zur Hälfte gewichtet). */
    val bytesDone: Long,
    val bytesTotal: Long,
)

data class BackupFailure(val item: MediaItem, val reason: String)

data class BackupResult(
    val copied: Int,
    /** Dateien, die schon vollständig auf dem Medium lagen (z. B. nach einem Abbruch) und übernommen wurden. */
    val adopted: Int,
    val bytesCopied: Long,
    val failures: List<BackupFailure>,
    /** Gesetzt, wenn der Lauf vorzeitig endete (Medium getrennt, abgebrochen). */
    val abortReason: String?,
)

/**
 * Kopiert Originale bitgenau auf das Speichermedium:
 * 1. in `Metadata/_incoming` als "<name>.part" schreiben, dabei SHA-256 der Quelle berechnen, fsync
 * 2. Kopie vom Medium zurücklesen und SHA-256 + Größe vergleichen
 * 3. erst dann umbenennen, in den Zielordner verschieben und in den Index eintragen
 *
 * Wird das Medium während des Kopierens abgezogen, beendet Android die App (vold beendet alle
 * Prozesse mit offenen Dateien auf dem Datenträger). Ein späterer Lauf setzt dann sauber fort:
 * Reste liegen nur im Zwischenordner, fertige Dateien stehen bereits im Journal.
 */
class BackupEngine(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val io = SafIo(resolver)

    /**
     * Entfernt Reste abgebrochener Läufe aus dem Zwischenordner. Vor der Platzprüfung aufrufen,
     * damit halb kopierte Dateien den freien Platz nicht verfälschen.
     */
    suspend fun cleanupStaging(deviceDir: DocumentFile) = withContext(Dispatchers.IO) {
        deviceDir.findFile(DeviceRegistry.METADATA_DIR)?.findFile(STAGING_DIR)?.delete()
    }

    suspend fun run(
        deviceDir: DocumentFile,
        plan: BackupPlan,
        index: BackupIndex,
        layout: FolderLayout,
        isCancelled: () -> Boolean,
        onProgress: (BackupProgress) -> Unit,
    ): BackupResult = withContext(Dispatchers.IO) {
        val dirs = DirCache(deviceDir)
        // Kopieren + Zurücklesen: jede Datei wird zweimal verarbeitet
        val total = plan.bytesToCopy * 2
        var doneBefore = 0L
        var copied = 0
        var adopted = 0
        var bytesCopied = 0L
        val failures = mutableListOf<BackupFailure>()
        var abortReason: String? = null

        for ((i, item) in plan.toCopy.withIndex()) {
            val report = { bytes: Long ->
                // Wird während des Kopierens laufend aufgerufen – so greift ein Abbruch auch bei großen Videos zügig
                if (isCancelled()) throw UserCancelled()
                onProgress(BackupProgress(i, plan.toCopy.size, item.displayName, doneBefore + bytes, total))
            }
            try {
                report(0)
                when (backupOne(item, dirs, index, layout, report)) {
                    Outcome.COPIED -> { copied++; bytesCopied += item.sizeBytes }
                    Outcome.ADOPTED -> adopted++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserCancelled) {
                abortReason = "Abgebrochen"
                break
            } catch (e: Exception) {
                if (!deviceDir.exists()) {
                    abortReason = "Speichermedium wurde getrennt"
                    break
                }
                failures += BackupFailure(item, e.message ?: e.javaClass.simpleName)
            }
            doneBefore += item.sizeBytes * 2
        }
        onProgress(BackupProgress(plan.toCopy.size, plan.toCopy.size, "", doneBefore, total))
        BackupResult(copied, adopted, bytesCopied, failures, abortReason)
    }

    private enum class Outcome { COPIED, ADOPTED }

    private class UserCancelled : Exception()

    companion object {
        /** Zwischenordner für Dateien, die gerade geschrieben und geprüft werden. */
        const val STAGING_DIR = "_incoming"
        private const val STAGING_PATH = "${DeviceRegistry.METADATA_DIR}/$STAGING_DIR"
    }

    private suspend fun backupOne(
        item: MediaItem,
        dirs: DirCache,
        index: BackupIndex,
        layout: FolderLayout,
        report: (Long) -> Unit,
    ): Outcome {
        val subPath = if (layout == FolderLayout.FLAT) "" else backupSubPath(item.dateTakenMillis)
        val dir = dirs.dir(subPath)
        val files = dirs.files(subPath)

        // Liegt die Datei schon vollständig dort (Abbruch nach dem Verschieben, vor dem Indexeintrag)?
        files[item.displayName]?.let { existing ->
            val backupPath = joinPath(subPath, item.displayName)
            if (!index.state.containsBackupPath(backupPath) && existing.length() == item.sizeBytes) {
                val src = hashSource(item) { report(it) }
                val dst = hashDocument(existing) { report(item.sizeBytes + it) }
                if (src == dst) {
                    index.record(entryFor(item, src, backupPath))
                    return Outcome.ADOPTED
                }
            }
        }

        val finalName = uniqueName(item.displayName) { it in files }

        // Im Zwischenordner schreiben und prüfen; im Zielordner landet nur eine geprüfte, vollständige Datei
        val staging = dirs.dir(STAGING_PATH)
        staging.findFile("$finalName.part")?.delete()
        staging.findFile(finalName)?.delete()
        val part = io.createFile(staging, "$finalName.part")
        var moved = false
        try {
            val source = resolver.openInputStream(item.uri) ?: throw IOException("Quelle nicht lesbar")
            val copy = source.use { input ->
                io.writeSynced(part) { out -> copyWithSha256(input, out) { report(it) } }
            }
            val check = hashDocument(part) { report(copy.bytes + it) }
            if (check != copy) {
                throw IOException("Prüfsumme der Kopie stimmt nicht überein")
            }
            if (!part.renameTo(finalName) || part.name != finalName) {
                throw IOException("Umbenennen nach „$finalName“ fehlgeschlagen")
            }
            val target = DocumentsContract.moveDocument(resolver, part.uri, staging.uri, dir.uri)
                ?: throw IOException("Verschieben nach „$subPath“ fehlgeschlagen")
            moved = true
            files[finalName] = DocumentFile.fromSingleUri(context, target)!!
            index.record(entryFor(item, copy, joinPath(subPath, finalName)))
            return Outcome.COPIED
        } catch (e: Throwable) {
            // Unvollständige oder fehlerhafte Kopie nie liegen lassen. Eine bereits verschobene Datei
            // bleibt stehen – sie ist geprüft und wird beim nächsten Lauf übernommen.
            if (!moved) runCatching { part.delete() }
            throw e
        }
    }

    private suspend fun hashSource(item: MediaItem, onBytes: (Long) -> Unit): StreamResult =
        (resolver.openInputStream(item.uri) ?: throw IOException("Quelle nicht lesbar")).use { sha256(it, onBytes) }

    private suspend fun hashDocument(file: DocumentFile, onBytes: (Long) -> Unit): StreamResult =
        (resolver.openInputStream(file.uri) ?: throw IOException("Kopie nicht lesbar")).use { sha256(it, onBytes) }

    private fun entryFor(item: MediaItem, hash: StreamResult, backupPath: String) = IndexEntry(
        phonePath = item.phonePath,
        mimeType = item.mimeType,
        dateTakenMillis = item.dateTakenMillis,
        originalSize = hash.bytes,
        originalSha256 = hash.sha256,
        backupPath = backupPath,
        backedUpAt = Instant.now().toString(),
    )
}
