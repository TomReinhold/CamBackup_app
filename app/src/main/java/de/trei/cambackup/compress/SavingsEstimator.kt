package de.trei.cambackup.compress

import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.CompressionSettings
import kotlin.math.roundToInt

/** Abmessungen nach dem Verkleinern; skaliert gleichmäßig, vergrößert nie. */
fun scaledSize(width: Int, height: Int, maxEdge: Int?): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (maxEdge == null || longest <= maxEdge) return width to height
    val factor = maxEdge.toDouble() / longest
    return maxOf(1, (width * factor).roundToInt()) to maxOf(1, (height * factor).roundToInt())
}

/** Ergebnis einer Probe-Kompression: Originalgröße und Größe danach in Bytes. */
data class SizeSample(val originalBytes: Long, val compressedBytes: Long)

data class SavingsEstimate(
    val imageCount: Int,
    val imagesBefore: Long,
    val imagesAfter: Long,
    val videoCount: Int,
    val videosBefore: Long,
    val videosAfter: Long,
    /** Dateien, die wegen Mindestalter (noch) nicht verkleinert würden. */
    val tooRecentCount: Int,
    val tooRecentBytes: Long,
) {
    val before: Long get() = imagesBefore + videosBefore
    val after: Long get() = imagesAfter + videosAfter
    val saved: Long get() = before - after
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Würde eine Datei bei diesen Größen ersetzt? Nur wenn die Mindest-Ersparnis erreicht wird. */
fun worthReplacing(originalBytes: Long, compressedBytes: Long, minSavingsPercent: Int): Boolean =
    originalBytes > 0 && compressedBytes <= originalBytes * (100 - minSavingsPercent) / 100

/** Erwartete Videogröße aus Dauer und Ziel-Bitrate (Video + Audio). */
fun estimatedVideoBytes(durationMs: Long, settings: CompressionSettings): Long {
    val bitsPerSecond = settings.videoBitrateMbps * 1_000_000L + CompressionSettings.AUDIO_BITRATE_KBPS * 1000L
    return bitsPerSecond / 8 * durationMs / 1000
}

/**
 * Schätzt den Speicherbedarf nach dem Verkleinern.
 * - Bilder: mittleres Verhältnis aus [imageSamples] (echte Probe-Kompressionen), auf alle Bilder hochgerechnet
 * - Videos: Dauer × Ziel-Bitrate
 * Dateien unterhalb der Mindest-Ersparnis bleiben unverändert, ebenso Dateien jünger als das Mindestalter.
 */
fun estimateSavings(
    items: List<MediaItem>,
    settings: CompressionSettings,
    imageSamples: List<SizeSample>,
    nowMillis: Long,
): SavingsEstimate {
    val cutoff = nowMillis - settings.minAgeDays * DAY_MS
    val (eligible, tooRecent) = items.partition { it.dateTakenMillis <= cutoff }
    val (videos, images) = eligible.partition { it.isVideo }

    // Pro Probe entscheiden, ob ersetzt würde – sonst zählt sie mit Verhältnis 1
    val imageRatio = imageSamples
        .filter { it.originalBytes > 0 }
        .map { s ->
            if (worthReplacing(s.originalBytes, s.compressedBytes, settings.minSavingsPercent)) {
                s.compressedBytes.toDouble() / s.originalBytes
            } else {
                1.0
            }
        }
        .takeIf { it.isNotEmpty() }
        ?.average() ?: 1.0

    val imagesBefore = images.sumOf { it.sizeBytes }
    val videosBefore = videos.sumOf { it.sizeBytes }
    val videosAfter = videos.sumOf { v ->
        val target = if (v.durationMs > 0) estimatedVideoBytes(v.durationMs, settings) else v.sizeBytes
        if (worthReplacing(v.sizeBytes, target, settings.minSavingsPercent)) target else v.sizeBytes
    }

    return SavingsEstimate(
        imageCount = images.size,
        imagesBefore = imagesBefore,
        imagesAfter = (imagesBefore * imageRatio).toLong(),
        videoCount = videos.size,
        videosBefore = videosBefore,
        videosAfter = videosAfter,
        tooRecentCount = tooRecent.size,
        tooRecentBytes = tooRecent.sumOf { it.sizeBytes },
    )
}

/** Wählt [count] Bilder gleichmäßig verteilt aus (für repräsentative Probe-Kompressionen). */
fun <T> spreadSample(items: List<T>, count: Int): List<T> {
    if (items.size <= count) return items
    return (0 until count).map { i -> items[(i * (items.size - 1)) / (count - 1).coerceAtLeast(1)] }.distinct()
}
