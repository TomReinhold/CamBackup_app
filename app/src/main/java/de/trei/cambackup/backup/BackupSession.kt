package de.trei.cambackup.backup

import android.content.Context
import de.trei.cambackup.index.IndexState
import java.time.Instant

/** Bereits erledigter Teil eines fortgesetzten Backups (für einen durchgehenden Fortschritt). */
data class SessionOffset(val files: Int, val bytes: Long) {
    /** Rechnet den Fortschritt des aktuellen Laufs auf das gesamte Backup hoch. */
    fun apply(p: BackupProgress) = p.copy(
        fileIndex = p.fileIndex + files,
        fileCount = p.fileCount + files,
        // Kopieren und Prüfen zählen je einmal (siehe BackupProgress.bytesDone)
        bytesDone = p.bytesDone + bytes * 2,
        bytesTotal = p.bytesTotal + bytes * 2,
    )
}

/**
 * Ein Backup kann aus mehreren Läufen bestehen (Medium abgezogen, fortgesetzt). Gemerkt wird nur der
 * Startzeitpunkt; was seitdem schon gesichert ist, steht im Index. Das funktioniert auch, wenn die
 * App beim Abziehen beendet wurde und ihren letzten Stand nicht mehr speichern konnte.
 */
class BackupSession(context: Context) {
    private val prefs = context.getSharedPreferences("backup_session", Context.MODE_PRIVATE)

    /** Beginnt ein neues Backup oder setzt ein unterbrochenes fort; liefert den bereits erledigten Teil. */
    fun begin(index: IndexState, now: Instant = Instant.now()): SessionOffset {
        val started = prefs.getString(KEY_STARTED, null)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (started == null) {
            prefs.edit().putString(KEY_STARTED, now.toString()).apply()
            return SessionOffset(0, 0)
        }
        return offsetSince(index, started)
    }

    /** Das Backup ist vollständig durchgelaufen – das nächste beginnt wieder bei null. */
    fun complete() {
        prefs.edit().remove(KEY_STARTED).apply()
    }

    private companion object {
        const val KEY_STARTED = "started_at"
    }
}

/** Seit [started] gesicherte (und noch gültige) Dateien laut Index. */
fun offsetSince(index: IndexState, started: Instant): SessionOffset {
    val done = index.entries.filter { !it.deleted && runCatching { !Instant.parse(it.backedUpAt).isBefore(started) }.getOrDefault(false) }
    return SessionOffset(done.size, done.sumOf { it.originalSize })
}
