package de.trei.cambackup.compress

import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.CompressionSettings

/** Gründe, ein Bild nicht zu verkleinern; die Datei bleibt dann unverändert (gesichert ist sie trotzdem). */
enum class SkipReason(val text: String) {
    UNSUPPORTED_FORMAT("Format wird nicht verkleinert (nur JPEG)"),
    MOTION_PHOTO("Bewegtes Foto – eingebettetes Video bliebe nicht erhalten"),
    TOO_RECENT("Jünger als das Mindestalter"),
    ALREADY_COMPRESSED("Bereits von CamBackup verkleinert"),
    TOO_LITTLE_SAVING("Ersparnis unter dem Mindestwert"),
}

/** Kennzeichnung im EXIF-Feld „Software“ verkleinerter Bilder. */
const val COMPRESSED_MARKER = "CamBackup"

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Prüfungen, die ohne Lesen der Datei möglich sind. */
fun precheckImage(item: MediaItem, settings: CompressionSettings, nowMillis: Long): SkipReason? = when {
    item.mimeType.lowercase() != "image/jpeg" -> SkipReason.UNSUPPORTED_FORMAT
    item.dateTakenMillis > nowMillis - settings.minAgeDays * DAY_MS -> SkipReason.TOO_RECENT
    else -> null
}

fun isMarkedCompressed(software: String?): Boolean = software?.contains(COMPRESSED_MARKER) == true

fun markSoftware(original: String?): String =
    if (original.isNullOrBlank()) COMPRESSED_MARKER else "$original ($COMPRESSED_MARKER)"

/**
 * Erkennt Fotos mit eingebettetem Video:
 * - Google: XMP "MotionPhoto"/"MicroVideo", Dateinamen "….MP.jpg" bzw. "MVIMG_…"
 * - Samsung: Anhang "MotionPhoto_Data" am Dateiende
 */
fun looksLikeMotionPhoto(displayName: String, xmp: String?, tail: ByteArray?): Boolean {
    val name = displayName.uppercase()
    if (name.contains(".MP.") || name.startsWith("MVIMG_")) return true
    if (xmp != null && (xmp.contains("MotionPhoto") || xmp.contains("MicroVideo"))) return true
    return tail != null && tail.containsAscii("MotionPhoto_Data")
}

private fun ByteArray.containsAscii(needle: String): Boolean {
    val n = needle.toByteArray(Charsets.US_ASCII)
    outer@ for (i in 0..size - n.size) {
        for (j in n.indices) if (this[i + j] != n[j]) continue@outer
        return true
    }
    return false
}

/**
 * EXIF-Felder, die nicht übernommen werden: Bildaufbau, Vorschaubild und Ausrichtung beschreiben
 * die neue Datei nicht mehr (die Pixel sind bereits gedreht), MakerNote/XMP enthalten
 * herstellerspezifische Offsets bzw. Motion-Photo-Verweise.
 */
private val NOT_COPIED = setOf(
    "Orientation", "ImageWidth", "ImageLength", "PixelXDimension", "PixelYDimension",
    "ThumbnailImageWidth", "ThumbnailImageLength", "ThumbnailOrientation",
    "JPEGInterchangeFormat", "JPEGInterchangeFormatLength",
    "StripOffsets", "StripByteCounts", "RowsPerStrip", "Compression", "BitsPerSample",
    "PhotometricInterpretation", "SamplesPerPixel", "PlanarConfiguration", "YCbCrSubSampling",
    "NewSubfileType", "SubfileType", "MakerNote", "Xmp", "Software",
)

/** Wählt aus allen bekannten EXIF-Feldern die zu übernehmenden aus. */
fun tagsToCopy(allTags: Collection<String>, keepGps: Boolean): List<String> =
    allTags.filter { it !in NOT_COPIED && (keepGps || !it.startsWith("GPS")) }.distinct()
