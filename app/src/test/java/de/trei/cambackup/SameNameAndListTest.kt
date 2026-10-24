package de.trei.cambackup

import android.net.Uri
import de.trei.cambackup.backup.backupDirFor
import de.trei.cambackup.backup.joinPath
import de.trei.cambackup.backup.phonePath
import de.trei.cambackup.backup.planBackup
import de.trei.cambackup.backup.uniqueName
import de.trei.cambackup.freeup.keepOriginalChanges
import de.trei.cambackup.freeup.overviewFor
import de.trei.cambackup.index.EntryStatus
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.index.fileListCsv
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.settings.FolderLayout
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SameNameAndListTest {

    private val uri = mockk<Uri>()
    private val zone = ZoneId.of("Europe/Berlin")
    private val t = 1_790_000_000_000L // Sept. 2026

    private fun item(folder: String, name: String, size: Long, id: Long) =
        MediaItem(id, uri, name, folder, "image/jpeg", size, t, isVideo = false)

    private fun entry(phonePath: String, backupPath: String, size: Long = 100, status: EntryStatus = EntryStatus.VERIFIED, keep: Boolean = false) =
        IndexEntry(
            phonePath = phonePath, mimeType = "image/jpeg", dateTakenMillis = t, originalSize = size, originalSha256 = "sha-$backupPath",
            backupPath = backupPath, backedUpAt = "2026-10-24T00:00:00Z", status = status,
            compressedSize = if (status == EntryStatus.COMPRESSED) 10 else null, keepOriginal = keep,
        )

    @Test fun gleichnamigeOrdnerLandenGetrennt() {
        for (layout in FolderLayout.entries) {
            val a = backupDirFor("DCIM/Camera/", t, layout, separateFolders = true, zone)
            val b = backupDirFor("Pictures/Camera/", t, layout, separateFolders = true, zone)
            assertNotEquals("Layout $layout", a, b)
            assertTrue(a.startsWith("DCIM/Camera") && b.startsWith("Pictures/Camera"))
        }
    }

    @Test fun gleicheDateinamenInGleichnamigenOrdnernWerdenBeideGesichert() {
        val a = item("DCIM/Camera/", "IMG_1.jpg", 100, 1)
        val b = item("Pictures/Camera/", "IMG_1.jpg", 200, 2)
        // Ist nur eine der beiden gesichert, gilt die andere nicht als gesichert
        val index = IndexState(listOf(entry(a.phonePath, "DCIM/Camera/2026/IMG_1.jpg", size = 100)))
        val plan = planBackup(listOf(a, b), index, "exfat")
        assertEquals(listOf(b), plan.toCopy)
        assertEquals(1, plan.alreadyBackedUp)
    }

    @Test fun gleicherNameImSelbenZielordnerUeberschreibtNichts() {
        // z. B. nach einem Wechsel der Ablage: im Zielordner liegt schon eine Datei mit dem Namen
        val taken = setOf("IMG_1.jpg")
        val name = uniqueName("IMG_1.jpg", taken::contains)
        assertEquals("IMG_1 (1).jpg", name)
        val index = IndexState(
            listOf(
                entry("DCIM/Camera/IMG_1.jpg", joinPath("2026", "IMG_1.jpg")),
                entry("Pictures/Camera/IMG_1.jpg", joinPath("2026", name)),
            ),
        )
        assertEquals("2026/IMG_1.jpg", index.forPhonePath("DCIM/Camera/IMG_1.jpg").single().backupPath)
        assertEquals("2026/IMG_1 (1).jpg", index.forPhonePath("Pictures/Camera/IMG_1.jpg").single().backupPath)
    }

    @Test fun dateilisteZeigtHerkunftUndStatus() {
        val csv = fileListCsv(
            listOf(
                entry("Pictures/Camera/IMG_1.jpg", "2026/IMG_1 (1).jpg", keep = true),
                entry("DCIM/Camera/IMG_1.jpg", "2026/IMG_1.jpg", status = EntryStatus.COMPRESSED).copy(compressedAt = "2026-10-24T20:20:45Z"),
                entry("DCIM/Camera/weg.jpg", "2026/weg.jpg").copy(deleted = true),
                entry("DCIM/Camera/a;b.jpg", "2026/a;b.jpg"),
            ),
            zone,
        )
        assertTrue(csv.startsWith("﻿Datei auf dem Medium;Pfad auf dem Smartphone;"))
        val lines = csv.removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }
        assertEquals(4, lines.size) // Kopf + 3, gelöschte fehlt
        assertEquals("2026/IMG_1 (1).jpg;Pictures/Camera/IMG_1.jpg;2026-09-21 16:13:20;image/jpeg;100;Gesichert;100;;ja;sha-2026/IMG_1 (1).jpg", lines[1])
        assertEquals("2026/IMG_1.jpg;DCIM/Camera/IMG_1.jpg;2026-09-21 16:13:20;image/jpeg;100;Auf dem Smartphone verkleinert;10;2026-10-24 22:20:45;;sha-2026/IMG_1.jpg", lines[2])
        assertTrue(lines[3].startsWith("\"2026/a;b.jpg\";\"DCIM/Camera/a;b.jpg\";"))
    }

    @Test fun ausschliessenNurBeiGesichertenOriginalen() {
        val index = IndexState(
            listOf(
                entry("DCIM/Camera/a.jpg", "2026/a.jpg"),
                entry("DCIM/Camera/b.jpg", "2026/b.jpg", status = EntryStatus.COMPRESSED),
                entry("DCIM/Camera/c.jpg", "2026/c.jpg", keep = true),
                entry("DCIM/Camera/d.jpg", "2026/d.jpg"),
            ),
        )
        val all = setOf("2026/a.jpg", "2026/b.jpg", "2026/c.jpg")
        assertEquals(listOf("2026/a.jpg"), keepOriginalChanges(index, all, keep = true).map { it.backupPath })
        assertTrue(keepOriginalChanges(index, all, keep = true).all { it.keepOriginal })
        assertEquals(listOf("2026/c.jpg"), keepOriginalChanges(index, all, keep = false).map { it.backupPath })

        // Ausgeschlossene Dateien sind keine Kandidaten zum Verkleinern mehr
        keepOriginalChanges(index, all, keep = true).forEach(index::put)
        val items = listOf("a.jpg", "c.jpg", "d.jpg").mapIndexed { i, n -> item("DCIM/Camera/", n, 100, i.toLong()) }
        val o = overviewFor(items, index)
        assertEquals(listOf("d.jpg"), o.candidates.map { it.item.displayName })
        assertEquals(2, o.keptOriginal)
    }
}
