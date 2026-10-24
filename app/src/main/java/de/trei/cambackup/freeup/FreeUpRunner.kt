package de.trei.cambackup.freeup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import de.trei.cambackup.backup.sha256
import de.trei.cambackup.compress.ImageShrinker
import de.trei.cambackup.compress.ShrinkResult
import de.trei.cambackup.compress.VideoShrinker
import de.trei.cambackup.compress.precheckImage
import de.trei.cambackup.compress.precheckVideo
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
import de.trei.cambackup.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

data class FreeUpProgress(val done: Int, val total: Int, val currentName: String, val replacing: Boolean)

/**
 * Speicher freigeben in zwei Schritten:
 * 1. [prepare]: für jedes gesicherte, unveränderte Original (SHA-256 geprüft) eine verkleinerte
 *    Version im Cache erzeugen. Auf dem Smartphone ändert sich dabei nichts.
 * 2. [replace]: nach Zustimmung des Nutzers (System-Dialog) die Originale auf dem Smartphone durch
 *    die verkleinerten Versionen ersetzen – nur bei angeschlossenem Medium und ausgeschaltetem Probelauf.
 */
class FreeUpRunner(private val context: Context) {

    private val resolver = context.contentResolver
    private val drives = DriveRepository(context)
    private val io = SafIo(resolver)
    private val scanner = MediaScanner(resolver)
    private val settings = SettingsRepository(context)
    private val images = ImageShrinker(context)
    private val videos = VideoShrinker(context)
    private val store = FreeUpStore.get(context)

    private class Opened(val deviceDir: DocumentFile, val index: BackupIndex)

    private suspend fun open(folderUri: String): Opened = withContext(Dispatchers.IO) {
        val deviceDir = drives.requireFolder(folderUri)
        val metadata = deviceDir.findFile(DeviceRegistry.METADATA_DIR) ?: throw IOException("Ordner Metadata fehlt")
        Opened(deviceDir, BackupIndex.open(io, metadata))
    }

    /** Überblick für die Oberfläche (ohne Hash-Prüfung, schnell). */
    suspend fun overview(folderName: String): FreeUpOverview {
        val opened = open(folderName)
        IndexCache.get(context).update(opened.index.state)
        return overviewFor(scanner.scan(settings.current().mediaFolders), opened.index.state)
    }

    suspend fun prepare(folderName: String, onProgress: (FreeUpProgress) -> Unit): FreeUpSummary {
        val s = settings.current()
        val caller = currentCoroutineContext()
        val opened = open(folderName)
        val candidates = overviewFor(scanner.scan(settings.current().mediaFolders), opened.index.state).candidates
        val dirs = DirCache(opened.deviceDir)
        val now = System.currentTimeMillis()

        // Alte Vorbereitung verwerfen
        store.saveBatch(null)
        images.clearCache()

        val pending = mutableListOf<PendingReplacement>()
        val skipped = linkedMapOf<String, Int>()
        val failures = mutableListOf<FreeUpSummary.Failure>()
        fun skip(reason: String) { skipped[reason] = (skipped[reason] ?: 0) + 1 }
        var abort: String? = null

        for ((i, c) in candidates.withIndex()) {
            if (!caller.isActive) {
                abort = "Abgebrochen"
                break
            }
            val item = c.item
            onProgress(FreeUpProgress(i, candidates.size, item.displayName, replacing = false))
            val pre = if (item.isVideo) precheckVideo(item, s, now) else precheckImage(item, s, now)
            if (pre != null) {
                skip(pre.text)
                continue
            }
            try {
                if (!backupPresent(dirs, c.entry)) {
                    failures += FreeUpSummary.Failure(item.displayName, "Sicherung auf dem Medium fehlt oder ist unvollständig")
                    continue
                }
                // Sicherheitsregel: nur ein bitgenau gesichertes Original darf ersetzt werden
                if (hashOf(item.uri) != c.entry.originalSha256) {
                    skip("Seit der Sicherung verändert")
                    continue
                }
                when (val r = if (item.isVideo) videos.shrink(item, s, now) else images.shrink(item, s, now)) {
                    is ShrinkResult.Skipped -> skip(r.reason.text)
                    is ShrinkResult.Ready -> pending += PendingReplacement(
                        mediaId = item.id,
                        uri = item.uri.toString(),
                        displayName = item.displayName,
                        phonePath = c.entry.phonePath,
                        isVideo = item.isVideo,
                        backupPath = c.entry.backupPath,
                        originalSize = r.originalBytes,
                        originalSha256 = c.entry.originalSha256,
                        newFile = r.file.path,
                        newSize = r.newBytes,
                        newSha256 = r.sha256,
                        params = r.params,
                    )
                }
            } catch (e: CancellationException) {
                abort = "Abgebrochen"
                break
            } catch (e: Exception) {
                failures += FreeUpSummary.Failure(item.displayName, e.message ?: e.javaClass.simpleName)
            }
        }

        val batch = PendingBatch(folderName, Instant.now().toString(), pending)
        store.saveBatch(batch.takeIf { pending.isNotEmpty() })
        return FreeUpSummary(Instant.now().toString(), false, pending.size, batch.bytesSaved, skipped, failures, abort)
    }

