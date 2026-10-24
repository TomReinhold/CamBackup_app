package de.trei.cambackup

import de.trei.cambackup.backup.DetectedLayout
import de.trei.cambackup.backup.detectLayout
import de.trei.cambackup.backup.planRelocation
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.settings.FolderLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RelocationTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun millis(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun entry(phonePath: String, backupPath: String, taken: Long) = IndexEntry(
        phonePath = phonePath, mimeType = "image/jpeg", dateTakenMillis = taken, originalSize = 1, originalSha256 = "s-$backupPath",
        backupPath = backupPath, backedUpAt = "2026-10-24T00:00:00Z",
    )

    /** Stand wie auf dem Stick: nur DCIM/Camera, Ablage nach Jahr. */
    private val yearIndex = IndexState(
        listOf(
            entry("DCIM/Camera/a.jpg", "2024/a.jpg", millis(2024, 8, 4)),
            entry("DCIM/Camera/b.jpg", "2025/b.jpg", millis(2025, 3, 6)),
            entry("DCIM/Camera/c.mp4", "2026/c.mp4", millis(2026, 9, 24)),
        ),
    )

    @Test fun passendeAblageVerschiebtNichts() {
        assertTrue(planRelocation(yearIndex, FolderLayout.YEAR, separateFolders = false, zone).isEmpty())
    }

    @Test fun weitererOrdnerVerschiebtAlteFotosInDenUnterordner() {
        val moves = planRelocation(yearIndex, FolderLayout.YEAR, separateFolders = true, zone)
        assertEquals(
            listOf("2024/a.jpg" to "DCIM/Camera/2024", "2025/b.jpg" to "DCIM/Camera/2025", "2026/c.mp4" to "DCIM/Camera/2026"),
            moves.map { it.entry.backupPath to it.targetDir },
        )
        // Und zurück: ohne zweiten Ordner wieder direkt in die Jahresordner
        val moved = IndexState(moves.map { it.entry.copy(backupPath = "${it.targetDir}/${it.name}") })
        assertEquals(
            listOf("2024", "2025", "2026"),
            planRelocation(moved, FolderLayout.YEAR, separateFolders = false, zone).map { it.targetDir },
        )
    }

    @Test fun andereAblageArt() {
        assertEquals(
            listOf("2024/08", "2025/03", "2026/09"),
            planRelocation(yearIndex, FolderLayout.YEAR_MONTH, separateFolders = false, zone).map { it.targetDir },
        )
        assertEquals(listOf("", "", ""), planRelocation(yearIndex, FolderLayout.FLAT, separateFolders = false, zone).map { it.targetDir })
    }

    @Test fun zeitzoneAmMonatsendeVerschiebtNicht() {
        // In Japan am 1.9. um 3 Uhr gesichert, in Berlin ist es noch der 31.8. – liegt in 2026/09 und bleibt dort
        val taken = millis(2026, 8, 31, 20)
        val index = IndexState(listOf(entry("DCIM/Camera/x.jpg", "2026/09/x.jpg", taken)))
        assertTrue(planRelocation(index, FolderLayout.YEAR_MONTH, separateFolders = false, zone).isEmpty())
    }

    @Test fun ablageWirdAusDemIndexErkannt() {
        assertEquals(DetectedLayout(FolderLayout.YEAR, false), detectLayout(yearIndex, zone))
        val separate = IndexState(
            listOf(
                entry("DCIM/Camera/a.jpg", "DCIM/Camera/2024/08/a.jpg", millis(2024, 8, 4)),
                entry("Pictures/Screenshots/s.png", "Pictures/Screenshots/2025/03/s.png", millis(2025, 3, 6)),
            ),
        )
        assertEquals(DetectedLayout(FolderLayout.YEAR_MONTH, true), detectLayout(separate, zone))
        assertNull(detectLayout(IndexState(), zone))
    }
}
