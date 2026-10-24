package de.trei.cambackup

import android.net.Uri
import de.trei.cambackup.compress.SizeSample
import de.trei.cambackup.compress.estimateSavings
import de.trei.cambackup.compress.estimatedVideoBytes
import de.trei.cambackup.compress.scaledSize
import de.trei.cambackup.compress.spreadSample
import de.trei.cambackup.compress.worthReplacing
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.CompressionSettings
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavingsEstimatorTest {

    private val uri = mockk<Uri>()
    private val day = 24L * 60 * 60 * 1000
    private val now = 1000 * day

    private fun image(size: Long, ageDays: Int) =
        MediaItem(size, uri, "i$size", "DCIM/Camera/", "image/jpeg", size, now - ageDays * day, isVideo = false)

    private fun video(size: Long, durationMs: Long, ageDays: Int = 100) =
        MediaItem(size, uri, "v$size", "DCIM/Camera/", "video/mp4", size, now - ageDays * day, isVideo = true, durationMs = durationMs)

    @Test fun scaledSizeKeepsAspectAndNeverUpscales() {
        assertEquals(3072 to 2304, scaledSize(4000, 3000, 3072))
        assertEquals(2304 to 3072, scaledSize(3000, 4000, 3072))
        assertEquals(1000 to 800, scaledSize(1000, 800, 3072))
        assertEquals(4000 to 3000, scaledSize(4000, 3000, null))
    }

    @Test fun worthReplacingRespectsThreshold() {
        assertTrue(worthReplacing(100, 80, 20))
        assertFalse(worthReplacing(100, 81, 20))
        assertFalse(worthReplacing(0, 0, 20))
    }

    @Test fun videoBytesFromBitrate() {
        // 6 Mbit/s + 128 kbit/s = 766 000 Byte/s; 10 s
        assertEquals(7_660_000, estimatedVideoBytes(10_000, CompressionSettings(videoBitrateMbps = 6)))
    }

    @Test fun imagesUseAverageSampleRatio() {
        val e = estimateSavings(
            listOf(image(1000, 60), image(3000, 60)),
            CompressionSettings(),
            listOf(SizeSample(100, 20), SizeSample(100, 40)), // 0,2 und 0,4 → 0,3
            now,
        )
        assertEquals(4000, e.imagesBefore)
        assertEquals(1200, e.imagesAfter)
        assertEquals(2800, e.saved)
    }

    @Test fun samplesBelowMinSavingsCountAsUnchanged() {
        val e = estimateSavings(
            listOf(image(1000, 60)),
            CompressionSettings(minSavingsPercent = 20),
            listOf(SizeSample(100, 90), SizeSample(100, 50)), // 1,0 (zu wenig gespart) und 0,5 → 0,75
            now,
        )
        assertEquals(750, e.imagesAfter)
    }

    @Test fun recentFilesAreExcluded() {
        val e = estimateSavings(listOf(image(1000, 5), image(2000, 60)), CompressionSettings(minAgeDays = 30), listOf(SizeSample(10, 5)), now)
        assertEquals(1, e.imageCount)
        assertEquals(1, e.tooRecentCount)
        assertEquals(1000, e.tooRecentBytes)
    }

    @Test fun videosBelowTargetBitrateStayUnchanged() {
        val s = CompressionSettings(videoBitrateMbps = 6)
        val big = video(50_000_000, 10_000) // 40 Mbit/s → wird kleiner
        val small = video(5_000_000, 10_000) // schon unter Ziel-Bitrate
        val e = estimateSavings(listOf(big, small), s, emptyList(), now)
        assertEquals(55_000_000, e.videosBefore)
        assertEquals(7_660_000 + 5_000_000, e.videosAfter)
    }

    @Test fun noSamplesMeansNoImageSavings() {
        val e = estimateSavings(listOf(image(1000, 60)), CompressionSettings(), emptyList(), now)
        assertEquals(1000, e.imagesAfter)
    }

    @Test fun spreadSampleCoversRange() {
        assertEquals(listOf(0, 3, 6, 9), spreadSample((0..9).toList(), 4))
        assertEquals(listOf(1, 2), spreadSample(listOf(1, 2), 5))
    }
}
