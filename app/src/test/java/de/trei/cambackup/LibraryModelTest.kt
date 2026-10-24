package de.trei.cambackup

import android.net.Uri
import de.trei.cambackup.index.EntryStatus
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.library.MediaStatus
import de.trei.cambackup.library.classify
import de.trei.cambackup.library.dayLabel
import de.trei.cambackup.library.formatDuration
import de.trei.cambackup.library.groupByDay
import de.trei.cambackup.library.statsOf
import de.trei.cambackup.library.withoutDrive
import de.trei.cambackup.media.MediaItem
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LibraryModelTest {

    private val uri = mockk<Uri>()

    private fun item(name: String, size: Long, taken: Long = 0) =
        MediaItem(name.hashCode().toLong(), uri, name, "DCIM/Camera/", "image/jpeg", size, taken, isVideo = false)

    private fun entry(name: String, size: Long, status: EntryStatus = EntryStatus.VERIFIED, compressed: Long? = null) = IndexEntry(
        "DCIM/Camera/$name", "image/jpeg", 0, size, "h", "2026/10/$name", "t", status, compressed,
    )

    @Test fun classificationAndStats() {
        val index = IndexState(listOf(entry("a.jpg", 100), entry("b.jpg", 100, EntryStatus.COMPRESSED, 30)))
        val items = listOf(item("a.jpg", 100), item("b.jpg", 30), item("c.jpg", 50)).map { classify(it, index) }
        assertEquals(listOf(MediaStatus.BACKED_UP, MediaStatus.COMPRESSED, MediaStatus.NOT_BACKED_UP), items.map { it.status })

        val s = statsOf(items)
        assertEquals(3, s.totalCount)
        assertEquals(180, s.totalBytes)
        assertEquals(1, s.backedUpCount)
        assertEquals(1, s.compressedCount)
        assertEquals(70, s.savedBytes)
        assertEquals(1, s.openCount)
        assertEquals(50, s.openBytes)
    }

    @Test fun withoutDriveNothingCountsAsBackedUp() {
        val index = IndexState(listOf(entry("a.jpg", 100), entry("b.jpg", 100, EntryStatus.COMPRESSED, 30)))
        val items = listOf(item("a.jpg", 100), item("b.jpg", 30), item("c.jpg", 50)).map { classify(it, index).withoutDrive() }
        assertEquals(listOf(MediaStatus.UNKNOWN, MediaStatus.COMPRESSED, MediaStatus.UNKNOWN), items.map { it.status })
        assertEquals(false, items.any { it.driveConnected })

        val s = statsOf(items)
        assertEquals(0, s.backedUpCount)
        assertEquals(0, s.openCount)
        assertEquals(2, s.unknownCount)
        assertEquals(150, s.unknownBytes)
        assertEquals(1, s.compressedCount)
    }

    @Test fun changedFileIsNotBackedUp() {
        val index = IndexState(listOf(entry("a.jpg", 100)))
        assertEquals(MediaStatus.NOT_BACKED_UP, classify(item("a.jpg", 120), index).status)
    }

    @Test fun dayLabels() {
        val today = LocalDate.of(2026, 10, 24) // Samstag
        assertEquals("Heute", dayLabel(today, today))
        assertEquals("Gestern", dayLabel(today.minusDays(1), today))
        assertEquals("Mi., 21. Okt.", dayLabel(LocalDate.of(2026, 10, 21), today))
        assertEquals("So., 31. Dez. 2023", dayLabel(LocalDate.of(2023, 12, 31), today))
    }

    @Test fun groupsByLocalDayNewestFirst() {
        val zone = ZoneId.of("Europe/Berlin")
        val day1 = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val day2 = LocalDate.of(2026, 10, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val items = listOf(item("a", 1, day1), item("b", 1, day2 + 1000), item("c", 1, day2)).map { classify(it, IndexState()) }
        val groups = groupByDay(items, zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)), groups.map { it.first })
        assertEquals(listOf("b", "c"), groups[0].second.map { it.media.displayName })
    }

    @Test fun durations() {
        assertEquals("0:42", formatDuration(42_000))
        assertEquals("10:05", formatDuration(605_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }
}