    /**
     * Ersetzt die vorbereiteten Dateien. Setzt voraus, dass der Nutzer per MediaStore.createWriteRequest
     * zugestimmt hat. Läuft bis zum Ende der aktuellen Datei auch bei Abbruch weiter (NonCancellable).
     */
    suspend fun replace(onProgress: (FreeUpProgress) -> Unit): FreeUpSummary {
        val batch = store.batch.value ?: return summary(0, 0, emptyList(), "Nichts vorbereitet")
        if (settings.current().dryRun) return summary(0, 0, emptyList(), "Probelauf ist aktiv – es wurde nichts ersetzt")
        val caller = currentCoroutineContext()
        val opened = open(batch.deviceFolder)
        val dirs = DirCache(opened.deviceDir)

        return withContext(NonCancellable + Dispatchers.IO) {
            var replaced = 0
            var saved = 0L
            val failures = mutableListOf<FreeUpSummary.Failure>()
            var abort: String? = null
            var processed = 0
            for ((i, p) in batch.items.withIndex()) {
                if (!caller.isActive) {
                    abort = "Abgebrochen"
                    break
                }
                processed = i + 1
                onProgress(FreeUpProgress(i, batch.items.size, p.displayName, replacing = true))
                try {
                    replaceOne(p, opened.index, dirs)
                    replaced++
                    saved += p.originalSize - p.newSize
                } catch (e: Exception) {
                    if (!opened.deviceDir.exists()) {
                        abort = "Speichermedium wurde getrennt"
                        failures += FreeUpSummary.Failure(p.displayName, e.message ?: "Fehler")
                        break
                    }
                    failures += FreeUpSummary.Failure(p.displayName, e.message ?: e.javaClass.simpleName)
                } finally {
                    File(p.newFile).delete()
                }
            }
            if (opened.deviceDir.exists()) {
                opened.index.compact()
                appendLog(opened.deviceDir, replaced, saved, failures, abort)
            }
            IndexCache.get(context).update(opened.index.state)
            // Nicht bearbeitete Dateien bleiben vorbereitet (z. B. nach Abbruch)
            val rest = batch.items.drop(processed)
            store.saveBatch(batch.copy(items = rest).takeIf { rest.isNotEmpty() })
            if (rest.isEmpty()) images.clearCache()
            summary(replaced, saved, failures, abort)
        }
    }

