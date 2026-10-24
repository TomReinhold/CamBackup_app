package de.trei.cambackup.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Liest alle Fotos und Videos aus DCIM/Camera über den MediaStore. */
class MediaScanner(private val resolver: ContentResolver) {

    suspend fun scanCameraFolder(): List<MediaItem> = withContext(Dispatchers.IO) {
        val images = query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), isVideo = false)
        val videos = query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), isVideo = true)
        (images + videos).sortedByDescending { it.dateTakenMillis }
    }

    private fun query(collection: Uri, isVideo: Boolean): List<MediaItem> {
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
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(CAMERA_RELATIVE_PATH)

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
                // DATE_TAKEN ist in ms, DATE_MODIFIED in Sekunden
                val taken = if (c.isNull(takenCol)) c.getLong(modifiedCol) * 1000 else c.getLong(takenCol)
                result += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = c.getString(nameCol) ?: "unbenannt_$id",
                    relativePath = c.getString(pathCol) ?: CAMERA_RELATIVE_PATH,
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
    }
}
