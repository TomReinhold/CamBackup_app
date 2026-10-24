package de.trei.cambackup.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/** Ein Backup-Ordner samt seiner device.json. */
data class BackupFolder(val dir: DocumentFile, val record: DeviceRecord)

/** Ergebnis der Prüfung eines vom Nutzer gewählten Ordners. */
sealed interface FolderCheck {
    /** Backup-Ordner dieses Smartphones – direkt verwendbar. */
    data class Own(val folder: BackupFolder) : FolderCheck
    /** Backup-Ordner eines Smartphones gleichen Modells – nur nach Rückfrage. */
    data class SameModel(val folder: BackupFolder) : FolderCheck
    /** Gehört einem anderen Smartphone – nicht verwendbar. */
    data class Foreign(val record: DeviceRecord) : FolderCheck
    /** Kein Backup-Ordner, enthält aber (auch) einen passenden Unterordner. */
    data class ContainsMatch(val folder: BackupFolder, val relation: DeviceRelation) : FolderCheck
    /**
     * Kein Backup-Ordner und kein passender Unterordner. [isEmpty]: hier kann direkt eingerichtet werden;
     * [otherDevices]: Unterordner anderer Smartphones (dann besser einen eigenen Unterordner anlegen).
     */
    data class Unused(val dir: DocumentFile, val isEmpty: Boolean, val otherDevices: List<BackupFolder>, val existingNames: Set<String>) : FolderCheck
}

/**
 * Liest und schreibt `Metadata/device.json` im Backup-Ordner. Der Ordnername ist frei wählbar;
 * welchem Smartphone ein Ordner gehört, steht allein in der device.json.
 */
class DeviceRegistry(private val context: Context, private val appVersion: String) {

    /** device.json eines Ordners, oder null, wenn er kein Backup-Ordner ist. */
    suspend fun read(dir: DocumentFile): DeviceRecord? = withContext(Dispatchers.IO) {
        dir.findFile(METADATA_DIR)?.findFile(DEVICE_FILE)?.let { runCatching { readRecord(it) }.getOrNull() }
    }

    /** Prüft einen gewählten Ordner – und ggf. seine direkten Unterordner – auf Zugehörigkeit. */
    suspend fun check(dir: DocumentFile, identity: LocalIdentity): FolderCheck = withContext(Dispatchers.IO) {
        read(dir)?.let { record ->
            val folder = BackupFolder(dir, record)
            return@withContext when (relationOf(identity, record)) {
                DeviceRelation.SAME -> FolderCheck.Own(folder)
                DeviceRelation.SAME_MODEL -> FolderCheck.SameModel(folder)
                DeviceRelation.OTHER -> FolderCheck.Foreign(record)
            }
        }
        val children = dir.listFiles()
        val subfolders = children.filter { it.isDirectory }.mapNotNull { child -> read(child)?.let { BackupFolder(child, it) } }
        // Eindeutig eigener Unterordner vor einem gleichen Modell
        subfolders.firstOrNull { relationOf(identity, it.record) == DeviceRelation.SAME }
            ?.let { return@withContext FolderCheck.ContainsMatch(it, DeviceRelation.SAME) }
        subfolders.firstOrNull { relationOf(identity, it.record) == DeviceRelation.SAME_MODEL }
            ?.let { return@withContext FolderCheck.ContainsMatch(it, DeviceRelation.SAME_MODEL) }
        FolderCheck.Unused(
            dir = dir,
            isEmpty = children.none { it.name != null && !it.name!!.startsWith(".") },
            otherDevices = subfolders,
            existingNames = children.mapNotNull { it.name }.toSet(),
        )
    }

    /** Richtet [dir] als Backup-Ordner dieses Smartphones ein (Metadata/device.json, .nomedia). */
    suspend fun setup(dir: DocumentFile, identity: LocalIdentity, displayName: String): BackupFolder = withContext(Dispatchers.IO) {
        if (read(dir) != null) throw IOException("Ordner ist bereits ein Backup-Ordner")
        val metadata = dir.findFile(METADATA_DIR) ?: dir.createDirectory(METADATA_DIR)
            ?: throw IOException("Ordner „$METADATA_DIR“ konnte nicht angelegt werden")
        val now = Instant.now().toString()
        val record = DeviceRecord(
            deviceUuid = identity.deviceUuid,
            androidId = identity.androidId,
            manufacturer = identity.manufacturer,
            model = identity.model,
            displayName = displayName,
            createdAt = now,
            lastSeenAt = now,
            appVersion = appVersion,
        )
        writeRecord(metadata, record)
        ensureNoMedia(dir)
        BackupFolder(dir, record)
    }