    private suspend fun replaceOne(p: PendingReplacement, index: BackupIndex, dirs: DirCache) {
        val uri = Uri.parse(p.uri)
        val entry = index.state.forPhonePath(p.phonePath).lastOrNull { it.backupPath == p.backupPath }
            ?: throw IOException("Kein Index-Eintrag")
        val backup = backupFile(dirs, entry) ?: throw IOException("Sicherung auf dem Medium fehlt")
        if (backup.length() != entry.originalSize) throw IOException("Sicherung auf dem Medium unvollständig")
        val currentSize = resolver.openAssetFileDescriptor(originalUri(uri), "r")?.use { it.length } ?: -1
        if (currentSize != p.originalSize) throw IOException("Datei wurde seit der Vorbereitung verändert")
        val newFile = File(p.newFile)
        if (!newFile.exists() || newFile.length() != p.newSize) throw IOException("Vorbereitete Datei fehlt")

        // Erst den Index (Absicht) schreiben, dann ersetzen: so wird eine verkleinerte Datei
        // nach einem Abbruch nie als neues „Original“ gesichert.
        index.record(entry.asCompressed(p, Instant.now().toString()))
        try {
            io.writeSynced(uri, p.displayName) { out -> newFile.inputStream().use { it.copyTo(out, 1 shl 20) } }
            if (hashOf(uri) != p.newSha256) throw IOException("Prüfsumme nach dem Ersetzen falsch")
        } catch (e: Exception) {
            restoreOriginal(uri, p, backup, index, entry)
            throw IOException("Ersetzen fehlgeschlagen (${e.message}) – Original wiederhergestellt", e)
        }
    }

    /** Spielt das Original vom Medium zurück auf das Smartphone. */
    private suspend fun restoreOriginal(uri: Uri, p: PendingReplacement, backup: DocumentFile, index: BackupIndex, entry: IndexEntry) {
        io.writeSynced(uri, p.displayName) { out ->
            (resolver.openInputStream(backup.uri) ?: throw IOException("Sicherung nicht lesbar")).use { it.copyTo(out, 1 shl 20) }
        }
        if (hashOf(uri) != entry.originalSha256) {
            throw IOException("ACHTUNG: Wiederherstellen fehlgeschlagen – Original liegt unter ${entry.backupPath} auf dem Medium")
        }
        index.record(entry.asVerifiedOnly())
    }

    /** Protokolliert das Ersetzen in Metadata/backup_log.txt – nachvollziehbar auch am PC. */
    private fun appendLog(deviceDir: DocumentFile, replaced: Int, saved: Long, failures: List<FreeUpSummary.Failure>, abort: String?) {
        val metadata = deviceDir.findFile(DeviceRegistry.METADATA_DIR) ?: return
        val line = buildString {
            append("${Instant.now()}  Ersetzt: $replaced auf dem Smartphone verkleinert (${formatBytes(saved)} frei), ${failures.size} Fehler")
            abort?.let { append(" – abgebrochen: $it") }
            append("\n")
            failures.forEach { append("    Fehler: ${it.name}: ${it.reason}\n") }
        }
        runCatching { io.appendText(metadata, "backup_log.txt", line) }
    }

    private suspend fun hashOf(uri: Uri): String =
        withContext(Dispatchers.IO) { (resolver.openInputStream(originalUri(uri)) ?: throw IOException("nicht lesbar")).use { sha256(it).sha256 } }

    private fun backupFile(dirs: DirCache, entry: IndexEntry): DocumentFile? {
        val sub = entry.backupPath.substringBeforeLast('/', "")
        val name = entry.backupPath.substringAfterLast('/')
        return dirs.files(sub)[name]
    }

    private fun backupPresent(dirs: DirCache, entry: IndexEntry): Boolean =
        backupFile(dirs, entry)?.length() == entry.originalSize

    private fun summary(done: Int, saved: Long, failures: List<FreeUpSummary.Failure>, abort: String?) =
        FreeUpSummary(Instant.now().toString(), true, done, saved, emptyMap(), failures, abort)
}
