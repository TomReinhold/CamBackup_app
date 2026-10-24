package de.trei.cambackup.media

import android.net.Uri
import android.provider.MediaStore

/**
 * URI zum Lesen der **unveränderten** Datei. Ohne setRequireOriginal schwärzt Android beim Lesen
 * die GPS-Daten in Fotos und Videos – die Sicherung wäre dann nicht bitgenau und der Ort ginge
 * verloren. Erfordert die Berechtigung ACCESS_MEDIA_LOCATION. Nur für content://-URIs nötig.
 */
fun originalUri(uri: Uri): Uri = if (uri.scheme == "content") MediaStore.setRequireOriginal(uri) else uri

val MediaItem.readUri: Uri get() = originalUri(uri)

/** Ein Foto oder Video aus dem Kameraordner, wie es der MediaStore liefert. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Aufnahmezeitpunkt in Millisekunden seit 1970 (Fallback: Änderungsdatum). */
    val dateTakenMillis: Long,
    val isVideo: Boolean,
    /** Nur bei Videos gesetzt, sonst 0. */
    val durationMs: Long = 0,
)

data class MediaSummary(
    val imageCount: Int,
    val imageBytes: Long,
    val videoCount: Int,
    val videoBytes: Long,
) {
    val totalCount: Int get() = imageCount + videoCount
    val totalBytes: Long get() = imageBytes + videoBytes

    companion object {
        fun of(items: List<MediaItem>): MediaSummary {
            val (videos, images) = items.partition { it.isVideo }
            return MediaSummary(
                imageCount = images.size,
                imageBytes = images.sumOf { it.sizeBytes },
                videoCount = videos.size,
                videoBytes = videos.sumOf { it.sizeBytes },
            )
        }
    }
}
