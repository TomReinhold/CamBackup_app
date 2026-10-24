package de.trei.cambackup.backup

import de.trei.cambackup.work.JobControl
import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import de.trei.cambackup.index.BackupIndex
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.settings.FolderLayout
import de.trei.cambackup.storage.DirCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZoneId

/** Eine gesicherte Datei, die in einen anderen Ordner auf dem Medium gehört. */
data class Relocation(val entry: IndexEntry, val targetDir: String) {
    val currentDir: String get() = entry.backupPath.substringBeforeLast('/', "")
    val name: String get() = entry.backupPath.substringAfterLast('/')
}

/** Ordner, in dem eine Sicherung bei dieser Ablage liegen soll. */
fun targetDirOf(entry: IndexEntry, layout: FolderLayout, separateFolders: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
    backupDirFor(folderOf(entry.phonePath), entry.dateTakenMillis, layout, separateFolders, zone)

/**
 * Liegt [entry] schon passend zu dieser Ablage? Jahr und Monat werden mit ±14 Stunden Spielraum
 * verglichen: wurde die Datei in einer anderen Zeitzone gesichert (Reise), kann ein Foto kurz vor
 * Mitternacht am Monatsende im Nachbarmonat liegen – deshalb wird es nicht hin- und hergeschoben.
 */
fun placedFor(entry: IndexEntry, layout: FolderLayout, separateFolders: Boolean, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val current = entry.backupPath.substringBeforeLast('/', "")
    return ZONE_SLACK_MS.any { d ->
        current == backupDirFor(folderOf(entry.phonePath), entry.dateTakenMillis + d, layout, separateFolders, zone)
    }
}

private val ZONE_SLACK_MS = listOf(0L, -14 * 3_600_000L, 14 * 3_600_000L)

/** Alle Sicherungen, die für die eingestellte Ablage verschoben werden müssen. */
fun planRelocation(index: IndexState, layout: FolderLayout, separateFolders: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<Relocation> =
    index.entries
        .filter { !placedFor(it, layout, separateFolders, zone) }
        .map { Relocation(it, targetDirOf(it, layout, separateFolders, zone)) }
        .sortedBy { it.entry.backupPath }

/** Ablage, die auf dem Medium tatsächlich vorliegt – erkannt aus den Pfaden im Index. */
data class DetectedLayout(val layout: FolderLayout, val separateFolders: Boolean)

/** Welche Ablage passt zu den meisten Sicherungen? null, wenn der Index leer ist. */
fun detectLayout(index: IndexState, zone: ZoneId = ZoneId.systemDefault()): DetectedLayout? {
    if (index.size == 0) return null
    val options = FolderLayout.entries.flatMap { l -> listOf(DetectedLayout(l, false), DetectedLayout(l, true)) }
    return options.maxBy { o -> index.entries.count { placedFor(it, o.layout, o.separateFolders, zone) } }
}

data class RelocationResult(val moved: Int, val failures: List<RunSummary.Failure>, val abortReason: String?)

/**
 * Verschiebt Sicherungen innerhalb des Mediums in die eingestellte Ordnerstruktur. Der Inhalt
 * bleibt unverändert (Verschieben im Dateisystem, kein Kopieren).
 *
 * Reihenfolge je Datei: verschieben → neuen Indexeintrag schreiben → alten entfernen. Bricht der
 * Lauf dazwischen ab (Medium abgezogen), stellt der nächste Lauf den Index anhand von Name, Größe
 * und Prüfsumme richtig (siehe [repair]).
 */
class Relocator(private val context: Context) {

    private val resolver = context.contentResolver

    suspend fun run(
        deviceDir: DocumentFile,
        index: BackupIndex,
        moves: List<Relocation>,
        abortReason: () -> String?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): RelocationResult = withContext(Dispatchers.IO) {
        val dirs = DirCache(deviceDir)
        val touched = linkedSetOf<String>()
        val failures = mutableListOf<RunSummary.Failure>()
        var moved = 0
        var abort: String? = null
        var failedInRow = 0
        for ((i, m) in moves.withIndex()) {
            JobControl.waitWhilePaused(JobControl.BACKUP) { abortReason() != null }
            abortReason()?.let { abort = it }
            if (abort != null) break
            onProgress(i, moves.size)
            try {
                // Eintrag kann durch eine frühere Datei dieser Liste schon bereinigt sein
                if (!index.state.containsBackupPath(m.entry.backupPath)) continue
                if (moveOne(m, dirs, index)) moved++
                touched += m.currentDir
                failedInRow = 0
            } catch (e: Exception) {
                if (!deviceDir.exists()) {
                    abort = BackupEngine.MEDIUM_DISCONNECTED
                    break
                }
                failures += RunSummary.Failure(m.name, e.message ?: e.javaClass.simpleName)
                if (++failedInRow >= 3) {
                    abort = "Speichermedium antwortet nicht mehr"
                    break
                }
            }
        }
        onProgress(moves.size, moves.size)
        if (deviceDir.exists()) removeEmptyDirs(deviceDir, touched)
        RelocationResult(moved, failures, abort)
    }

    /** Liefert true, wenn verschoben wurde; false, wenn nur der Index berichtigt werden musste. */
    private suspend fun moveOne(m: Relocation, dirs: DirCache, index: BackupIndex): Boolean {
        val entry = m.entry
        val source = dirs.files(m.currentDir)[m.name]
        if (source == null) {
            repair(m, dirs, index)
            return false
        }
        if (source.length() != entry.originalSize) throw IOException("Größe auf dem Medium stimmt nicht – bleibt unverändert")

        val targetFiles = dirs.files(m.targetDir)
        val currentFiles = dirs.files(m.currentDir)
        // Gleichnamige Datei im Ziel: neuer Name, der in beiden Ordnern frei ist (nichts wird überschrieben)
        val finalName = uniqueName(m.name) { it in targetFiles || (it != m.name && it in currentFiles) }
        var file = source
        if (finalName != m.name) {
            val renamed = DocumentsContract.renameDocument(resolver, source.uri, finalName)
                ?: throw IOException("Umbenennen nach „$finalName“ fehlgeschlagen")
            currentFiles.remove(m.name)
            file = DocumentFile.fromSingleUri(context, renamed)!!
            currentFiles[finalName] = file
        }
        val target = DocumentsContract.moveDocument(resolver, file.uri, dirs.dir(m.currentDir).uri, dirs.dir(m.targetDir).uri)
            ?: throw IOException("Verschieben nach „${m.targetDir.ifEmpty { "/" }}“ fehlgeschlagen")
        currentFiles.remove(finalName)
        targetFiles[finalName] = DocumentFile.fromSingleUri(context, target)!!

        index.record(entry.copy(backupPath = joinPath(m.targetDir, finalName)))
        index.remove(entry)
        return true
    }

    /**
     * Die Datei fehlt am alten Ort. Entweder wurde sie schon verschoben, aber der Index nicht mehr
     * fortgeschrieben (Abbruch), oder sie ist wirklich weg. Nur eine Datei mit gleicher Größe und
     * Prüfsumme, die noch keinem Eintrag gehört, wird als die verschobene Sicherung übernommen.
     */
    private suspend fun repair(m: Relocation, dirs: DirCache, index: BackupIndex) {
        val entry = m.entry
        // Schon richtig eingetragen (neuer Eintrag geschrieben, alter noch nicht entfernt)?
        if (index.state.forPhonePath(entry.phonePath).any { it.backupPath != entry.backupPath && it.originalSha256 == entry.originalSha256 }) {
            index.remove(entry)
            return
        }
        val files = dirs.files(m.targetDir)
        val dot = m.name.lastIndexOf('.').takeIf { it > 0 }
        val base = dot?.let { m.name.substring(0, it) } ?: m.name
        val ext = dot?.let { m.name.substring(it) } ?: ""
        val names = listOf(m.name) + (1..50).map { "$base ($it)$ext" }
        for (name in names) {
            val candidate = files[name] ?: continue
            val path = joinPath(m.targetDir, name)
            if (index.state.containsBackupPath(path) || candidate.length() != entry.originalSize) continue
            val hash = (resolver.openInputStream(candidate.uri) ?: continue).use { sha256(it) }
            if (hash.sha256 == entry.originalSha256) {
                index.record(entry.copy(backupPath = path))
                index.remove(entry)
                return
            }
        }
        throw IOException("Sicherung fehlt auf dem Medium (${entry.backupPath})")
    }

    /** Entfernt leer gewordene alte Ordner (z. B. "2024"), von innen nach außen. */
    private fun removeEmptyDirs(deviceDir: DocumentFile, dirs: Set<String>) {
        val candidates = dirs.flatMap { d ->
            val parts = d.split('/').filter { it.isNotEmpty() }
            (parts.size downTo 1).map { parts.take(it).joinToString("/") }
        }.distinct().sortedByDescending { it.count { c -> c == '/' } }
        for (path in candidates) {
            val dir = path.split('/').fold<String, DocumentFile?>(deviceDir) { p, seg -> p?.findFile(seg) } ?: continue
            if (dir.isDirectory && dir.listFiles().isEmpty()) runCatching { dir.delete() }
        }
    }
}
