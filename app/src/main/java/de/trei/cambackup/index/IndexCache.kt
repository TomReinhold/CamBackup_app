package de.trei.cambackup.index

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Lokale Kopie des Index vom Speichermedium. Damit zeigt die Fotoansicht den Sicherungsstatus
 * auch an, wenn der Stick nicht eingesteckt ist. Maßgeblich bleibt immer der Index auf dem Medium.
 */
class IndexCache private constructor(context: Context) {

    private val file = File(context.filesDir, "index_cache.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(IndexEntry.serializer())

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<IndexEntry>> = _entries.asStateFlow()

    suspend fun update(state: IndexState) = withContext(Dispatchers.IO) {
        val list = state.entries.toList()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer, list))
        tmp.renameTo(file)
        _entries.value = list
    }

    private fun load(): List<IndexEntry> = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    companion object {
        @Volatile private var instance: IndexCache? = null

        fun get(context: Context): IndexCache =
            instance ?: synchronized(this) { instance ?: IndexCache(context.applicationContext).also { instance = it } }
    }
}
