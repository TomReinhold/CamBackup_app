package de.trei.cambackup.compress

import android.content.Context
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import de.trei.cambackup.backup.sha256
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.CompressionSettings
import de.trei.cambackup.settings.VideoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** Eckdaten eines Videos, gelesen mit MediaMetadataRetriever. */
data class VideoInfo(
    val width: Int,
    val height: Int,
    /** Drehung laut Metadaten (0/90/180/270). */
    val rotation: Int,
    val durationMs: Long,
    val hasAudio: Boolean,
    val isHdr: Boolean,
    /** Aufnahmedatum laut Container, z. B. "20240501T123456.000Z". */
    val date: String?,
    /** ISO-6709-Ortsangabe, z. B. "+52.5200+013.4050/". */
    val location: String?,
) {
    val displayWidth: Int get() = if (rotation % 180 == 0) width else height
    val displayHeight: Int get() = if (rotation % 180 == 0) height else width
}

/**
 * Kodiert Videos mit Media3 Transformer neu (Auflösung, Codec, Bitrate, optional 30 fps).
 * Die Tonspur wird unverändert übernommen. Das Ergebnis landet im Cache und wird geprüft
 * (Dauer, Tonspur, Größe), bevor es als Ersatz in Frage kommt.
 */
@OptIn(UnstableApi::class)
class VideoShrinker(private val context: Context) {

    private val outDir = File(context.cacheDir, "shrunk")

    suspend fun shrink(
        item: MediaItem,
        settings: CompressionSettings,
        nowMillis: Long = System.currentTimeMillis(),
        onProgress: (Int) -> Unit = {},
    ): ShrinkResult {
        precheckVideo(item, settings, nowMillis)?.let { return ShrinkResult.Skipped(it) }

        val info = withContext(Dispatchers.IO) { readInfo(item.uri) }
        if (info.isHdr && settings.videoCodec != VideoCodec.HEVC) return ShrinkResult.Skipped(SkipReason.HDR_VIDEO)

        outDir.mkdirs()
        val out = File(outDir, "${item.id}.mp4")
        out.delete()
        val (w, h) = targetVideoSize(info.displayWidth, info.displayHeight, settings.videoMaxShortEdge)
        export(item.uri, out, info, w, h, settings, onProgress)

        return withContext(Dispatchers.IO) {
            val result = readInfo(Uri.fromFile(out))
            // Unvollständige oder stillschweigend verschlechterte Ausgabe darf niemals ein Original ersetzen.
            // (Kann ein Encoder die Auflösung nicht, weicht Transformer sonst unbemerkt auf eine kleinere aus.)
            val problem = when {
                abs(result.durationMs - info.durationMs) > DURATION_TOLERANCE_MS ->
                    "Dauer weicht ab (${result.durationMs} statt ${info.durationMs} ms)"
                info.hasAudio && !result.hasAudio -> "Tonspur fehlt"
                abs(result.displayWidth - w) > 2 || abs(result.displayHeight - h) > 2 ->
                    "Auflösung ${result.displayWidth}×${result.displayHeight} statt $w×$h (Encoder unterstützt sie nicht)"
                else -> null
            }
            if (problem != null) {
                out.delete()
                throw IOException("Video-Kodierung fehlerhaft: $problem")
            }
            if (!worthReplacing(item.sizeBytes, out.length(), settings.minSavingsPercent)) {
                out.delete()
                return@withContext ShrinkResult.Skipped(SkipReason.TOO_LITTLE_SAVING)
            }
            ShrinkResult.Ready(
                file = out,
                originalBytes = item.sizeBytes,
                newBytes = out.length(),
                sha256 = out.inputStream().use { sha256(it) }.sha256,
                params = "${settings.videoCodec.name.lowercase()} ${settings.videoBitrateMbps}M " +
                    (settings.videoMaxShortEdge?.let { "${it}p" } ?: "orig") +
                    if (settings.videoLimit30Fps) " 30fps" else "",
            )
        }
    }

    fun readInfo(uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(context, uri)
            fun int(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
            val transfer = int(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)
            return VideoInfo(
                width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                isHdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG,
                date = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                location = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION),
            )
        } finally {
            r.release()
        }
    }

    /** Führt den Export aus; Transformer verlangt einen Looper-Thread, daher auf Main. */
    private suspend fun export(
        source: Uri,
        out: File,
        info: VideoInfo,
        w: Int,
        h: Int,
        settings: CompressionSettings,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val effects = buildList<Effect> {
            if (w != info.displayWidth || h != info.displayHeight) {
                add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
            }
            if (settings.videoLimit30Fps) add(FrameDropEffect.createDefaultFrameDropEffect(30f))
        }
        val edited = EditedMediaItem.Builder(Media3Item.fromUri(source))
            .setEffects(Effects(emptyList(), effects))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(settings.videoBitrateMbps * 1_000_000).build(),
            )
            .setEnableFallback(true)
            .build()

        coroutineScope {
            lateinit var transformer: Transformer
            val progress = launch {
                val holder = ProgressHolder()
                while (true) {
                    delay(500)
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    transformer = Transformer.Builder(context)
                        .setVideoMimeType(if (settings.videoCodec == VideoCodec.HEVC) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                        .setEncoderFactory(encoderFactory)
                        .setMuxerFactory(muxerWithMetadata(info))
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                cont.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                cont.resumeWithException(IOException("Video-Kodierung fehlgeschlagen: ${exportException.message}", exportException))
                            }
                        })
                        .build()
                    cont.invokeOnCancellation { transformer.cancel() }
                    transformer.start(edited, out.path)
                }
            } finally {
                progress.cancel()
            }
        }
    }

    /**
     * Transformer schreibt sonst das aktuelle Datum in die Datei – die Galerie würde das Video
     * dann unter „heute“ einsortieren. Aufnahmedatum und Ort des Originals werden daher übernommen.
     */
    private fun muxerWithMetadata(info: VideoInfo): InAppMuxer.Factory {
        val date = parseVideoDate(info.date)
        val location = parseIso6709(info.location)
        return InAppMuxer.Factory.Builder()
            .setMetadataProvider { entries ->
                if (date != null) {
                    entries.removeAll { it is Mp4TimestampData }
                    val seconds = Mp4TimestampData.unixTimeToMp4TimeSeconds(date)
                    entries.add(Mp4TimestampData(seconds, seconds))
                }
                if (location != null) {
                    entries.removeAll { it is Mp4LocationData }
                    entries.add(Mp4LocationData(location.first, location.second))
                }
            }
            .build()
    }

    private companion object {
        /** Erlaubte Abweichung der Dauer (Rundung, Frame-Raster beim Wechsel der Bildrate). */
        const val DURATION_TOLERANCE_MS = 300L
    }
}
