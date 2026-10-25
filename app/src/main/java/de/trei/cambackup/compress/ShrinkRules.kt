package de.trei.cambackup.compress

import java.time.ZoneId
import java.time.Instant
import androidx.annotation.StringRes
import de.trei.cambackup.R
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.util.L10n
import de.trei.cambackup.settings.CompressionSettings

/** Gründe, ein Bild nicht zu verkleinern; die Datei bleibt dann unverändert (gesichert ist sie trotzdem). */
enum class SkipReason(@StringRes private val textRes: Int) {
    UNSUPPORTED_FORMAT(R.string.skip_format),
    HDR_VIDEO(R.string.skip_hdr),
    TOO_RECENT(R.string.skip_too_recent),
    ALREADY_COMPRESSED(R.string.skip_already_compressed),
    TOO_LITTLE_SAVING(R.string.skip_too_little_saving);

    /** Grund in der App-Sprache. */
    val text: String get() = L10n.str(textRes)
}

/** Kennzeichnung im EXIF-Feld „Software“ verkleinerter Bilder. */
const val COMPRESSED_MARKER = "CamBackup"

/**
 * Grenze für „älter als [minAgeDays] Tage“ in ganzen Kalendertagen: alles, was vor Beginn des Tages
 * vor [minAgeDays] Tagen aufgenommen wurde. So wandert die Grenze nicht minütlich mit (sonst kämen
 * mitten in einer Sitzung neue Dateien hinzu); 0 heißt: ohne Altersgrenze.
 */
fun ageCutoffMillis(minAgeDays: Int, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    if (minAgeDays <= 0) Long.MAX_VALUE
    else Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().minusDays(minAgeDays.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

/** Alt genug zum Verkleinern? */
fun oldEnough(dateTakenMillis: Long, minAgeDays: Int, nowMillis: Long): Boolean = dateTakenMillis < ageCutoffMillis(minAgeDays, nowMillis)

/** Nur JPEG-Fotos und MP4-Videos (mit bekannter Dauer) werden verkleinert, alles andere bleibt im Original. */
fun compressibleFormat(item: MediaItem): Boolean =
    if (item.isVideo) item.mimeType.lowercase() == "video/mp4" && item.durationMs > 0 else item.mimeType.lowercase() == "image/jpeg"

/** Prüfungen, die ohne Lesen der Datei möglich sind. */
fun precheckImage(item: MediaItem, settings: CompressionSettings, nowMillis: Long): SkipReason? = when {
    !compressibleFormat(item) -> SkipReason.UNSUPPORTED_FORMAT
    !oldEnough(item.dateTakenMillis, settings.minAgeDays, nowMillis) -> SkipReason.TOO_RECENT
    else -> null
}

/**
 * Prüfungen für Videos vor dem (langsamen) Neu-Kodieren. Liegt die Bitrate schon unter dem Ziel,
 * würde das Video nicht kleiner – dann gar nicht erst kodieren.
 */
fun precheckVideo(item: MediaItem, settings: CompressionSettings, nowMillis: Long): SkipReason? = when {
    !compressibleFormat(item) -> SkipReason.UNSUPPORTED_FORMAT
    !oldEnough(item.dateTakenMillis, settings.minAgeDays, nowMillis) -> SkipReason.TOO_RECENT
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
 * EXIF-Felder, die nicht übernommen werden: Bildaufbau, Vorschaubild und Ausrichtung beschreiben
 * die neue Datei nicht mehr (die Pixel sind bereits gedreht), MakerNote/XMP enthalten
 * herstellerspezifische Offsets bzw. Motion-Photo-Verweise (die verkleinerte Datei ist ein normales
 * Foto; die Bewegung bleibt nur im Original auf dem Medium).
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

/**
 * Kennung der Einstellungen, die bestimmen, wie klein eine Datei wird. Lohnte sich das Verkleinern
 * damit nicht, wird das im Index vermerkt und erst bei geänderten Einstellungen erneut versucht.
 */
fun shrinkSettingsKey(s: CompressionSettings, isVideo: Boolean): String =
    if (isVideo) {
        "video ${s.videoCodec.name.lowercase()} ${s.videoBitrateMbps}M ${s.videoMaxShortEdge ?: "orig"}" +
            (if (s.videoLimit30Fps) " 30fps" else "") + " min${s.minSavingsPercent}"
    } else {
        "jpeg q${s.jpegQuality} max${s.imageMaxEdge ?: "orig"} min${s.minSavingsPercent}"
    }

/** Gründe, die sich bei gleichen Einstellungen nicht ändern – solche Dateien nicht jedes Mal neu versuchen. */
val NOT_WORTH_REASONS = setOf(SkipReason.TOO_LITTLE_SAVING, SkipReason.HDR_VIDEO)
