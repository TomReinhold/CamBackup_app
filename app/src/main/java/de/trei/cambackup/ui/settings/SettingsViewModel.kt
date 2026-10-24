package de.trei.cambackup.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.trei.cambackup.compress.ImageCompressor
import de.trei.cambackup.compress.SavingsEstimate
import de.trei.cambackup.compress.SizeSample
import de.trei.cambackup.compress.estimateSavings
import de.trei.cambackup.compress.spreadSample
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.media.MediaScanner
import de.trei.cambackup.settings.CompressionSettings
import de.trei.cambackup.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** Vorschau-Bilder: jeweils Original und verkleinerte Version, als Gesamtbild und als 100-%-Ausschnitt. */
data class PreviewImages(
    val name: String,
    val originalFull: ImageBitmap,
    val compressedFull: ImageBitmap,
    val originalDetail: ImageBitmap,
    val compressedDetail: ImageBitmap,
    val originalBytes: Long,
    val compressedBytes: Long,
    val originalSize: Pair<Int, Int>,
    val compressedSize: Pair<Int, Int>,
)

sealed interface PreviewState {
    data object Loading : PreviewState
    data object NoImages : PreviewState
    data class Ready(val images: PreviewImages, val index: Int, val count: Int) : PreviewState
    data class Error(val message: String) : PreviewState
}

