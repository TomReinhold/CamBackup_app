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

val metadataJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Wie gut passt ein Backup-Ordner (laut seiner device.json) zu diesem Smartphone? */
enum class DeviceRelation {
    /** Eindeutig dieses Smartphone (App-UUID bzw. ANDROID_ID stimmen). */
    SAME,
    /**
     * Gleicher Hersteller und gleiches Modell, aber keine passende Kennung – z. B. nach Neuinstallation
     * mit anderem Signaturschlüssel. Nur nach Rückfrage verwendbar.
     */
    SAME_MODEL,
    /** Ein anderes Smartphone – der Ordner darf nicht verwendet werden. */
    OTHER,
}

fun relationOf(identity: LocalIdentity, record: DeviceRecord): DeviceRelation = when {
    record.deviceUuid == identity.deviceUuid || identity.deviceUuid in record.previousUuids -> DeviceRelation.SAME
    identity.androidId != null && record.androidId == identity.androidId -> DeviceRelation.SAME
    record.manufacturer.equals(identity.manufacturer, ignoreCase = true) &&
        record.model.equals(identity.model, ignoreCase = true) -> DeviceRelation.SAME_MODEL
    else -> DeviceRelation.OTHER
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
