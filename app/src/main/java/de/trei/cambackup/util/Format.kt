package de.trei.cambackup.util

import java.util.Locale

/** Formatiert eine Byte-Anzahl menschenlesbar (Basis 1024), z. B. "1,5 GB". */
fun formatBytes(bytes: Long, locale: Locale = Locale.GERMANY): String {
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
