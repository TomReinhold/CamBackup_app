package de.trei.cambackup.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Identität dieses Smartphones, wie die App sie lokal kennt. */
data class LocalIdentity(
    /** Von der App einmalig erzeugt; geht bei Neuinstallation verloren. */
    val deviceUuid: String,
    /** Settings.Secure.ANDROID_ID – bleibt bei Neuinstallation (gleicher Signaturschlüssel) erhalten. */
    val androidId: String?,
    val manufacturer: String,
    val model: String,
) {
    val suggestedFolderName: String
        get() = sanitizeFolderName(
            if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model",
        )
}

/** Inhalt von `<Geräteordner>/Metadata/device.json`. */
@Serializable
data class DeviceRecord(
    val formatVersion: Int = 1,
    val deviceUuid: String,
    val androidId: String? = null,
    val manufacturer: String,
    val model: String,
    val displayName: String,
    /** Zeitpunkte als ISO-8601 (UTC), damit die Datei auch am PC lesbar ist. */
    val createdAt: String,
    val lastSeenAt: String,
    val lastBackupAt: String? = null,
    val appVersion: String,
    /** Frühere UUIDs dieses Geräts (z. B. nach Neuinstallation der App). */
    val previousUuids: List<String> = emptyList(),
)

/** Ein Geräteordner auf dem Speichermedium samt gelesener device.json. */
data class DeviceFolder(val folderName: String, val record: DeviceRecord)

val metadataJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

sealed interface DeviceMatch {
    /** [viaAndroidId]: nur über ANDROID_ID erkannt – UUID in device.json sollte aktualisiert werden. */
    data class Recognized(val folder: DeviceFolder, val viaAndroidId: Boolean) : DeviceMatch

    /** Kein eindeutiger Treffer. [candidates] sind vorhandene Ordner, wahrscheinlichste zuerst. */
    data class Unknown(val candidates: List<DeviceFolder>) : DeviceMatch
}

fun matchDevice(identity: LocalIdentity, folders: List<DeviceFolder>): DeviceMatch {
    folders.firstOrNull {
        it.record.deviceUuid == identity.deviceUuid || identity.deviceUuid in it.record.previousUuids
    }?.let { return DeviceMatch.Recognized(it, viaAndroidId = false) }

    val byAndroidId = identity.androidId?.let { id -> folders.filter { it.record.androidId == id } }.orEmpty()
    if (byAndroidId.size == 1) return DeviceMatch.Recognized(byAndroidId.single(), viaAndroidId = true)

    val candidates = folders.sortedWith(
        compareByDescending<DeviceFolder> { it in byAndroidId }
            .thenByDescending { it.record.model == identity.model }
            .thenBy { it.folderName.lowercase() },
    )
    return DeviceMatch.Unknown(candidates)
}

sealed interface FolderNameCheck {
    data class Ok(val name: String) : FolderNameCheck
    data class Invalid(val reason: String) : FolderNameCheck
}

private val forbiddenChars = Regex("""[\\/:*?"<>|\x00-\x1F]""")
const val MAX_FOLDER_NAME_LENGTH = 64

/**
 * Prüft einen vom Nutzer eingegebenen Geräteordnernamen. Die Regeln entsprechen FAT32/exFAT,
 * dem üblichen Dateisystem von USB-Sticks; dort sind Namen nicht case-sensitiv.
 */
fun validateFolderName(raw: String, existingNames: Set<String>): FolderNameCheck {
    val name = raw.trim()
    return when {
        name.isEmpty() -> FolderNameCheck.Invalid("Bitte einen Namen eingeben.")
        name.length > MAX_FOLDER_NAME_LENGTH ->
            FolderNameCheck.Invalid("Höchstens $MAX_FOLDER_NAME_LENGTH Zeichen.")
        forbiddenChars.containsMatchIn(name) ->
            FolderNameCheck.Invalid("Nicht erlaubt sind: \\ / : * ? \" < > |")
        name.endsWith('.') -> FolderNameCheck.Invalid("Der Name darf nicht mit einem Punkt enden.")
        name == "." || name == ".." -> FolderNameCheck.Invalid("Ungültiger Name.")
        existingNames.any { it.equals(name, ignoreCase = true) } ->
            FolderNameCheck.Invalid("Ein Ordner mit diesem Namen existiert bereits.")
        else -> FolderNameCheck.Ok(name)
    }
}

/** Macht aus einem beliebigen Text einen gültigen Ordnernamen (für Vorschläge). */
fun sanitizeFolderName(raw: String): String =
    raw.replace(forbiddenChars, "_").trim().trimEnd('.').take(MAX_FOLDER_NAME_LENGTH).ifEmpty { "Smartphone" }
