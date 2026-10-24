package de.trei.cambackup.backup

import de.trei.cambackup.index.EntryStatus
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.FolderLayout
import java.time.Instant
import java.time.ZoneId

data class BackupPlan(
    val toCopy: List<MediaItem>,
    val alreadyBackedUp: Int,
    /** Dateien > 4 GB, die auf FAT32 nicht gespeichert werden können. */
    val tooLargeForFileSystem: List<MediaItem>,
    /** Bereits gesicherte Dateien, die für die eingestellte Ablage in einen anderen Ordner gehören. */
    val toMove: List<Relocation> = emptyList(),
    /** Ablage, die auf dem Medium bisher vorliegt (aus dem Index erkannt). */
    val currentLayout: DetectedLayout? = null,
) {
    val bytesToCopy: Long = toCopy.sumOf { it.sizeBytes }
    val hasWork: Boolean get() = toCopy.isNotEmpty() || toMove.isNotEmpty()
}

val MediaItem.phonePath: String get() = relativePath + displayName

/** Größte Datei, die FAT32 speichern kann (4 GiB − 1 Byte). */
const val FAT32_MAX_FILE_SIZE = 0xFFFF_FFFFL

fun isFat(fsType: String?): Boolean = fsType?.lowercase() in setOf("vfat", "msdos", "fat", "fat32")

/**
 * Gilt die Datei auf dem Smartphone bereits als gesichert? Erkannt wird sowohl das unveränderte
 * Original als auch die bereits verkleinerte Version – letztere darf nie erneut als „Original“
 * gesichert werden.
 */
fun isAlreadyBackedUp(item: MediaItem, entries: List<IndexEntry>): Boolean = entries.any { e ->
    when (e.status) {
        EntryStatus.VERIFIED -> e.originalSize == item.sizeBytes
        EntryStatus.COMPRESSED -> e.compressedSize == item.sizeBytes
    }
}

fun planBackup(items: List<MediaItem>, index: IndexState, fsType: String?): BackupPlan {
    val (done, open) = items.partition { isAlreadyBackedUp(it, index.forPhonePath(it.phonePath)) }
    val (tooLarge, toCopy) = if (isFat(fsType)) {
        open.partition { it.sizeBytes > FAT32_MAX_FILE_SIZE }
    } else {
        emptyList<MediaItem>() to open
    }
    // Älteste zuerst, damit ein Abbruch die chronologisch frühen Dateien bereits gesichert hat
    return BackupPlan(toCopy.sortedBy { it.dateTakenMillis }, done.size, tooLarge)
}

sealed interface SpaceCheck {
    val requiredBytes: Long

    data class Enough(override val requiredBytes: Long, val freeBytes: Long) : SpaceCheck
    data class NotEnough(override val requiredBytes: Long, val freeBytes: Long) : SpaceCheck
    /** Freier Platz konnte nicht ermittelt werden. */
    data class Unknown(override val requiredBytes: Long) : SpaceCheck
}

/** Feste Reserve für Metadaten und Dateisystem-Verwaltung. */
const val SPACE_RESERVE_FIXED = 50L * 1024 * 1024

/** Benötigter Platz inkl. Reserve (50 MB + 1 % der Datenmenge). */
fun requiredSpace(bytesToCopy: Long): Long =
    if (bytesToCopy == 0L) 0 else bytesToCopy + SPACE_RESERVE_FIXED + bytesToCopy / 100

fun checkSpace(bytesToCopy: Long, freeBytes: Long?): SpaceCheck {
    val required = requiredSpace(bytesToCopy)
    return when {
        freeBytes == null -> SpaceCheck.Unknown(required)
        freeBytes >= required -> SpaceCheck.Enough(required, freeBytes)
        else -> SpaceCheck.NotEnough(required, freeBytes)
    }
}

/** Unterordner im Geräteordner nach Aufnahmedatum, z. B. "2026/01". */
fun backupSubPath(dateTakenMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli(dateTakenMillis).atZone(zone)
    return "%04d/%02d".format(date.year, date.monthValue)
}

/**
 * Zielordner auf dem Medium (relativ zum Geräteordner). Bei mehreren Medienordnern liegt jeder
 * in einem eigenen Unterordner mit seinem Pfad, z. B. "Pictures/Screenshots/2026/10".
 */
fun backupDirFor(
    relativePath: String,
    dateTakenMillis: Long,
    layout: FolderLayout,
    separateFolders: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val folder = if (separateFolders) relativePath.trim('/') else ""
    val date = when (layout) {
        FolderLayout.FLAT -> ""
        FolderLayout.YEAR -> backupSubPath(dateTakenMillis, zone).substringBefore('/')
        FolderLayout.YEAR_MONTH -> backupSubPath(dateTakenMillis, zone)
    }
    return listOf(folder, date).filter { it.isNotEmpty() }.joinToString("/")
}

/** Pfad relativ zum Geräteordner; [subPath] "" steht für den Geräteordner selbst. */
fun joinPath(subPath: String, name: String): String = if (subPath.isEmpty()) name else "$subPath/$name"

/** Liefert [name] oder bei Kollision "Name (1).ext", "Name (2).ext", … */
fun uniqueName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 }
    val base = dot?.let { name.substring(0, it) } ?: name
    val ext = dot?.let { name.substring(it) } ?: ""
    return generateSequence(1) { it + 1 }.map { "$base ($it)$ext" }.first { !taken(it) }
}
