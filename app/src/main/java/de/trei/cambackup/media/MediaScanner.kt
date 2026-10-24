package de.trei.cambackup.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ein Ordner auf dem Smartphone, der Fotos oder Videos enthält. */
data class MediaFolder(val relativePath: String, val count: Int, val bytes: Long)

/**
 * Liest Fotos und Videos über den MediaStore. Nur der interne Speicher wird betrachtet – Dateien
 * auf dem Backup-Medium sollen nie als Medienordner erscheinen.
 */
class MediaScanner(private val resolver: ContentResolver) {

    /** Alle Fotos und Videos aus den gewählten Ordnern (genau diese Ordner, ohne Unterordner). */
    suspend fun scan(folders: Set<String>): List<MediaItem> = withContext(Dispatchers.IO) {
        if (folders.isEmpty()) return@withContext emptyList()
        (query(IMAGES, isVideo = false, folders) + query(VIDEOS, isVideo = true, folders))
            .sortedByDescending { it.dateTakenMillis }
    }

    /** Alle Ordner mit Fotos/Videos, größte zuerst – für die Auswahl in den Einstellungen. */
    suspend fun listFolders(): List<MediaFolder> = withContext(Dispatchers.IO) {
        (query(IMAGES, false, null) + query(VIDEOS, true, null))
            .groupBy { it.relativePath }
            .map { (path, items) -> MediaFolder(path, items.size, items.sumOf { it.sizeBytes }) }
            .sortedWith(compareByDescending<MediaFolder> { it.relativePath == CAMERA_RELATIVE_PATH }.thenByDescending { it.bytes })
    }

    private fun query(collection: Uri, isVideo: Boolean, folders: Set<String>?): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DURATION,
        )
        val (selection, args) = if (folders == null) {
            null to null
        } else {
            "${MediaStore.MediaColumns.RELATIVE_PATH} IN (${folders.joinToString { "?" }})" to folders.toTypedArray()
        }

        val result = mutableListOf<MediaItem>()
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val path = c.getString(pathCol) ?: continue
                // DATE_TAKEN ist in ms, DATE_MODIFIED in Sekunden
                val taken = if (c.isNull(takenCol)) c.getLong(modifiedCol) * 1000 else c.getLong(takenCol)
                result += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = c.getString(nameCol) ?: "unbenannt_$id",
                    relativePath = path,
                    mimeType = c.getString(mimeCol) ?: if (isVideo) "video/*" else "image/*",
                    sizeBytes = c.getLong(sizeCol),
                    dateTakenMillis = taken,
                    isVideo = isVideo,
                    durationMs = if (c.isNull(durationCol)) 0 else c.getLong(durationCol),
                )
            }
        }
        return result
    }

    companion object {
        const val CAMERA_RELATIVE_PATH = "DCIM/Camera/"

        val IMAGES: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val VIDEOS: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
