package de.trei.cambackup

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.trei.cambackup.compress.ShrinkResult
import de.trei.cambackup.compress.SkipReason
import de.trei.cambackup.compress.VideoShrinker
import de.trei.cambackup.compress.parseIso6709
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.CompressionSettings
import de.trei.cambackup.settings.VideoCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Testvideo (androidTest/assets/test_video.mp4): 1280×720 H.264 ≈ 10 Mbit/s, 3 s, AAC-Ton,
 * Drehung 90° (also hochkant), Aufnahmedatum 2024-05-01 12:34:56 UTC, Ort Berlin.
 */
@RunWith(AndroidJUnit4::class)
class VideoShrinkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shrinker = VideoShrinker(context)
    private lateinit var video: File
    private val old = System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000

    @Before fun setUp() {
        video = File(context.cacheDir, "VID_TEST.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("test_video.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
    }

    @After fun tearDown() {
        video.delete()
        File(context.cacheDir, "shrunk").deleteRecursively()
    }

    private fun item() = MediaItem(
        id = 4711, uri = Uri.fromFile(video), displayName = video.name, relativePath = "DCIM/Camera/",
        mimeType = "video/mp4", sizeBytes = video.length(), dateTakenMillis = old, isVideo = true, durationMs = 3000,
    )

    private fun videoMime(file: File): String {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            return (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!! }.first { it.startsWith("video/") }
        } finally {
            ex.release()
        }
    }

    private fun shrinkWith(codec: VideoCodec): ShrinkResult.Ready = runBlocking {
        val settings = CompressionSettings(videoMaxShortEdge = 480, videoCodec = codec, videoBitrateMbps = 2, minSavingsPercent = 20)
        val result = shrinker.shrink(item(), settings)
        assertTrue("Ergebnis: $result", result is ShrinkResult.Ready)
        result as ShrinkResult.Ready
    }

    @Test fun h264ScalesKeepsAudioDurationAndOrientation() {
        val before = shrinker.readInfo(Uri.fromFile(video))
        val ready = shrinkWith(VideoCodec.H264)
        val after = shrinker.readInfo(Uri.fromFile(ready.file))

        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, videoMime(ready.file))
        // Hochkant bleibt hochkant, kürzere Kante 480
        assertEquals(480, after.displayWidth)
        assertTrue("Höhe ${after.displayHeight}", abs(after.displayHeight - 854) <= 2)
        assertTrue("Dauer ${after.durationMs}", abs(after.durationMs - before.durationMs) <= 300)
        assertTrue(after.hasAudio)
        assertTrue("kleiner: ${ready.newBytes} < ${ready.originalBytes}", ready.newBytes < ready.originalBytes * 0.8)

        println("Vorher: $before")
        println("Nachher: $after, ${ready.newBytes} Bytes, ${ready.params}")
    }

    private fun hevcEncoderSupports(w: Int, h: Int) = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes }
        .any { info ->
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities
            caps.isSizeSupported(w, h) || caps.isSizeSupported(h, w)
        }

    /**
     * Mit passendem Encoder entsteht HEVC in 480p. Kann der Encoder die Auflösung nicht (z. B. der
     * Software-Encoder des Emulators), muss die Kodierung mit klarer Meldung scheitern – statt
     * stillschweigend ein kleineres Video als Ersatz zu liefern.
     */
    @Test fun hevcProducesHevcOrRefusesDowngrade() = runBlocking {
        val settings = CompressionSettings(videoMaxShortEdge = 480, videoCodec = VideoCodec.HEVC, videoBitrateMbps = 2)
        val outcome = runCatching { shrinker.shrink(item(), settings) }
        if (hevcEncoderSupports(480, 854)) {
            val ready = outcome.getOrThrow() as ShrinkResult.Ready
            assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, videoMime(ready.file))
            assertEquals(480, shrinker.readInfo(Uri.fromFile(ready.file)).displayWidth)
        } else {
            val error = outcome.exceptionOrNull()
            assertTrue("Fehler erwartet, war: $outcome", error?.message?.contains("Auflösung") == true)
            assertFalse("keine Ausgabedatei", File(context.cacheDir, "shrunk/4711.mp4").exists())
            println("HEVC-Encoder ohne 480p-Unterstützung: ${error!!.message}")
        }
    }

    @Test fun keepsCaptureDate() {
        val before = shrinker.readInfo(Uri.fromFile(video))
        assertEquals("Voraussetzung", "20240501T123456.000Z", before.date)
        val after = shrinker.readInfo(Uri.fromFile(shrinkWith(VideoCodec.H264).file))
        assertEquals(before.date, after.date)
    }

    /**
     * Den Ort schreiben Kamera-Apps über Androids MediaMuxer. Das Testvideo wird daher zuerst
     * mit MediaMuxer samt Ortsangabe neu verpackt – so wie ein echtes Handyvideo.
     */
    @Test fun keepsLocationWrittenByMediaMuxer() {
        val withLocation = File(context.cacheDir, "VID_LOC.mp4")
        remuxWithLocation(video, withLocation, 52.52f, 13.405f)
        video.delete()
        withLocation.renameTo(video)

        val before = shrinker.readInfo(Uri.fromFile(video))
        assertTrue("Voraussetzung: Ort im Original, war ${before.location}", before.location != null)
        val after = shrinker.readInfo(Uri.fromFile(shrinkWith(VideoCodec.H264).file))
        // Gleicher Ort; die Schreibweise darf abweichen (Media3: "+13.4050" statt "+013.4050")
        val (latB, lonB) = parseIso6709(before.location)!!
        val (latA, lonA) = parseIso6709(after.location) ?: error("Ort fehlt: ${after.location}")
        assertEquals(latB, latA, 0.0001f)
        assertEquals(lonB, lonA, 0.0001f)
        assertEquals("Drehung", before.rotation, after.rotation)
        println("Ort vorher/nachher: ${before.location} / ${after.location}")
    }

    private fun remuxWithLocation(src: File, dst: File, lat: Float, lon: Float) {
        val extractor = MediaExtractor().apply { setDataSource(src.path) }
        val muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            muxer.setLocation(lat, lon)
            muxer.setOrientationHint(shrinker.readInfo(Uri.fromFile(src)).rotation)
            val trackMap = (0 until extractor.trackCount).associateWith { muxer.addTrack(extractor.getTrackFormat(it)) }
            muxer.start()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((srcTrack, dstTrack) in trackMap) {
                extractor.selectTrack(srcTrack)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    info.size = extractor.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    info.offset = 0
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(dstTrack, buffer, info)
                    extractor.advance()
                }
                extractor.unselectTrack(srcTrack)
            }
            muxer.stop()
        } finally {
            muxer.release()
            extractor.release()
        }
    }

    @Test fun lowBitrateVideoIsNotReencoded() = runBlocking {
        // Ziel 12 Mbit/s liegt über den ≈ 10 Mbit/s des Originals
        val result = shrinker.shrink(item(), CompressionSettings(videoBitrateMbps = 12))
        assertEquals(ShrinkResult.Skipped(SkipReason.TOO_LITTLE_SAVING), result)
    }
}
