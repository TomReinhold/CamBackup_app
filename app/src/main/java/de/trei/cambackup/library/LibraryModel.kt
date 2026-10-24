package de.trei.cambackup.library

import de.trei.cambackup.backup.phonePath
import de.trei.cambackup.index.EntryStatus
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.media.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class MediaStatus {
    NOT_BACKED_UP,
    /** Original liegt geprüft auf dem Medium, auf dem Smartphone noch in voller Größe. */
    BACKED_UP,
    /** Original auf dem Medium, auf dem Smartphone die verkleinerte Version. */
    COMPRESSED,
    /** Speichermedium nicht verbunden – ob die Datei gesichert ist, lässt sich nicht prüfen. */
    UNKNOWN,
}

data class LibraryItem(
    val media: MediaItem,
    val status: MediaStatus,
    val entry: IndexEntry?,
    /** false: Speichermedium nicht verbunden, der Status beruht nur auf dem lokalen Stand. */
    val driveConnected: Boolean = true,
)

/**
 * Ohne verbundenes Speichermedium wird nichts als „gesichert“ angezeigt. „Verkleinert“ bleibt
 * erkennbar – das beschreibt die Datei auf dem Smartphone selbst.
 */
fun LibraryItem.withoutDrive(): LibraryItem =
    if (status == MediaStatus.COMPRESSED) copy(driveConnected = false)
    else copy(status = MediaStatus.UNKNOWN, entry = null, driveConnected = false)

data class LibraryStats(
    val totalCount: Int,
    val totalBytes: Long,
    val backedUpCount: Int,
    val backedUpBytes: Long,
    val compressedCount: Int,
    val compressedBytes: Long,
    /** Durch Verkleinern bereits frei gewordener Platz. */
    val savedBytes: Long,
    val openCount: Int,
    val openBytes: Long,
    val unknownCount: Int = 0,
    val unknownBytes: Long = 0,
)

fun classify(item: MediaItem, index: IndexState): LibraryItem {
    val entries = index.forPhonePath(item.phonePath)
    entries.lastOrNull { it.status == EntryStatus.COMPRESSED && it.compressedSize == item.sizeBytes }
        ?.let { return LibraryItem(item, MediaStatus.COMPRESSED, it) }
    entries.lastOrNull { it.originalSize == item.sizeBytes }
        ?.let { return LibraryItem(item, MediaStatus.BACKED_UP, it) }
    return LibraryItem(item, MediaStatus.NOT_BACKED_UP, null)
}

fun statsOf(items: List<LibraryItem>): LibraryStats {
    fun sum(s: MediaStatus) = items.filter { it.status == s }
    val backed = sum(MediaStatus.BACKED_UP)
    val compressed = sum(MediaStatus.COMPRESSED)
    val open = sum(MediaStatus.NOT_BACKED_UP)
    val unknown = sum(MediaStatus.UNKNOWN)
    return LibraryStats(
        totalCount = items.size,
        totalBytes = items.sumOf { it.media.sizeBytes },
        backedUpCount = backed.size,
        backedUpBytes = backed.sumOf { it.media.sizeBytes },
        compressedCount = compressed.size,
        compressedBytes = compressed.sumOf { it.media.sizeBytes },
        savedBytes = compressed.sumOf { (it.entry?.originalSize ?: 0) - it.media.sizeBytes },
        openCount = open.size,
        openBytes = open.sumOf { it.media.sizeBytes },
        unknownCount = unknown.size,
        unknownBytes = unknown.sumOf { it.media.sizeBytes },
    )
}

/** Gruppen für die Fotoansicht: je Aufnahmetag, neueste zuerst. */
fun groupByDay(items: List<LibraryItem>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, List<LibraryItem>>> =
    items.sortedByDescending { it.media.dateTakenMillis }
        .groupBy { Instant.ofEpochMilli(it.media.dateTakenMillis).atZone(zone).toLocalDate() }
        .toList()

/**
 * Überschrift wie in Google Fotos: „Heute“, „Gestern“, „Mi., 21. Okt.“ bzw. „Wed, Oct 21“, in anderen
 * Jahren mit Jahreszahl. [todayText]/[yesterdayText] kommen aus den Ressourcen der App-Sprache.
 */
fun dayLabel(
    day: LocalDate,
    today: LocalDate,
    todayText: String,
    yesterdayText: String,
    german: Boolean = Locale.getDefault().language == "de",
): String {
    return when (day) {
        today -> todayText
        today.minusDays(1) -> yesterdayText
        else -> buildString {
            if (german) {
                val weekdays = listOf("Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa.", "So.")
                val months = listOf("Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sept.", "Okt.", "Nov.", "Dez.")
                append("${weekdays[day.dayOfWeek.value - 1]}, ${day.dayOfMonth}. ${months[day.monthValue - 1]}")
            } else {
                val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                append("${weekdays[day.dayOfWeek.value - 1]}, ${months[day.monthValue - 1]} ${day.dayOfMonth}")
            }
            if (day.year != today.year) append(if (german) " ${day.year}" else ", ${day.year}")
        }
    }
}

/** Videodauer wie „0:42“ bzw. „1:02:03“. */
fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
