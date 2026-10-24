package de.trei.cambackup.media

import android.net.Uri

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
