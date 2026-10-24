package de.trei.cambackup

import de.trei.cambackup.backup.BackupProgress
import de.trei.cambackup.backup.SessionOffset
import de.trei.cambackup.backup.offsetSince
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class BackupSessionTest {

    private fun entry(name: String, size: Long, at: String) =
        IndexEntry("DCIM/Camera/$name", "image/jpeg", 0, size, "h$name", "2026/10/$name", at)

    @Test fun `zählt nur seit Beginn des Backups Gesichertes`() {
        val index = IndexState(
            listOf(
                entry("alt.jpg", 500, "2026-10-01T10:00:00Z"),
                entry("a.jpg", 100, "2026-10-24T17:00:00Z"),
                entry("b.jpg", 200, "2026-10-24T17:05:00.123456Z"),
            ),
        )
        assertEquals(SessionOffset(2, 300), offsetSince(index, Instant.parse("2026-10-24T17:00:00Z")))
    }

    @Test fun `Fortschritt läuft über alle Läufe durch`() {
        val run = BackupProgress(fileIndex = 9, fileCount = 100, bytesDone = 50, bytesTotal = 1000)
        val total = SessionOffset(files = 50, bytes = 500).apply(run)
        assertEquals(59, total.fileIndex)
        assertEquals(150, total.fileCount)
        assertEquals(1050, total.bytesDone)
        assertEquals(2000, total.bytesTotal)
    }
}
