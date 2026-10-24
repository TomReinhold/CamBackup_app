package de.trei.cambackup.compress

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import de.trei.cambackup.backup.sha256
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.media.readUri
import de.trei.cambackup.settings.CompressionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import de.trei.cambackup.util.toMsg
import de.trei.cambackup.util.msg
import de.trei.cambackup.util.L10n
import de.trei.cambackup.util.AppError
import de.trei.cambackup.R

sealed interface ShrinkResult {
    /** Verkleinerte Version liegt fertig (mit EXIF) in [file] im Cache der App. */
    data class Ready(
        val file: File,
        val originalBytes: Long,
        val newBytes: Long,
        val sha256: String,
        /** Kurzbeschreibung der Parameter für den Index, z. B. "jpeg q85 max3072". */
        val params: String,
    ) : ShrinkResult

    data class Skipped(val reason: SkipReason) : ShrinkResult
}

/**
 * Erzeugt die verkleinerte Version eines Fotos im Cache. Das Original wird hier nie verändert –
 * das Ersetzen auf dem Smartphone geschieht erst nach erfolgreicher Sicherung (siehe Sicherheitsregel).
 */
class ImageShrinker(private val context: Context) {

    private val resolver = context.contentResolver
    private val compressor = ImageCompressor(resolver)
    private val outDir = File(context.cacheDir, "shrunk")

    suspend fun shrink(item: MediaItem, settings: CompressionSettings, nowMillis: Long = System.currentTimeMillis()): ShrinkResult {
        precheckImage(item, settings, nowMillis)?.let { return ShrinkResult.Skipped(it) }

        val source = withContext(Dispatchers.IO) { readExif(item) }
        if (isMarkedCompressed(source.getAttribute(ExifInterface.TAG_SOFTWARE))) {
            return ShrinkResult.Skipped(SkipReason.ALREADY_COMPRESSED)
        }
        val tail = withContext(Dispatchers.IO) { readTail(item) }
        if (looksLikeMotionPhoto(item.displayName, source.getAttribute(ExifInterface.TAG_XMP), tail)) {
            return ShrinkResult.Skipped(SkipReason.MOTION_PHOTO)
        }

        val compressed = compressor.compress(item.readUri, settings.imageMaxEdge, settings.jpegQuality)
        // Grobe Vorprüfung; EXIF fügt nur wenige KB hinzu, die genaue Prüfung folgt unten
        if (!worthReplacing(item.sizeBytes, compressed.bytes.size.toLong(), settings.minSavingsPercent)) {
            return ShrinkResult.Skipped(SkipReason.TOO_LITTLE_SAVING)
        }

        return withContext(Dispatchers.IO) {
            outDir.mkdirs()
            val file = File(outDir, "${item.id}.jpg")
            file.writeBytes(compressed.bytes)
            writeExif(source, file, compressed, settings.keepGps)

            val size = file.length()
            if (!worthReplacing(item.sizeBytes, size, settings.minSavingsPercent)) {
                file.delete()
                return@withContext ShrinkResult.Skipped(SkipReason.TOO_LITTLE_SAVING)
            }
            val hash = file.inputStream().use { sha256(it) }
            ShrinkResult.Ready(
                file = file,
                originalBytes = item.sizeBytes,
                newBytes = size,
                sha256 = hash.sha256,
                params = "jpeg q${settings.jpegQuality} max${settings.imageMaxEdge ?: "orig"}" +
                    if (settings.keepGps) "" else " nogps",
            )
        }
    }

    /** Entfernt alle zwischengespeicherten verkleinerten Bilder. */
    fun clearCache() {
        outDir.deleteRecursively()
    }

    private fun readExif(item: MediaItem): ExifInterface =
        resolver.openInputStream(item.readUri)?.use { ExifInterface(it) } ?: throw AppError(R.string.err_file_unreadable, item.displayName)

    /** Letzte 64 KB der Datei – dort hängen Samsung-Motion-Photos ihr Video an. */
    private fun readTail(item: MediaItem): ByteArray? = runCatching {
        resolver.openInputStream(item.readUri)?.use { input ->
            val skip = (item.sizeBytes - TAIL_BYTES).coerceAtLeast(0)
            var skipped = 0L
            while (skipped < skip) {
                val n = input.skip(skip - skipped)
                if (n <= 0) break
                skipped += n
            }
            input.readBytes()
        }
    }.getOrNull()

    private fun writeExif(source: ExifInterface, file: File, image: CompressedImage, keepGps: Boolean) {
        val target = ExifInterface(file)
        for (tag in tagsToCopy(ALL_TAGS, keepGps)) {
            val value = source.getAttribute(tag) ?: continue
            // Einzelne exotische Felder dürfen das Übertragen der übrigen nicht verhindern
            runCatching { target.setAttribute(tag, value) }
        }
        // Die Pixel sind bereits aufrecht gedreht
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, image.width.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, image.height.toString())
        target.setAttribute(ExifInterface.TAG_SOFTWARE, markSoftware(source.getAttribute(ExifInterface.TAG_SOFTWARE)))
        target.saveAttributes()
    }

    private companion object {
        const val TAIL_BYTES = 64 * 1024L

        /** Alle EXIF-Feldnamen, die ExifInterface kennt (öffentliche Konstanten TAG_…). */
        val ALL_TAGS: List<String> by lazy {
            ExifInterface::class.java.fields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name.startsWith("TAG_") }
                .mapNotNull { it.get(null) as? String }
                .distinct()
        }
    }
}
