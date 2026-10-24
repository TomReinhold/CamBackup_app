package de.trei.cambackup.util

import java.util.Locale

/** "1 Datei", "2 Dateien" */
fun fileCount(n: Int): String = if (n == 1) "1 Datei" else "$n Dateien"

/** Restdauer für Fortschrittsanzeigen: "noch ca. 1 Std. 5 Min.", "noch ca. 7 Min.", "noch weniger als 1 Min." */
fun formatRemaining(seconds: Long): String {
    if (seconds < 60) return "noch weniger als 1 Min."
    // Auf volle Minuten aufrunden: lieber etwas früher fertig als angekündigt
    val minutes = (seconds + 59) / 60
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "noch ca. $m Min."
        m == 0L -> "noch ca. $h Std."
        else -> "noch ca. $h Std. $m Min."
    }
}

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
