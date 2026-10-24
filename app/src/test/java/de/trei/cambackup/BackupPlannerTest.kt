package de.trei.cambackup

import android.net.Uri
import de.trei.cambackup.backup.FAT32_MAX_FILE_SIZE
import de.trei.cambackup.backup.SPACE_RESERVE_FIXED
import de.trei.cambackup.backup.SpaceCheck
import de.trei.cambackup.backup.backupSubPath
import de.trei.cambackup.backup.checkSpace
import de.trei.cambackup.backup.planBackup
import de.trei.cambackup.backup.requiredSpace
import de.trei.cambackup.backup.uniqueName
import de.trei.cambackup.index.EntryStatus
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.index.decodeJournal
import de.trei.cambackup.index.encodeJournalLine
import de.trei.cambackup.media.MediaItem
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BackupPlannerTest {

    private val uri = mockk<Uri>()

    private fun item(name: String, size: Long, taken: Long = 0, id: Long = name.hashCode().toLong()) =
        MediaItem(id, uri, name, "DCIM/Camera/", "image/jpeg", size, taken, isVideo = false)

    private fun entry(name: String, size: Long, status: EntryStatus = EntryStatus.VERIFIED, compressed: Long? = null, backupPath: String = "2026/01/$name") =
        IndexEntry(
            phonePath = "DCIM/Camera/$name", mimeType = "image/jpeg", dateTakenMillis = 0, originalSize = size,
            originalSha256 = "abc", backupPath = backupPath, backedUpAt = "2026-01-01T00:00:00Z",
            status = status, compressedSize = compressed,
        )

    @Test fun newFilesAreCopiedOldestFirst() {
        val plan = planBackup(listOf(item("b.jpg", 10, taken = 2), item("a.jpg", 20, taken = 1)), IndexState(), "exfat")
        assertEquals(listOf("a.jpg", "b.jpg"), plan.toCopy.map { it.displayName })
        assertEquals(30, plan.bytesToCopy)
        assertEquals(0, plan.alreadyBackedUp)
    }

    @Test fun verifiedOriginalIsSkipped() {
        val plan = planBackup(listOf(item("a.jpg", 100)), IndexState(listOf(entry("a.jpg", 100))), null)
        assertTrue(plan.toCopy.isEmpty())
        assertEquals(1, plan.alreadyBackedUp)
    }

    @Test fun compressedVersionOnPhoneIsNotBackedUpAgain() {
        val index = IndexState(listOf(entry("a.jpg", 100, EntryStatus.COMPRESSED, compressed = 30)))
        val plan = planBackup(listOf(item("a.jpg", 30)), index, null)
        assertTrue(plan.toCopy.isEmpty())
        assertEquals(1, plan.alreadyBackedUp)
    }

    @Test fun changedFileIsBackedUpAgain() {
        val plan = planBackup(listOf(item("a.jpg", 150)), IndexState(listOf(entry("a.jpg", 100))), null)
        assertEquals(1, plan.toCopy.size)
    }

    @Test fun hugeFilesAreExcludedOnFat32Only() {
        val huge = item("v.mp4", FAT32_MAX_FILE_SIZE + 1)
        val onFat = planBackup(listOf(huge, item("a.jpg", 1)), IndexState(), "vfat")
        assertEquals(listOf("v.mp4"), onFat.tooLargeForFileSystem.map { it.displayName })
        assertEquals(listOf("a.jpg"), onFat.toCopy.map { it.displayName })

        val onExfat = planBackup(listOf(huge), IndexState(), "exfat")
        assertTrue(onExfat.tooLargeForFileSystem.isEmpty())
        assertEquals(1, onExfat.toCopy.size)
    }

    @Test fun exactlyFat32LimitIsAllowed() {
        val plan = planBackup(listOf(item("v.mp4", FAT32_MAX_FILE_SIZE)), IndexState(), "vfat")
        assertEquals(1, plan.toCopy.size)
    }

    @Test fun spaceCheck() {
        val gb = 1024L * 1024 * 1024
        assertEquals(gb + SPACE_RESERVE_FIXED + gb / 100, requiredSpace(gb))
        assertEquals(0, requiredSpace(0))
        assertTrue(checkSpace(gb, 2 * gb) is SpaceCheck.Enough)
        assertTrue(checkSpace(gb, gb) is SpaceCheck.NotEnough)
        assertTrue(checkSpace(gb, null) is SpaceCheck.Unknown)
        assertTrue(checkSpace(0, 0) is SpaceCheck.Enough)
    }

    @Test fun subPathUsesLocalDate() {
        // 2025-12-31T23:30Z ist in Berlin bereits Januar 2026
        val millis = java.time.Instant.parse("2025-12-31T23:30:00Z").toEpochMilli()
        assertEquals("2026/01", backupSubPath(millis, ZoneId.of("Europe/Berlin")))
        assertEquals("2025/12", backupSubPath(millis, ZoneId.of("UTC")))
    }

    @Test fun uniqueNames() {
        val taken = setOf("a.jpg", "a (1).jpg", "noext")
        assertEquals("b.jpg", uniqueName("b.jpg", taken::contains))
        assertEquals("a (2).jpg", uniqueName("a.jpg", taken::contains))
        assertEquals("noext (1)", uniqueName("noext", taken::contains))
        assertEquals(".hidden", uniqueName(".hidden", taken::contains))
    }

    @Test fun indexStateReplacesByBackupPath() {
        val state = IndexState(listOf(entry("a.jpg", 100)))
        state.put(entry("a.jpg", 100, EntryStatus.COMPRESSED, compressed = 40))
        assertEquals(1, state.size)
        assertEquals(EntryStatus.COMPRESSED, state.forPhonePath("DCIM/Camera/a.jpg").single().status)
    }

    @Test fun indexStateKeepsMultipleVersionsOfSamePhoneFile() {
        val state = IndexState(listOf(entry("a.jpg", 100), entry("a.jpg", 150, backupPath = "2026/01/a (1).jpg")))
        assertEquals(2, state.forPhonePath("DCIM/Camera/a.jpg").size)
        assertTrue(planBackup(listOf(item("a.jpg", 150)), state, null).toCopy.isEmpty())
    }

    @Test fun journalRoundTripSkipsTruncatedLastLine() {
        val a = entry("a.jpg", 1)
        val b = entry("b.jpg", 2)
        val text = encodeJournalLine(a) + encodeJournalLine(b) + encodeJournalLine(entry("c.jpg", 3)).take(20)
        assertEquals(listOf(a, b), decodeJournal(text))
    }
}
