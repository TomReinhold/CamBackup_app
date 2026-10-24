package de.trei.cambackup.freeup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Lokale Ablage für das letzte Ergebnis. Beobachtbar, damit die Oberfläche Änderungen des
 * Hintergrund-Jobs (gleicher Prozess) sofort sieht.
 */
class FreeUpStore private constructor(context: Context) {

    private val dir = File(context.filesDir, "freeup").apply { mkdirs() }
    private val summaryFile = File(dir, "summary.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        // Vorbereitete Stapel aus früheren Versionen gibt es nicht mehr
        File(dir, "pending.json").delete()
    }

    private val _summary = MutableStateFlow(runCatching { json.decodeFromString(FreeUpSummary.serializer(), summaryFile.readText()) }.getOrNull())
    /** Letztes, noch nicht bestätigtes Ergebnis. */
    val summary: StateFlow<FreeUpSummary?> = _summary.asStateFlow()

    fun saveSummary(summary: FreeUpSummary?) {
        if (summary == null) {
            summaryFile.delete()
        } else {
            // Atomar schreiben (temporäre Datei, dann umbenennen)
            val tmp = File(summaryFile.path + ".tmp")
            tmp.writeText(json.encodeToString(FreeUpSummary.serializer(), summary))
            tmp.renameTo(summaryFile)
        }
        _summary.value = summary
    }

    companion object {
        @Volatile private var instance: FreeUpStore? = null

        fun get(context: Context): FreeUpStore =
            instance ?: synchronized(this) { instance ?: FreeUpStore(context.applicationContext).also { instance = it } }
    }
}
