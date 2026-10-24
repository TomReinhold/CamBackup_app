package de.trei.cambackup

import de.trei.cambackup.storage.DeviceFolder
import de.trei.cambackup.storage.DeviceMatch
import de.trei.cambackup.storage.DeviceRecord
import de.trei.cambackup.storage.FolderNameCheck
import de.trei.cambackup.storage.LocalIdentity
import de.trei.cambackup.storage.matchDevice
import de.trei.cambackup.storage.metadataJson
import de.trei.cambackup.storage.sanitizeFolderName
import de.trei.cambackup.storage.validateFolderName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRecordTest {

    private val me = LocalIdentity("uuid-me", "aid-me", "Google", "Pixel 8")

    private fun folder(name: String, uuid: String, androidId: String?, model: String = "Pixel 8", prev: List<String> = emptyList()) =
        DeviceFolder(
            name,
            DeviceRecord(
                deviceUuid = uuid, androidId = androidId, manufacturer = "Google", model = model,
                displayName = name, createdAt = "2026-01-01T00:00:00Z", lastSeenAt = "2026-01-01T00:00:00Z",
                appVersion = "0.1.0", previousUuids = prev,
            ),
        )

    @Test fun recognizedByUuid() {
        val mine = folder("Mein Pixel", "uuid-me", "other")
        val result = matchDevice(me, listOf(folder("Anderes", "x", "y"), mine))
        assertEquals(DeviceMatch.Recognized(mine, viaAndroidId = false), result)
    }

    @Test fun recognizedByPreviousUuid() {
        val mine = folder("Mein Pixel", "new", null, prev = listOf("uuid-me"))
        assertEquals(DeviceMatch.Recognized(mine, false), matchDevice(me, listOf(mine)))
    }

    @Test fun recognizedByAndroidIdAfterReinstall() {
        val mine = folder("Mein Pixel", "old-uuid", "aid-me")
        assertEquals(DeviceMatch.Recognized(mine, viaAndroidId = true), matchDevice(me, listOf(mine)))
    }

    @Test fun ambiguousAndroidIdIsUnknownWithThoseFirst() {
        val a = folder("B", "1", "aid-me")
        val b = folder("A", "2", "aid-me")
        val other = folder("0 Anderes", "3", "zzz", model = "Galaxy")
        val result = matchDevice(me, listOf(other, a, b)) as DeviceMatch.Unknown
        assertEquals(listOf("A", "B", "0 Anderes"), result.candidates.map { it.folderName })
    }

    @Test fun unknownSortsSameModelFirst() {
        val galaxy = folder("A Galaxy", "1", null, model = "Galaxy")
        val pixel = folder("Z Pixel", "2", null)
        val result = matchDevice(me, listOf(galaxy, pixel)) as DeviceMatch.Unknown
        assertEquals(listOf("Z Pixel", "A Galaxy"), result.candidates.map { it.folderName })
    }

    @Test fun emptyDriveIsUnknown() = assertEquals(DeviceMatch.Unknown(emptyList()), matchDevice(me, emptyList()))

    @Test fun validNameIsTrimmed() =
        assertEquals(FolderNameCheck.Ok("Pixel Tom"), validateFolderName("  Pixel Tom ", emptySet()))

    @Test fun invalidNames() {
        listOf("", "   ", "a/b", "a:b", "x?", "name.", "a".repeat(65)).forEach {
            assertTrue("'$it' sollte ungültig sein", validateFolderName(it, emptySet()) is FolderNameCheck.Invalid)
        }
    }

    @Test fun existingNameCaseInsensitive() =
        assertTrue(validateFolderName("pixel", setOf("Pixel")) is FolderNameCheck.Invalid)

    @Test fun sanitize() {
        assertEquals("Pixel_8 Pro", sanitizeFolderName("Pixel/8 Pro."))
        assertEquals("Smartphone", sanitizeFolderName("..."))
    }

    @Test fun suggestedNameAvoidsDuplicateManufacturer() {
        assertEquals("Google Pixel 8", me.suggestedFolderName)
        assertEquals("samsung SM-S918B", LocalIdentity("u", null, "samsung", "SM-S918B").suggestedFolderName)
        assertEquals("OnePlus 12", LocalIdentity("u", null, "OnePlus", "OnePlus 12").suggestedFolderName)
    }

    @Test fun jsonRoundTripIgnoresUnknownFields() {
        val record = folder("X", "u", "a").record
        val text = metadataJson.encodeToString(DeviceRecord.serializer(), record)
        val withExtra = text.replaceFirst("{", "{\n  \"futureField\": 42,")
        assertEquals(record, metadataJson.decodeFromString(DeviceRecord.serializer(), withExtra))
    }
}
