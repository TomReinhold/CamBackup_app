package de.trei.cambackup.backup

import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.media.MediaItem
import kotlinx.serialization.Serializable

/**
 * Eine gesicherte Datei, die es auf dem Smartphone nicht mehr gibt.
 * [versions]: alle Sicherungen dieses Pfads (bei zwischendurch geänderten Dateien mehrere), neueste zuletzt.
 */
data class MissingItem(val phonePath: String, val versions: List<IndexEntry>) {
    val latest: IndexEntry get() = versions.last()
    val displayName: String get() = phonePath.substringAfterLast('/')
    val isVideo: Boolean get() = latest.mimeType.startsWith("video/")
    val bytes: Long get() = versions.sumOf { it.originalSize }
}

@Serializable
enum class MissingAction {
    /** Original vom Medium zurück auf das Smartphone kopieren. */
    RESTORE,
    /** Sicherung(en) auf dem Medium löschen. */
    DELETE_BACKUP,
}

/**
 * Gesicherte Dateien, die auf dem Smartphone fehlen. Nur Pfade aus den aktuell gewählten
 * Medienordnern zählen – ein abgewählter Ordner bedeutet nicht, dass seine Dateien gelöscht wurden.
 */
fun findMissing(items: List<MediaItem>, index: IndexState, folders: Set<String>): List<MissingItem> {
    val present = items.mapTo(HashSet()) { it.phonePath }
    return index.groupedByPhonePath()
        .filterKeys { path -> path !in present && folderOf(path) in folders }
        .map { (path, entries) -> MissingItem(path, entries.sortedBy { it.backedUpAt }) }
        .sortedByDescending { it.latest.dateTakenMillis }
}

/** "DCIM/Camera/IMG_1.jpg" → "DCIM/Camera/" (Schreibweise wie RELATIVE_PATH im MediaStore). */
fun folderOf(phonePath: String): String = phonePath.substringBeforeLast('/', "") + "/"
