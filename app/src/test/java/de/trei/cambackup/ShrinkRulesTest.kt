package de.trei.cambackup

import android.net.Uri
import de.trei.cambackup.compress.SkipReason
import de.trei.cambackup.compress.isMarkedCompressed
import de.trei.cambackup.compress.looksLikeMotionPhoto
import de.trei.cambackup.compress.markSoftware
import de.trei.cambackup.compress.precheckImage
import de.trei.cambackup.compress.tagsToCopy
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

    @Test fun exifTagSelection() {
        val all = listOf("DateTimeOriginal", "Make", "Orientation", "GPSLatitude", "GPSVersionID", "MakerNote", "PixelXDimension", "Make")
        assertEquals(listOf("DateTimeOriginal", "Make", "GPSLatitude", "GPSVersionID"), tagsToCopy(all, keepGps = true))
        assertEquals(listOf("DateTimeOriginal", "Make"), tagsToCopy(all, keepGps = false))
    }
}
