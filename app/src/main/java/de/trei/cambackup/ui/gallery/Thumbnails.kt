package de.trei.cambackup.ui.gallery

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
                runCatching {
                    context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null).asImageBitmap()
                }.getOrNull()
            }?.also { ThumbnailCache.put(key, it) }
        }
    }
    Box(modifier.background(placeholder)) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale) }
    }
}
