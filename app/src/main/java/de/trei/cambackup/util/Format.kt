package de.trei.cambackup.util

import de.trei.cambackup.R
import java.util.Locale

/** "1 Datei", "2 Dateien" (in der App-Sprache) */
fun fileCount(n: Int): String = L10n.plural(R.plurals.file_count, n, n)

/** Restdauer für Fortschrittsanzeigen: "noch ca. 1 Std. 5 Min.", "noch ca. 7 Min.", "noch weniger als 1 Min." */
fun formatRemaining(seconds: Long): String {
    val (h, m) = remainingHoursMinutes(seconds) ?: return L10n.str(R.string.remaining_under_minute)
    return when {
        h == 0L -> L10n.str(R.string.remaining_minutes, m)
        m == 0L -> L10n.str(R.string.remaining_hours, h)
        else -> L10n.str(R.string.remaining_hours_minutes, h, m)
    }
}

/** Restdauer als (Stunden, Minuten), auf volle Minuten aufgerundet; null unter einer Minute. */
fun remainingHoursMinutes(seconds: Long): Pair<Long, Long>? {
    if (seconds < 60) return null
    // Aufrunden: lieber etwas früher fertig als angekündigt
    val minutes = (seconds + 59) / 60
    return minutes / 60 to minutes % 60
}

/** Formatiert eine Byte-Anzahl menschenlesbar (Basis 1024), z. B. "1,5 GB" bzw. "1.5 GB". */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(locale, "%.1f %s", value, units[unit])
}

/** Größe für Protokoll und Dateiliste auf dem Medium (immer englisch). */
fun formatBytesEn(bytes: Long): String = formatBytes(bytes, Locale.US)
