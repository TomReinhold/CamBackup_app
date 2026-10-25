package de.trei.cambackup.freeup

import de.trei.cambackup.compress.shrinkSettingsKey
import de.trei.cambackup.compress.SkipReason
import de.trei.cambackup.compress.NOT_WORTH_REASONS
import android.os.SystemClock
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
import de.trei.cambackup.R
import de.trei.cambackup.util.AppError
import de.trei.cambackup.util.L10n
import de.trei.cambackup.util.Msg
import de.trei.cambackup.util.formatBytesEn
import de.trei.cambackup.util.msg
import de.trei.cambackup.util.toMsg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant

data class FreeUpProgress(
    val done: Int,
    val total: Int,
    val currentName: String,
    val replacing: Boolean,
    /** Geschätzte Restdauer in Sekunden, -1 solange noch keine Schätzung möglich ist. */
    val remainingSeconds: Long = -1,
)

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
        val metadata = deviceDir.findFile(DeviceRegistry.METADATA_DIR) ?: throw AppError(R.string.err_no_metadata)
        Opened(deviceDir, BackupIndex.open(io, metadata))
    }

    /** Überblick für die Oberfläche (ohne Hash-Prüfung, schnell). */
    suspend fun overview(folderName: String): FreeUpOverview {
        val opened = open(folderName)
        IndexCache.get(context).update(opened.index.state)
        val s = settings.current()
        return overviewFor(scanner.scan(s.mediaFolders), opened.index.state, s)
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
        // Nur was alt genug ist – so zählt der Fortschritt dieselben Dateien wie die Übersicht
        val candidates = compressTargets(overviewFor(scanner.scan(s.mediaFolders), opened.index.state, s).candidates, s.minAgeDays, System.currentTimeMillis())
        val dirs = DirCache(opened.deviceDir)
        val cache = IndexCache.get(context)
        val now = System.currentTimeMillis()
        images.clearCache()

        var done = 0
        var saved = 0L
        val skipped = linkedMapOf<String, Int>()
        val failures = mutableListOf<Pair<String, Msg>>()
        fun skip(reason: String) { skipped[reason] = (skipped[reason] ?: 0) + 1 }
        var abort: Msg? = null
        var lastShown = 0L

        // Für die Zeitschätzung: wie viele Fotos und Video-Sekunden ab Datei i noch offen sind
        val restImages = IntArray(candidates.size + 1)
        val restVideoSeconds = DoubleArray(candidates.size + 1)
        for (j in candidates.indices.reversed()) {
            val m = candidates[j].item
            restImages[j] = restImages[j + 1] + if (m.isVideo) 0 else 1
            restVideoSeconds[j] = restVideoSeconds[j + 1] + if (m.isVideo) m.durationMs / 1000.0 else 0.0
        }
        val eta = FreeUpEta()
        var fileStart = 0L
        // Lohnt sich eine Datei mit diesen Einstellungen nicht, im Index vermerken – sonst wird sie bei jedem
        // Lauf neu versucht und zählt in der Übersicht weiter als „wird verkleinert“
        suspend fun noteNotWorth(c: Candidate, reason: SkipReason) {
            if (doReplace && reason in NOT_WORTH_REASONS) {
                runCatching { opened.index.record(c.entry.copy(notWorthFor = shrinkSettingsKey(s, c.item.isVideo))) }
            }
        }

        for ((i, c) in candidates.withIndex()) {
            // Die vorige Datei ist fertig – vor dem Warten auf Pause, damit Pausen nicht mitzählen
            if (i > 0) candidates[i - 1].item.let { eta.record(it.isVideo, it.durationMs, SystemClock.elapsedRealtime() - fileStart) }
            JobControl.waitWhilePaused(JobControl.FREE_UP) { !caller.isActive }
            if (!caller.isActive) {
                abort = msg(R.string.abort_cancelled)
                break
            }
            fileStart = SystemClock.elapsedRealtime()
            val item = c.item
            onProgress(
                FreeUpProgress(
                    i, candidates.size, item.displayName, replacing = doReplace,
                    remainingSeconds = eta.remainingSeconds(restImages[i], restVideoSeconds[i]),
                ),
            )
            val pre = if (item.isVideo) precheckVideo(item, s, now) else precheckImage(item, s, now)
            if (pre != null) {
                skip(pre.text)
                noteNotWorth(c, pre)
                continue
            }
            if (!prepareSpaceOk(context.cacheDir.usableSpace, item.sizeBytes)) {
                abort = msg(R.string.abort_phone_full)
                break
            }
            var file: File? = null
            try {
                if (!backupPresent(dirs, c.entry)) {
                    if (!opened.deviceDir.exists()) {
                        abort = msg(R.string.abort_disconnected)
                        break
                    }
                    failures += item.displayName to msg(R.string.err_backup_missing_or_incomplete)
                    continue
                }
                // Sicherheitsregel: nur ein bitgenau gesichertes Original darf ersetzt werden
                if (hashOf(item.uri) != c.entry.originalSha256) {
                    skip(L10n.str(R.string.skip_changed))
                    continue
                }
                when (val r = if (item.isVideo) videos.shrink(item, s, now) else images.shrink(item, s, now)) {
                    is ShrinkResult.Skipped -> {
                        skip(r.reason.text)
                        noteNotWorth(c, r.reason)
                    }
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
                abort = msg(R.string.abort_cancelled)
                break
            } catch (e: Exception) {
                if (!opened.deviceDir.exists()) {
                    abort = msg(R.string.abort_disconnected)
                    failures += item.displayName to e.toMsg()
                    break
                }
                failures += item.displayName to e.toMsg()
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
        return FreeUpSummary(Instant.now().toString(), doReplace, done, saved, skipped, failures.map { FreeUpSummary.Failure(it.first, it.second.ui) }, abort?.ui)
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
            val text = if (keep) "excluded from compression" else "allowed for compression again"
            metadata?.let { runCatching { io.appendText(it, "backup_log.txt", "${Instant.now()}  ${changes.size} $text\n") } }
            IndexCache.get(context).update(opened.index.state)
        }
        return changes.size
    }

    private suspend fun replaceOne(p: PendingReplacement, index: BackupIndex, dirs: DirCache) {
        val uri = Uri.parse(p.uri)
        val entry = index.state.forPhonePath(p.phonePath).lastOrNull { it.originalSha256 == p.originalSha256 }
            ?: throw AppError(R.string.err_no_index_entry)
        val backup = backupFile(dirs, entry) ?: throw AppError(R.string.err_backup_missing)
        if (backup.length() != entry.originalSize) throw AppError(R.string.err_backup_incomplete)
        val currentSize = resolver.openAssetFileDescriptor(originalUri(uri), "r")?.use { it.length } ?: -1
        if (currentSize != p.originalSize) throw AppError(R.string.err_changed_while_compressing)
        val newFile = File(p.newFile)
        if (!newFile.exists() || newFile.length() != p.newSize) throw AppError(R.string.err_compressed_missing)

        // Erst den Index (Absicht) schreiben, dann ersetzen: so wird eine verkleinerte Datei
        // nach einem Abbruch nie als neues „Original“ gesichert.
        index.record(entry.asCompressed(p, Instant.now().toString()))
        try {
            io.writeSynced(uri, p.displayName) { out -> newFile.inputStream().use { it.copyTo(out, 1 shl 20) } }
            if (hashOf(uri) != p.newSha256) throw AppError(R.string.err_checksum_after_replace)
        } catch (e: Exception) {
            // Scheitert schon das Öffnen (z. B. keine Schreibberechtigung), ist das Original noch unverändert
            if (runCatching { hashOf(uri) == entry.originalSha256 }.getOrDefault(false)) {
                index.record(entry.asVerifiedOnly())
                throw AppError(msg(R.string.err_replace_failed_unchanged, e.toMsg()), e)
            }
            restoreOriginal(uri, p, backup, index, entry)
            throw AppError(msg(R.string.err_replace_failed_restored, e.toMsg()), e)
        }
    }

    /** Spielt das Original vom Medium zurück auf das Smartphone. */
    private suspend fun restoreOriginal(uri: Uri, p: PendingReplacement, backup: DocumentFile, index: BackupIndex, entry: IndexEntry) {
        io.writeSynced(uri, p.displayName) { out ->
            (resolver.openInputStream(backup.uri) ?: throw AppError(R.string.err_backup_unreadable)).use { it.copyTo(out, 1 shl 20) }
        }
        if (hashOf(uri) != entry.originalSha256) {
            throw AppError(R.string.err_restore_failed_warning, entry.backupPath)
        }
        index.record(entry.asVerifiedOnly())
    }

    /** Protokolliert das Ersetzen in Metadata/backup_log.txt – nachvollziehbar auch am PC. */
    private fun appendLog(deviceDir: DocumentFile, replaced: Int, saved: Long, failures: List<Pair<String, Msg>>, abort: Msg?) {
        val metadata = deviceDir.findFile(DeviceRegistry.METADATA_DIR) ?: return
        val line = buildString {
            append("${Instant.now()}  Replaced: $replaced compressed on the phone (${formatBytesEn(saved)} freed), ${failures.size} errors")
            abort?.let { append(" – aborted: ${it.en}") }
            append("\n")
            failures.forEach { (name, reason) -> append("    Error: $name: ${reason.en}\n") }
        }
        runCatching { io.appendText(metadata, "backup_log.txt", line) }
    }

    private suspend fun hashOf(uri: Uri): String =
        withContext(Dispatchers.IO) { (resolver.openInputStream(originalUri(uri)) ?: throw AppError(R.string.err_unreadable)).use { sha256(it).sha256 } }

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