sealed interface EstimateState {
    data object Loading : EstimateState
    /** [previous]: letzte Schätzung, wird während der Neuberechnung weiter angezeigt. */
    data class Calculating(val previous: SavingsEstimate?) : EstimateState
    data class Ready(val estimate: SavingsEstimate, val sampleCount: Int) : EstimateState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)
    private val compressor = ImageCompressor(app.contentResolver)
    private val scanner = MediaScanner(app.contentResolver)

    val settings: StateFlow<CompressionSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, CompressionSettings())

    private val items = MutableStateFlow<List<MediaItem>?>(null)
    private val images = items.filterNotNull().map { list -> list.filter { !it.isVideo } }
    private val previewIndex = MutableStateFlow(0)

    /** Originale der Vorschau: hängen nur vom Bild ab, nicht von den Einstellungen. */
    private val originalCache = HashMap<Uri, OriginalPreview>()
    /** Probe-Kompressionen für die Schätzung, je Bild und Einstellung. */
    private val sampleCache = HashMap<Triple<Uri, Int?, Int>, SizeSample>()
    /** Letzte fertige Schätzung – bleibt während einer Neuberechnung sichtbar. */
    private var estimateCache: EstimateState? = null

    init {
        viewModelScope.launch {
            items.value = runCatching { scanner.scanCameraFolder() }.getOrDefault(emptyList())
        }
    }

    val preview: StateFlow<PreviewState> = combine(
        images,
        previewIndex,
        settings.map { it.imageMaxEdge to it.jpegQuality }.distinctUntilChanged(),
    ) { imgs, index, params -> Triple(imgs, index, params) }
        .debounce(150)
        .transformLatest { (imgs, index, params) ->
            if (imgs.isEmpty()) {
                emit(PreviewState.NoImages)
                return@transformLatest
            }
            val i = index.coerceIn(0, imgs.lastIndex)
            emit(
                try {
                    PreviewState.Ready(buildPreview(imgs[i], params.first, params.second), i, imgs.size)
                } catch (e: Exception) {
                    PreviewState.Error(e.message ?: e.javaClass.simpleName)
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PreviewState.Loading)

    val estimate: StateFlow<EstimateState> = combine(items.filterNotNull(), settings) { list, s -> list to s }
        .debounce(300)
        .transformLatest { (list, s) ->
            val previous = (estimateCache as? EstimateState.Ready)?.estimate
            emit(EstimateState.Calculating(previous))
            val samples = spreadSample(list.filter { !it.isVideo }, SAMPLE_COUNT).mapNotNull { item ->
                val key = Triple(item.uri, s.imageMaxEdge, s.jpegQuality)
                sampleCache[key] ?: runCatching {
                    SizeSample(item.sizeBytes, compressor.compress(item.uri, s.imageMaxEdge, s.jpegQuality).bytes.size.toLong())
                }.getOrNull()?.also { sampleCache[key] = it }
            }
            val ready = EstimateState.Ready(estimateSavings(list, s, samples, System.currentTimeMillis()), samples.size)
            estimateCache = ready
            emit(ready)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EstimateState.Loading)

    fun update(transform: (CompressionSettings) -> CompressionSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun reset() {
        viewModelScope.launch { repo.reset() }
    }

    fun showPreview(delta: Int) {
        val count = (preview.value as? PreviewState.Ready)?.count ?: return
        previewIndex.value = (previewIndex.value + delta).mod(count)
    }

    private class OriginalPreview(
        val full: ImageBitmap,
        val detail: ImageBitmap,
        /** Ausschnitt relativ zur Bildgröße (0..1), damit er im verkleinerten Bild wiedergefunden wird. */
        val detailLeft: Float,
        val detailTop: Float,
        val detailWidth: Float,
        val detailHeight: Float,
    )

    private suspend fun buildPreview(item: MediaItem, maxEdge: Int?, quality: Int): PreviewImages {
        val original = originalCache[item.uri] ?: loadOriginal(item).also {
            if (originalCache.size >= 4) originalCache.clear()
            originalCache[item.uri] = it
        }
        val compressed = compressor.compress(item.uri, maxEdge, quality)
        // Kein recycle(): createScaledBitmap/createBitmap können das Ausgangsbild selbst zurückgeben
        val (full, detail) = withContext(Dispatchers.Default) {
            val bmp = compressor.decodeBytes(compressed.bytes)
            val full = Bitmap.createScaledBitmap(bmp, original.full.width, original.full.height, true)
            val crop = Rect(
                (original.detailLeft * bmp.width).roundToInt(),
                (original.detailTop * bmp.height).roundToInt(),
                ((original.detailLeft + original.detailWidth) * bmp.width).roundToInt().coerceAtMost(bmp.width),
                ((original.detailTop + original.detailHeight) * bmp.height).roundToInt().coerceAtMost(bmp.height),
            )
            val cropped = Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width().coerceAtLeast(1), crop.height().coerceAtLeast(1))
            // Auf die Größe des Original-Ausschnitts bringen – so sieht man den Detailverlust direkt
            val detail = Bitmap.createScaledBitmap(cropped, original.detail.width, original.detail.height, true)
            full.asImageBitmap() to detail.asImageBitmap()
        }
        return PreviewImages(
            name = item.displayName,
            originalFull = original.full,
            compressedFull = full,
            originalDetail = original.detail,
            compressedDetail = detail,
            originalBytes = item.sizeBytes,
            compressedBytes = compressed.bytes.size.toLong(),
            originalSize = compressed.originalWidth to compressed.originalHeight,
            compressedSize = compressed.width to compressed.height,
        )
    }

    private suspend fun loadOriginal(item: MediaItem): OriginalPreview {
        val bmp = compressor.decodeOriginal(item.uri)
        return withContext(Dispatchers.Default) {
            val scale = min(1f, DISPLAY_EDGE.toFloat() / maxOf(bmp.width, bmp.height))
            val full = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt(), true)
            // Quadratischer Ausschnitt aus der Bildmitte in voller Auflösung
            val side = min(DETAIL_SIZE, min(bmp.width, bmp.height))
            val left = (bmp.width - side) / 2
            val top = (bmp.height - side) / 2
            val detail = Bitmap.createBitmap(bmp, left, top, side, side)
            OriginalPreview(
                full = full.asImageBitmap(),
                detail = detail.asImageBitmap(),
                detailLeft = left.toFloat() / bmp.width,
                detailTop = top.toFloat() / bmp.height,
                detailWidth = side.toFloat() / bmp.width,
                detailHeight = side.toFloat() / bmp.height,
            )
        }
    }

    private companion object {
        const val DISPLAY_EDGE = 1200
        const val DETAIL_SIZE = 600
        const val SAMPLE_COUNT = 5
    }
}
