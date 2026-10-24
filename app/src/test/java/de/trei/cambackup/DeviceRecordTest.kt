package de.trei.cambackup

import de.trei.cambackup.storage.DeviceRecord
import de.trei.cambackup.storage.DeviceRelation
import de.trei.cambackup.storage.FolderNameCheck
import de.trei.cambackup.storage.LocalIdentity
import de.trei.cambackup.storage.metadataJson
import de.trei.cambackup.storage.relationOf
import de.trei.cambackup.storage.sanitizeFolderName
import de.trei.cambackup.storage.validateFolderName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRecordTest {

    private val me = LocalIdentity("uuid-me", "aid-me", "Google", "Pixel 8")

    private fun record(uuid: String, androidId: String?, manufacturer: String = "Google", model: String = "Pixel 8", prev: List<String> = emptyList()) =
        DeviceRecord(
            deviceUuid = uuid, androidId = androidId, manufacturer = manufacturer, model = model,
            displayName = "Handy", createdAt = "2026-01-01T00:00:00Z", lastSeenAt = "2026-01-01T00:00:00Z",
            appVersion = "1.0.0", previousUuids = prev,
        )

    @Test fun sameByUuid() = assertEquals(DeviceRelation.SAME, relationOf(me, record("uuid-me", "other")))

    @Test fun sameByPreviousUuid() = assertEquals(DeviceRelation.SAME, relationOf(me, record("new", null, prev = listOf("uuid-me"))))

    @Test fun sameByAndroidIdAfterReinstall() = assertEquals(DeviceRelation.SAME, relationOf(me, record("old-uuid", "aid-me")))

    /** Neuinstallation mit anderem Signaturschlüssel: UUID und ANDROID_ID neu, Modell gleich → nur mit Rückfrage. */
    @Test fun sameModelNeedsConfirmation() =
        assertEquals(DeviceRelation.SAME_MODEL, relationOf(me, record("x", "y", manufacturer = "google", model = "pixel 8")))

    @Test fun otherPhoneIsRejected() {
        assertEquals(DeviceRelation.OTHER, relationOf(me, record("x", "y", manufacturer = "samsung", model = "SM-S918B")))
        assertEquals(DeviceRelation.OTHER, relationOf(me, record("x", "y", model = "Pixel 7")))
    }

    @Test fun missingAndroidIdDoesNotMatchOtherMissingId() =
        assertEquals(DeviceRelation.OTHER, relationOf(LocalIdentity("u", null, "A", "B"), record("x", null, "C", "D")))

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
        val r = record("u", "a")
        val text = metadataJson.encodeToString(DeviceRecord.serializer(), r)
        val withExtra = text.replaceFirst("{", "{\n  \"futureField\": 42,")
        assertEquals(r, metadataJson.decodeFromString(DeviceRecord.serializer(), withExtra))
    }
}
