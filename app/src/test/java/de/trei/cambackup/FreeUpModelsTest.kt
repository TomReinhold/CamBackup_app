package de.trei.cambackup

import android.net.Uri
import de.trei.cambackup.freeup.PREPARE_RESERVE
import de.trei.cambackup.freeup.PendingReplacement
import de.trei.cambackup.freeup.prepareSpaceOk
import de.trei.cambackup.freeup.RESTORE_RESERVE
import de.trei.cambackup.freeup.restoreCandidates
import de.trei.cambackup.freeup.restoreSpaceNeeded
import de.trei.cambackup.freeup.asCompressed
import de.trei.cambackup.freeup.asVerifiedOnly
import de.trei.cambackup.freeup.overviewFor
import de.trei.cambackup.index.EntryStatus
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.media.MediaItem
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeUpModelsTest {

    private val uri = mockk<Uri>()

    private fun item(name: String, size: Long) =
        MediaItem(name.hashCode().toLong(), uri, name, "DCIM/Camera/", "image/jpeg", size, 0, isVideo = false)

    private fun entry(name: String, size: Long, status: EntryStatus = EntryStatus.VERIFIED, compressed: Long? = null) = IndexEntry(
        phonePath = "DCIM/Camera/$name", mimeType = "image/jpeg", dateTakenMillis = 0, originalSize = size,
        originalSha256 = "orig-$name", backupPath = "2026/10/$name", backedUpAt = "t", status = status, compressedSize = compressed,
    )

    @Test fun classifiesFiles() {
        val index = IndexState(
            listOf(
                entry("a.jpg", 100),                                           // gesichert, unverändert → Kandidat
                entry("b.jpg", 100, EntryStatus.COMPRESSED, compressed = 30),  // bereits ersetzt
                entry("c.jpg", 100),                                           // gesichert, aber verändert
            ),
        )
        val o = overviewFor(listOf(item("a.jpg", 100), item("b.jpg", 30), item("c.jpg", 120), item("d.jpg", 50)), index)
        assertEquals(listOf("a.jpg"), o.candidates.map { it.item.displayName })
        assertEquals(1, o.alreadyCompressed)
        assertEquals(70, o.alreadyCompressedSaved)
        assertEquals(2, o.notBackedUp) // c (verändert, neu zu sichern) und d
    }

    @Test fun keptOriginalsAreNotCompressedAgain() {
        val index = IndexState(listOf(entry("a.jpg", 100).copy(keepOriginal = true)))
        val o = overviewFor(listOf(item("a.jpg", 100)), index)
        assertEquals(0, o.candidates.size)
        assertEquals(1, o.keptOriginal)
    }

    @Test fun notWorthOnlyForSameSettings() {
        val s = de.trei.cambackup.settings.CompressionSettings(jpegQuality = 85)
        val key = de.trei.cambackup.compress.shrinkSettingsKey(s, isVideo = false)
        val index = IndexState(listOf(entry("a.jpg", 100).copy(notWorthFor = key)))
        // gleiche Einstellungen: nicht erneut versuchen
        val same = overviewFor(listOf(item("a.jpg", 100)), index, s)
        assertEquals(0, same.candidates.size)
        assertEquals(1, same.notWorth)
        // andere Qualität: wieder ein Kandidat
        val other = overviewFor(listOf(item("a.jpg", 100)), index, s.copy(jpegQuality = 60))
        assertEquals(1, other.candidates.size)
        assertEquals(0, other.notWorth)
    }

    @Test fun restoreCandidatesOnlyForUntouchedCompressedFiles() {
        val index = IndexState(
            listOf(
                entry("a.jpg", 100, EntryStatus.COMPRESSED, compressed = 30), // verkleinert, unverändert
                entry("b.jpg", 100, EntryStatus.COMPRESSED, compressed = 30), // inzwischen bearbeitet
                entry("c.jpg", 100),                                          // nur gesichert
            ),
        )
        val c = restoreCandidates(listOf(item("a.jpg", 30), item("b.jpg", 45), item("c.jpg", 100)), index)
        assertEquals(listOf("a.jpg"), c.map { it.item.displayName })
        assertEquals(70, c.single().extraBytes)
        assertEquals(70 + RESTORE_RESERVE, restoreSpaceNeeded(c))
        assertEquals(0, restoreSpaceNeeded(emptyList()))
    }

    @Test fun statusTransitions() {
        val p = PendingReplacement(
            1, "content://x", "a.jpg", "DCIM/Camera/a.jpg", false, "2026/10/a.jpg", 100, "orig", "/c/1.jpg", 30, "new", "jpeg q85 max3072",
        )
        val compressed = entry("a.jpg", 100).asCompressed(p, "2026-10-24T00:00:00Z")
        assertEquals(EntryStatus.COMPRESSED, compressed.status)
        assertEquals(30L, compressed.compressedSize)
        assertEquals("new", compressed.compressedSha256)
        assertEquals("jpeg q85 max3072", compressed.compressionParams)

        val reverted = compressed.asVerifiedOnly()
        assertEquals(EntryStatus.VERIFIED, reverted.status)
        assertNull(reverted.compressedSize)
        assertEquals(entry("a.jpg", 100), reverted)
    }

    @Test
    fun `vorbereiten nur mit reserve`() {
        val mb = 1024L * 1024
        assertTrue(prepareSpaceOk(PREPARE_RESERVE + 100 * mb, 100 * mb))
        assertFalse(prepareSpaceOk(PREPARE_RESERVE + 99 * mb, 100 * mb))
    }
}
