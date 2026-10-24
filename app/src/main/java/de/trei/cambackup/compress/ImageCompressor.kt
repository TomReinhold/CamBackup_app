package de.trei.cambackup.compress

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

data class CompressedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val originalWidth: Int,
    val originalHeight: Int,
)

/**
 * Verkleinert Bilder: dekodieren (Ausrichtung laut EXIF wird dabei angewendet), auf die
 * Ziel-Kantenlänge skalieren und als JPEG neu kodieren. Dieselbe Funktion dient der Vorschau
 * und dem späteren Ersetzen – die angezeigte Größe entspricht also dem echten Ergebnis.
 */
class ImageCompressor(private val resolver: ContentResolver) {

    suspend fun compress(uri: Uri, maxEdge: Int?, quality: Int): CompressedImage = withContext(Dispatchers.Default) {
        var origW = 0
        var origH = 0
        val bitmap = decode(uri) { w, h ->
            origW = w
            origH = h
            scaledSize(w, h, maxEdge)
        }
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            CompressedImage(out.toByteArray(), bitmap.width, bitmap.height, origW, origH)
        } finally {
            bitmap.recycle()
        }
    }

    /** Dekodiert das Original für die Vorschau; sehr große Bilder werden auf [maxPixels] begrenzt. */
    suspend fun decodeOriginal(uri: Uri, maxPixels: Long = 24_000_000): Bitmap = withContext(Dispatchers.Default) {
        decode(uri) { w, h ->
            val pixels = w.toLong() * h
            if (pixels <= maxPixels) {
                w to h
            } else {
                val f = sqrt(maxPixels.toDouble() / pixels)
                (w * f).toInt() to (h * f).toInt()
            }
        }
    }

    fun decodeBytes(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("JPEG nicht dekodierbar")

    private fun decode(uri: Uri, targetSize: (Int, Int) -> Pair<Int, Int>): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = targetSize(info.size.width, info.size.height)
            if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
            // Software-Bitmap: nötig für compress() und für Ausschnitte in der Vorschau
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
}
