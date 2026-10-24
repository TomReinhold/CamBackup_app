package de.trei.cambackup.compress

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Ergebnis einer Probe-Verkleinerung. */
data class TrialResult(
    val created: Int,
    val bytesBefore: Long,
    val bytesAfter: Long,
    /** Nicht verkleinerbar, je Grund. */
    val skipped: Map<String, Int>,
    val failures: Int,
)

/**
 * Probe-Verkleinerung einzelner Dateien: die verkleinerte Version wird als zusätzliche Datei im
 * Album „CamBackup Probe“ gespeichert (Bilder unter Pictures, Videos unter Movies). Das Original
 * bleibt unverändert – so lässt sich das Ergebnis in jeder Galerie-App neben dem Original ansehen.
 */
class TrialCompressor(private val context: Context) {

    private val resolver = context.contentResolver
    private val images = ImageShrinker(context)
    private val videos = VideoShrinker(context)

    suspend fun run(items: List<MediaItem>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): TrialResult {
        // Mindestalter und Mindest-Ersparnis gelten hier nicht – die Probe soll immer etwas zeigen
        val s = SettingsRepository(context).current().copy(minAgeDays = 0, minSavingsPercent = 0)
        val now = System.currentTimeMillis()
        var created = 0
        var before = 0L
        var after = 0L
        var failures = 0
        val skipped = linkedMapOf<String, Int>()
        for ((i, item) in items.withIndex()) {
            onProgress(i, items.size)
            try {
                when (val r = if (item.isVideo) videos.shrink(item, s, now) else images.shrink(item, s, now)) {
                    is ShrinkResult.Skipped -> skipped[r.reason.text] = (skipped[r.reason.text] ?: 0) + 1
                    is ShrinkResult.Ready -> try {
                        save(item, r)
                        created++
                        before += r.originalBytes
                        after += r.newBytes
                    } finally {
                        r.file.delete()
                    }
                }
            } catch (e: Exception) {
                failures++
            }
        }
        return TrialResult(created, before, after, skipped, failures)
    }

    /** Anzahl der vorhandenen Probe-Dateien. */
    suspend fun count(): Int = withContext(Dispatchers.IO) {
        COLLECTIONS.sumOf { (collection, path) ->
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(path), null)
                ?.use { it.count } ?: 0
        }
    }

    /** Löscht alle Probe-Dateien (die App hat sie selbst angelegt, daher ohne Rückfrage möglich). */
    suspend fun deleteAll(): Int = withContext(Dispatchers.IO) {
        COLLECTIONS.sumOf { (collection, path) ->
            resolver.delete(collection, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(path))
        }
    }

    private suspend fun save(item: MediaItem, r: ShrinkResult.Ready) = withContext(Dispatchers.IO) {
        val ext = r.file.extension
        val base = item.displayName.substringBeforeLast('.')
        val (collection, path) = if (item.isVideo) COLLECTIONS[1] else COLLECTIONS[0]
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_probe.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, if (item.isVideo) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            // Gleiches Aufnahmedatum: in der Zeitleiste der Galerie steht die Probe direkt neben dem Original
            put(MediaStore.MediaColumns.DATE_TAKEN, item.dateTakenMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Probe-Datei konnte nicht angelegt werden")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("Probe-Datei nicht beschreibbar")).use { out ->
                r.file.inputStream().use { it.copyTo(out, 1 shl 20) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    companion object {
        const val ALBUM = "CamBackup Probe"

        private val COLLECTIONS = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${android.os.Environment.DIRECTORY_PICTURES}/$ALBUM/",
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${android.os.Environment.DIRECTORY_MOVIES}/$ALBUM/",
        )
    }
}
