package de.trei.cambackup.backup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Ergebnis eines Backup-Laufs, dauerhaft gespeichert – übersteht Abbruch und App-Neustart. */
@Serializable
data class RunSummary(
    val finishedAt: String,
    val copied: Int,
    val adopted: Int,
    val bytesCopied: Long,
    val failures: List<Failure>,
    /** Gesetzt, wenn der Lauf vorzeitig endete oder gar nicht starten konnte. */
    val abortReason: String?,
    /** In die neue Ordnerstruktur verschobene, bereits gesicherte Dateien. */
    val moved: Int = 0,
) {
    @Serializable
    data class Failure(val name: String, val reason: String)

    val isComplete: Boolean get() = abortReason == null && failures.isEmpty()
}

/** Speichert die Zusammenfassung des letzten Laufs, bis der Nutzer sie bestätigt. */
class LastRunStore(context: Context) {

    private val prefs = context.getSharedPreferences("last_run", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Noch nicht bestätigte Zusammenfassung, sonst null. */
    fun unseen(): RunSummary? = prefs.getString(KEY_SUMMARY, null)
        ?.takeUnless { prefs.getBoolean(KEY_SEEN, false) }
        ?.let { runCatching { json.decodeFromString(RunSummary.serializer(), it) }.getOrNull() }

    fun save(summary: RunSummary) {
        // commit statt apply: der Prozess kann direkt danach beendet werden
        prefs.edit()
            .putString(KEY_SUMMARY, json.encodeToString(RunSummary.serializer(), summary))
            .putBoolean(KEY_SEEN, false)
            .commit()
    }

    fun markSeen() {
        prefs.edit().putBoolean(KEY_SEEN, true).apply()
    }

    /**
     * Beobachtet die unbestätigte Zusammenfassung. Der Hintergrund-Job läuft im selben Prozess,
     * daher kommt eine neue Zusammenfassung auch dann an, wenn sie erst nach dem Abbruch-Signal
     * geschrieben wird.
     */
    fun unseenFlow(): Flow<RunSummary?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(unseen()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(unseen())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private companion object {
        const val KEY_SUMMARY = "summary"
        const val KEY_SEEN = "seen"
    }
}
