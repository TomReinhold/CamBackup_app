package de.trei.cambackup.compress

import androidx.annotation.StringRes
import de.trei.cambackup.R
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.util.L10n
import de.trei.cambackup.settings.CompressionSettings

/** Gründe, ein Bild nicht zu verkleinern; die Datei bleibt dann unverändert (gesichert ist sie trotzdem). */
enum class SkipReason(@StringRes private val textRes: Int) {
    UNSUPPORTED_FORMAT(R.string.skip_format),
    HDR_VIDEO(R.string.skip_hdr),
    MOTION_PHOTO(R.string.skip_motion_photo),
    TOO_RECENT(R.string.skip_too_recent),
    ALREADY_COMPRESSED(R.string.skip_already_compressed),
    TOO_LITTLE_SAVING(R.string.skip_too_little_saving);

    /** Grund in der App-Sprache. */
    val text: String get() = L10n.str(textRes)
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

/**
 * Prüfungen für Videos vor dem (langsamen) Neu-Kodieren. Liegt die Bitrate schon unter dem Ziel,
 * würde das Video nicht kleiner – dann gar nicht erst kodieren.
 */
fun precheckVideo(item: MediaItem, settings: CompressionSettings, nowMillis: Long): SkipReason? = when {
    item.mimeType.lowercase() != "video/mp4" || item.durationMs <= 0 -> SkipReason.UNSUPPORTED_FORMAT
    item.dateTakenMillis > nowMillis - settings.minAgeDays * DAY_MS -> SkipReason.TOO_RECENT
    !worthReplacing(item.sizeBytes, estimatedVideoBytes(item.durationMs, settings), settings.minSavingsPercent) ->
        SkipReason.TOO_LITTLE_SAVING
    else -> null
}

/**
 * Zielgröße eines Videos (in Anzeige-Ausrichtung): kürzere Kante höchstens [maxShortEdge],
 * Seitenverhältnis bleibt, Kanten gerade (Voraussetzung vieler Encoder). Vergrößert nie.
 */
fun targetVideoSize(width: Int, height: Int, maxShortEdge: Int?): Pair<Int, Int> {
    val short = minOf(width, height)
    if (maxShortEdge == null || short <= maxShortEdge) return width to height
    val factor = maxShortEdge.toDouble() / short
    fun even(v: Double) = (Math.round(v / 2) * 2).toInt().coerceAtLeast(2)
    return even(width * factor) to even(height * factor)
}

/** Datum aus MediaMetadataRetriever ("20240501T123456.000Z") in Millisekunden seit 1970. */
fun parseVideoDate(value: String?): Long? {
    val m = value?.let { Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})""").find(it) } ?: return null
    val (y, mo, d, h, mi, s) = m.destructured
    // Unbekanntes Datum wird von Android als 1904/1970 gemeldet – nicht übernehmen
    if (y.toInt() < 1980) return null
    return runCatching {
        java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()
}

/** Ortsangabe nach ISO 6709 ("+52.5200+013.4050/") in Breite/Länge. */
fun parseIso6709(value: String?): Pair<Float, Float>? {
    val m = value?.let { Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(it) } ?: return null
    val lat = m.groupValues[1].toFloatOrNull() ?: return null
    val lon = m.groupValues[2].toFloatOrNull() ?: return null
    return if (lat in -90f..90f && lon in -180f..180f) lat to lon else null
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