    /** Legt in [parent] einen neuen Unterordner [folderName] an und richtet ihn ein. */
    suspend fun createAndSetup(parent: DocumentFile, folderName: String, identity: LocalIdentity, displayName: String): BackupFolder =
        withContext(Dispatchers.IO) {
            if (parent.findFile(folderName) != null) throw IOException("Ordner „$folderName“ existiert bereits")
            val dir = parent.createDirectory(folderName) ?: throw IOException("Ordner „$folderName“ konnte nicht angelegt werden")
            setup(dir, identity, displayName)
        }

    /**
     * Übernimmt einen Backup-Ordner für dieses Smartphone (z. B. nach Neuinstallation der App) bzw.
     * aktualisiert lastSeenAt. Die bisherige UUID wird in previousUuids aufbewahrt.
     */
    suspend fun claim(folder: BackupFolder, identity: LocalIdentity): BackupFolder = withContext(Dispatchers.IO) {
        val metadata = folder.dir.findFile(METADATA_DIR) ?: throw IOException("Metadaten nicht gefunden")
        val old = folder.record
        val previous = if (old.deviceUuid != identity.deviceUuid) (old.previousUuids + old.deviceUuid).distinct() else old.previousUuids
        val updated = old.copy(
            deviceUuid = identity.deviceUuid,
            androidId = identity.androidId,
            manufacturer = identity.manufacturer,
            model = identity.model,
            lastSeenAt = Instant.now().toString(),
            appVersion = appVersion,
            previousUuids = previous - identity.deviceUuid,
        )
        writeRecord(metadata, updated)
        folder.copy(record = updated)
    }

    /** Ändert den in device.json gespeicherten Namen des Smartphones. */
    suspend fun rename(dir: DocumentFile, displayName: String) = withContext(Dispatchers.IO) {
        val metadata = dir.findFile(METADATA_DIR) ?: throw IOException("Metadaten nicht gefunden")
        val record = read(dir) ?: throw IOException("$DEVICE_FILE fehlt")
        writeRecord(metadata, record.copy(displayName = displayName))
    }

    /**
     * Legt ".nomedia" im Backup-Ordner an: Android indiziert die Sicherungskopien dann nicht, sonst
     * erschienen sie bei eingestecktem Medium doppelt in Galerie-Apps.
     */
    fun ensureNoMedia(deviceDir: DocumentFile) {
        if (deviceDir.findFile(NO_MEDIA) == null) runCatching { deviceDir.createFile(SafIo.OCTET_STREAM, NO_MEDIA) }
    }

    /** Trägt den Zeitpunkt der letzten erfolgreichen Sicherung in device.json ein. */
    suspend fun markBackup(deviceDir: DocumentFile, at: Instant) = withContext(Dispatchers.IO) {
        val metadata = deviceDir.findFile(METADATA_DIR) ?: throw IOException("Ordner „$METADATA_DIR“ fehlt")
        val file = metadata.findFile(DEVICE_FILE) ?: throw IOException("$DEVICE_FILE fehlt")
        val record = readRecord(file)
        writeRecord(metadata, record.copy(lastBackupAt = at.toString(), lastSeenAt = at.toString()))
    }

    private fun readRecord(file: DocumentFile): DeviceRecord {
        val text = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().decodeToString() }
            ?: throw IOException("${file.name} nicht lesbar")
        return metadataJson.decodeFromString(DeviceRecord.serializer(), text)
    }

    private fun writeRecord(metadataDir: DocumentFile, record: DeviceRecord) {
        val text = metadataJson.encodeToString(DeviceRecord.serializer(), record)
        val file = metadataDir.findFile(DEVICE_FILE)
            ?: metadataDir.createFile("application/json", DEVICE_FILE)
            ?: throw IOException("$DEVICE_FILE konnte nicht angelegt werden")
        // "wt" = überschreiben und kürzen
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.encodeToByteArray()) }
            ?: throw IOException("$DEVICE_FILE nicht beschreibbar")
    }

    companion object {
        const val METADATA_DIR = "Metadata"
        const val DEVICE_FILE = "device.json"
        const val NO_MEDIA = ".nomedia"

        fun appVersion(context: Context): String =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }
}
