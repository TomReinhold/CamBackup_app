package de.trei.cambackup.freeup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Lokale Ablage für die vorbereiteten Ersetzungen und das letzte Ergebnis. Beobachtbar, damit
 * die Oberfläche Änderungen des Hintergrund-Jobs (gleicher Prozess) sofort sieht.
 */
class FreeUpStore private constructor(context: Context) {

    private val dir = File(context.filesDir, "freeup").apply { mkdirs() }
    private val batchFile = File(dir, "pending.json")
    private val summaryFile = File(dir, "summary.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _batch = MutableStateFlow(read(batchFile, PendingBatch.serializer()))
    val batch: StateFlow<PendingBatch?> = _batch.asStateFlow()

    private val _summary = MutableStateFlow(read(summaryFile, FreeUpSummary.serializer()))
    /** Letztes, noch nicht bestätigtes Ergebnis. */
    val summary: StateFlow<FreeUpSummary?> = _summary.asStateFlow()

    fun saveBatch(batch: PendingBatch?) {
        if (batch == null) batchFile.delete() else write(batchFile, json.encodeToString(PendingBatch.serializer(), batch))
        _batch.value = batch
    }

    fun saveSummary(summary: FreeUpSummary?) {
        if (summary == null) summaryFile.delete() else write(summaryFile, json.encodeToString(FreeUpSummary.serializer(), summary))
        _summary.value = summary
    }

    private fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()

    /** Schreibt atomar (temporäre Datei, dann umbenennen). */
    private fun write(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    companion object {
        @Volatile private var instance: FreeUpStore? = null

        fun get(context: Context): FreeUpStore =
            instance ?: synchronized(this) { instance ?: FreeUpStore(context.applicationContext).also { instance = it } }
    }
}
