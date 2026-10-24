package de.trei.cambackup

import android.net.Uri
import de.trei.cambackup.backup.backupDirFor
import de.trei.cambackup.backup.findMissing
import de.trei.cambackup.backup.folderOf
import de.trei.cambackup.index.EntryStatus
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.index.decodeJournal
import de.trei.cambackup.index.encodeJournalLine
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.FolderLayout
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MissingFilesTest {

    private val uri = mockk<Uri>()

    private fun item(path: String, size: Long = 100) = MediaItem(
        path.hashCode().toLong(), uri, path.substringAfterLast('/'), path.substringBeforeLast('/') + "/",
        "image/jpeg", size, 0, isVideo = false,
    )

    private fun entry(path: String, backupPath: String = "2026/10/${path.substringAfterLast('/')}", at: String = "2026-01-01T00:00:00Z") =
        IndexEntry(path, "image/jpeg", 0, 100, "h", backupPath, at)

    @Test fun deletedFilesAreFound() {
        val index = IndexState(listOf(entry("DCIM/Camera/a.jpg"), entry("DCIM/Camera/b.jpg")))
        val missing = findMissing(listOf(item("DCIM/Camera/a.jpg")), index, setOf("DCIM/Camera/"))
        assertEquals(listOf("DCIM/Camera/b.jpg"), missing.map { it.phonePath })
        assertEquals("b.jpg", missing.single().displayName)
    }

    @Test fun deselectedFoldersDoNotCountAsDeleted() {
        val index = IndexState(listOf(entry("Pictures/Screenshots/s.png")))
        assertTrue(findMissing(emptyList(), index, setOf("DCIM/Camera/")).isEmpty())
        assertEquals(1, findMissing(emptyList(), index, setOf("DCIM/Camera/", "Pictures/Screenshots/")).size)
    }

    @Test fun changedFileIsNotDeleted() {
        // Datei existiert mit anderer Größe → geändert, nicht gelöscht
        val index = IndexState(listOf(entry("DCIM/Camera/a.jpg")))
        assertTrue(findMissing(listOf(item("DCIM/Camera/a.jpg", size = 999)), index, setOf("DCIM/Camera/")).isEmpty())
    }

    @Test fun allVersionsAreGroupedNewestLast() {
        val index = IndexState(
            listOf(
                entry("DCIM/Camera/a.jpg", "2026/10/a (1).jpg", at = "2026-02-01T00:00:00Z"),
                entry("DCIM/Camera/a.jpg", "2026/10/a.jpg", at = "2026-01-01T00:00:00Z"),
            ),
        )
        val m = findMissing(emptyList(), index, setOf("DCIM/Camera/")).single()
        assertEquals(listOf("2026/10/a.jpg", "2026/10/a (1).jpg"), m.versions.map { it.backupPath })
        assertEquals("2026/10/a (1).jpg", m.latest.backupPath)
    }

    @Test fun tombstoneRemovesEntryThroughJournal() {
        val a = entry("DCIM/Camera/a.jpg")
        val state = IndexState(decodeJournal(encodeJournalLine(a) + encodeJournalLine(a.copy(deleted = true))))
        assertEquals(0, state.size)
        assertTrue(state.forPhonePath("DCIM/Camera/a.jpg").isEmpty())
        // Wiederherstellen danach ist möglich
        state.put(a.copy(status = EntryStatus.VERIFIED))
        assertEquals(1, state.size)
    }

    @Test fun folderOfPath() {
        assertEquals("DCIM/Camera/", folderOf("DCIM/Camera/IMG_1.jpg"))
        assertEquals("Pictures/Screenshots/", folderOf("Pictures/Screenshots/s.png"))
    }

    @Test fun backupDirsWithAndWithoutSubfolders() {
        val zone = ZoneId.of("Europe/Berlin")
        val t = Instant.parse("2026-10-24T10:00:00Z").toEpochMilli()
        assertEquals("2026/10", backupDirFor("DCIM/Camera/", t, FolderLayout.YEAR_MONTH, separateFolders = false, zone))
        assertEquals("DCIM/Camera/2026/10", backupDirFor("DCIM/Camera/", t, FolderLayout.YEAR_MONTH, separateFolders = true, zone))
        assertEquals("Pictures/Screenshots", backupDirFor("Pictures/Screenshots/", t, FolderLayout.FLAT, separateFolders = true, zone))
        assertEquals("", backupDirFor("DCIM/Camera/", t, FolderLayout.FLAT, separateFolders = false, zone))
        assertEquals("2026", backupDirFor("DCIM/Camera/", t, FolderLayout.YEAR, separateFolders = false, zone))
        assertEquals("Pictures/Screenshots/2026", backupDirFor("Pictures/Screenshots/", t, FolderLayout.YEAR, separateFolders = true, zone))
    }
}
