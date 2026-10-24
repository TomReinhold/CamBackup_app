package de.trei.cambackup

import android.net.Uri
import de.trei.cambackup.compress.SkipReason
import de.trei.cambackup.compress.isMarkedCompressed
import de.trei.cambackup.compress.looksLikeMotionPhoto
import de.trei.cambackup.compress.markSoftware
import de.trei.cambackup.compress.parseIso6709
import de.trei.cambackup.compress.parseVideoDate
import de.trei.cambackup.compress.precheckImage
import de.trei.cambackup.compress.precheckVideo
import de.trei.cambackup.compress.tagsToCopy
import de.trei.cambackup.compress.targetVideoSize
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.CompressionSettings
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShrinkRulesTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1000 * day

    private fun item(mime: String = "image/jpeg", ageDays: Int = 60, name: String = "IMG_1.jpg") =
        MediaItem(1, mockk<Uri>(), name, "DCIM/Camera/", mime, 1000, now - ageDays * day, isVideo = false)

    @Test fun jpegOldEnoughPasses() = assertNull(precheckImage(item(), CompressionSettings(minAgeDays = 30), now))

    @Test fun heicIsUnsupported() =
        assertEquals(SkipReason.UNSUPPORTED_FORMAT, precheckImage(item(mime = "image/heic"), CompressionSettings(), now))

    @Test fun recentIsSkipped() =
        assertEquals(SkipReason.TOO_RECENT, precheckImage(item(ageDays = 10), CompressionSettings(minAgeDays = 30), now))

    @Test fun minAgeZeroAllowsToday() =
        assertNull(precheckImage(item(ageDays = 0), CompressionSettings(minAgeDays = 0), now))

    @Test fun marker() {
        assertEquals("CamBackup", markSoftware(null))
        assertEquals("HDR+ 1.0 (CamBackup)", markSoftware("HDR+ 1.0"))
        assertTrue(isMarkedCompressed(markSoftware("x")))
        assertFalse(isMarkedCompressed("HDR+ 1.0"))
        assertFalse(isMarkedCompressed(null))
    }

    @Test fun motionPhotos() {
        assertTrue(looksLikeMotionPhoto("PXL_20240101_120000000.MP.jpg", null, null))
        assertTrue(looksLikeMotionPhoto("MVIMG_20190101_1.jpg", null, null))
        assertTrue(looksLikeMotionPhoto("IMG.jpg", "<x GCamera:MotionPhoto=\"1\"/>", null))
        assertTrue(looksLikeMotionPhoto("IMG.jpg", null, "xxMotionPhoto_Dataxx".toByteArray()))
        assertFalse(looksLikeMotionPhoto("IMG_20240101.jpg", "<x:xmpmeta/>", "plain".toByteArray()))
    }

    private fun video(size: Long, durationMs: Long, mime: String = "video/mp4", ageDays: Int = 60) =
        MediaItem(2, mockk<Uri>(), "VID.mp4", "DCIM/Camera/", mime, size, now - ageDays * day, isVideo = true, durationMs = durationMs)

    @Test fun videoPrecheck() {
        val s = CompressionSettings(videoBitrateMbps = 6, minSavingsPercent = 20, minAgeDays = 30)
        // 10 s mit 40 Mbit/s → Ziel ≈ 7,7 MB: lohnt sich
        assertNull(precheckVideo(video(50_000_000, 10_000), s, now))
        // 10 s mit 6,4 Mbit/s → Ziel kaum kleiner
        assertEquals(SkipReason.TOO_LITTLE_SAVING, precheckVideo(video(8_000_000, 10_000), s, now))
        assertEquals(SkipReason.UNSUPPORTED_FORMAT, precheckVideo(video(50_000_000, 10_000, mime = "video/webm"), s, now))
        assertEquals(SkipReason.UNSUPPORTED_FORMAT, precheckVideo(video(50_000_000, 0), s, now))
        assertEquals(SkipReason.TOO_RECENT, precheckVideo(video(50_000_000, 10_000, ageDays = 1), s, now))
    }

    @Test fun videoTargetSize() {
        assertEquals(1920 to 1080, targetVideoSize(3840, 2160, 1080))
        assertEquals(1080 to 1920, targetVideoSize(2160, 3840, 1080))
        assertEquals(1280 to 720, targetVideoSize(1920, 1080, 720))
        assertEquals(1280 to 720, targetVideoSize(1280, 720, 1080))
        assertEquals(3840 to 2160, targetVideoSize(3840, 2160, null))
        // Ungerade Ergebnisse werden auf gerade Kanten gerundet: 1000×750 → 960×720
        assertEquals(960 to 720, targetVideoSize(1000, 750, 720))
        assertEquals(854 to 480, targetVideoSize(1280, 720, 480))
    }

    @Test fun videoMetadataParsing() {
        assertEquals(java.time.Instant.parse("2024-05-01T12:34:56Z").toEpochMilli(), parseVideoDate("20240501T123456.000Z"))
        assertNull(parseVideoDate("19040101T000000.000Z"))
        assertNull(parseVideoDate(null))
        assertNull(parseVideoDate("kaputt"))
        assertEquals(52.52f to 13.405f, parseIso6709("+52.5200+013.4050/"))
        assertEquals(-33.8688f to 151.2093f, parseIso6709("-33.8688+151.2093+012.5/"))
        assertNull(parseIso6709("+99.0+013.0/"))
        assertNull(parseIso6709(null))
    }

    @Test fun exifTagSelection() {
        val all = listOf("DateTimeOriginal", "Make", "Orientation", "GPSLatitude", "GPSVersionID", "MakerNote", "PixelXDimension", "Make")
        assertEquals(listOf("DateTimeOriginal", "Make", "GPSLatitude", "GPSVersionID"), tagsToCopy(all, keepGps = true))
        assertEquals(listOf("DateTimeOriginal", "Make"), tagsToCopy(all, keepGps = false))
    }
}
