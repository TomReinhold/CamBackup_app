package de.trei.cambackup.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Zwischenspeicher für Vorschaubilder (in KB bemessen, ca. 1/8 des App-Speichers). */
private object ThumbnailCache : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
}

/**
 * Vorschaubild eines MediaStore-Eintrags in [sizePx] (über ContentResolver.loadThumbnail – nutzt
 * die vom System bereits erzeugten Vorschauen und ist dadurch schnell).
 */
@Composable
fun MediaThumbnail(
    uri: Uri,
    sizePx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val context = LocalContext.current
    val key = "$uri@$sizePx"
    val bitmap by produceState(ThumbnailCache.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null) }.getOrNull()
                    ?: decodeDirectly(context, uri, sizePx)
            }?.asImageBitmap()?.also { ThumbnailCache.put(key, it) }
        }
    }
    Box(modifier.background(placeholder)) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale) }
    }
}

/**
 * Vorschau direkt aus der Datei, wenn das System keine liefert – etwa für Dateien auf dem
 * USB-Stick: dessen Anbieter erzeugt Vorschauen nur für Fotos, nicht für Videos.
 */
private fun decodeDirectly(context: Context, uri: Uri, sizePx: Int): Bitmap? {
    val type = context.contentResolver.getType(uri).orEmpty()
    if (!type.startsWith("image/")) {
        val frame = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx)
            }
        }.getOrNull()
        if (frame != null || type.startsWith("video/")) return frame
    }
    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = sizePx.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()
}
