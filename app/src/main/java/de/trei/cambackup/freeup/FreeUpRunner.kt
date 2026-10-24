package de.trei.cambackup.freeup

import de.trei.cambackup.work.JobControl
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
 * Speicher freigeben: für jedes gesicherte, unveränderte Original (SHA-256 geprüft) eine verkleinerte
 * Version erzeugen und – nach Zustimmung des Nutzers per System-Dialog – sofort an Stelle des
 * Originals auf dem Smartphone schreiben. Im Probelauf wird nur gerechnet, nichts ersetzt.
 * Es liegt immer höchstens eine verkleinerte Kopie im Cache.
 */
class FreeUpRunner(private val context: Context) {

    private val resolver = context.contentResolver
    private val drives = DriveRepository(context)
    private val io = SafIo(resolver)
    private val scanner = MediaScanner(resolver)
    private val settings = SettingsRepository(context)
    private val images = ImageShrinker(context)
    private val videos = VideoShrinker(context)

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

    /**
     * Verkleinert alle Kandidaten. [replace] = false bzw. Probelauf: nur berechnen, wie viel frei würde.
     * Ersetzen setzt voraus, dass der Nutzer per MediaStore.createWriteRequest zugestimmt hat; fehlt die
     * Zustimmung für eine Datei, bleibt deren Original unverändert.
     */
    suspend fun run(folderName: String, replace: Boolean, onProgress: (FreeUpProgress) -> Unit): FreeUpSummary {
        val s = settings.current()
        val doReplace = replace && !s.dryRun
        val caller = currentCoroutineContext()
        val opened = open(folderName)
        val candidates = overviewFor(scanner.scan(s.mediaFolders), opened.index.state).candidates
        val dirs = DirCache(opened.deviceDir)
        val cache = IndexCache.get(context)
        val now = System.currentTimeMillis()
        images.clearCache()

        var done = 0
        var saved = 0L
        val skipped = linkedMapOf<String, Int>()
        val failures = mutableListOf<FreeUpSummary.Failure>()
        fun skip(reason: String) { skipped[reason] = (skipped[reason] ?: 0) + 1 }
        var abort: String? = null
        var lastShown = 0L

        for ((i, c) in candidates.withIndex()) {
            JobControl.waitWhilePaused(JobControl.FREE_UP) { !caller.isActive }
            if (!caller.isActive) {
                abort = "Abgebrochen"
                break
            }
            val item = c.item
            onProgress(FreeUpProgress(i, candidates.size, item.displayName, replacing = doReplace))
            val pre = if (item.isVideo) precheckVideo(item, s, now) else precheckImage(item, s, now)
            if (pre != null) {
                skip(pre.text)
                continue
            }
            if (!prepareSpaceOk(context.cacheDir.usableSpace, item.sizeBytes)) {
                abort = "Zu wenig Platz auf dem Smartphone für die verkleinerte Kopie"
                break
            }
            var file: File? = null
            try {
                if (!backupPresent(dirs, c.entry)) {
                    if (!opened.deviceDir.exists()) {
                        abort = "Speichermedium wurde getrennt"
                        break
                    }
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
                    is ShrinkResult.Ready -> {
                        file = r.file
                        val p = PendingReplacement(
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
                        // Eine angefangene Datei wird immer zu Ende ersetzt (oder zurückgespielt)
                        if (doReplace) withContext(NonCancellable) { replaceOne(p, opened.index, dirs) }
                        done++
                        saved += p.originalSize - p.newSize
                    }
                }
            } catch (e: CancellationException) {
                abort = "Abgebrochen"
                break
            } catch (e: Exception) {
                if (!opened.deviceDir.exists()) {
                    abort = "Speichermedium wurde getrennt"
                    failures += FreeUpSummary.Failure(item.displayName, e.message ?: "Fehler")
                    break
                }
                failures += FreeUpSummary.Failure(item.displayName, e.message ?: e.javaClass.simpleName)
            } finally {
                file?.delete()
            }
            // Übersicht und Galerie zeigen den Fortschritt live
            if (doReplace && System.currentTimeMillis() - lastShown >= LIVE_INTERVAL_MS) {
                lastShown = System.currentTimeMillis()
                cache.show(opened.index.state)
            }
        }

        if (doReplace) {
            withContext(NonCancellable + Dispatchers.IO) {
                if (opened.deviceDir.exists()) {
                    opened.index.compact()
                    appendLog(opened.deviceDir, done, saved, failures, abort)
                }
                cache.update(opened.index.state)
            }
        }
        images.clearCache()
        return FreeUpSummary(Instant.now().toString(), doReplace, done, saved, skipped, failures, abort)
    }

    /**
     * Schließt gesicherte Originale vom Verkleinern aus ([keep] = true) bzw. gibt sie wieder frei.
     * Liefert die Zahl der Änderungen.
     */
    suspend fun setKeepOriginal(folderUri: String, backupPaths: Set<String>, keep: Boolean): Int {
        val opened = open(folderUri)
        val changes = keepOriginalChanges(opened.index.state, backupPaths, keep)
        if (changes.isEmpty()) return 0
        withContext(NonCancellable + Dispatchers.IO) {
            changes.forEach { opened.index.record(it) }
            opened.index.compact()
            val metadata = opened.deviceDir.findFile(DeviceRegistry.METADATA_DIR)
            val text = if (keep) "vom Verkleinern ausgeschlossen" else "wieder zum Verkleinern freigegeben"
            metadata?.let { runCatching { io.appendText(it, "backup_log.txt", "${Instant.now()}  ${changes.size} $text\n") } }
            IndexCache.get(context).update(opened.index.state)
        }
        return changes.size
    }

    private suspend fun replaceOne(p: PendingReplacement, index: BackupIndex, dirs: DirCache) {
        val uri = Uri.parse(p.uri)
        val entry = index.state.forPhonePath(p.phonePath).lastOrNull { it.originalSha256 == p.originalSha256 }
            ?: throw IOException("Kein Index-Eintrag")
        val backup = backupFile(dirs, entry) ?: throw IOException("Sicherung auf dem Medium fehlt")
        if (backup.length() != entry.originalSize) throw IOException("Sicherung auf dem Medium unvollständig")
        val currentSize = resolver.openAssetFileDescriptor(originalUri(uri), "r")?.use { it.length } ?: -1
        if (currentSize != p.originalSize) throw IOException("Datei wurde während des Verkleinerns verändert")
        val newFile = File(p.newFile)
        if (!newFile.exists() || newFile.length() != p.newSize) throw IOException("Verkleinerte Datei fehlt")

        // Erst den Index (Absicht) schreiben, dann ersetzen: so wird eine verkleinerte Datei
        // nach einem Abbruch nie als neues „Original“ gesichert.
        index.record(entry.asCompressed(p, Instant.now().toString()))
        try {
            io.writeSynced(uri, p.displayName) { out -> newFile.inputStream().use { it.copyTo(out, 1 shl 20) } }
            if (hashOf(uri) != p.newSha256) throw IOException("Prüfsumme nach dem Ersetzen falsch")
        } catch (e: Exception) {
            // Scheitert schon das Öffnen (z. B. keine Schreibberechtigung), ist das Original noch unverändert
            if (runCatching { hashOf(uri) == entry.originalSha256 }.getOrDefault(false)) {
                index.record(entry.asVerifiedOnly())
                throw IOException("Ersetzen fehlgeschlagen (${e.message}) – Original unverändert", e)
            }
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

    private companion object {
        const val LIVE_INTERVAL_MS = 2_000L
    }
}
