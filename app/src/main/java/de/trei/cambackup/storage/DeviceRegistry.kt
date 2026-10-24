package de.trei.cambackup.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/** Liest und schreibt die Geräteordner samt `Metadata/device.json` auf dem Speichermedium. */
class DeviceRegistry(private val context: Context, private val appVersion: String) {

    data class ScanResult(
        val devices: List<DeviceFolder>,
        /** Namen aller Einträge im Stammordner – für die Namensprüfung. */
        val existingNames: Set<String>,
    )

    suspend fun scan(root: DocumentFile): ScanResult = withContext(Dispatchers.IO) {
        val children = root.listFiles()
        val devices = children.filter { it.isDirectory }.mapNotNull { dir ->
            val file = dir.findFile(METADATA_DIR)?.findFile(DEVICE_FILE) ?: return@mapNotNull null
            runCatching { DeviceFolder(dir.name!!, readRecord(file)) }.getOrNull()
        }
        ScanResult(devices, children.mapNotNull { it.name }.toSet())
    }

    /** Legt `<root>/<folderName>/Metadata/device.json` für dieses Smartphone an. */
    suspend fun createDevice(root: DocumentFile, folderName: String, identity: LocalIdentity): DeviceFolder =
        withContext(Dispatchers.IO) {
            if (root.findFile(folderName) != null) throw IOException("Ordner „$folderName“ existiert bereits")
            val dir = root.createDirectory(folderName) ?: throw IOException("Ordner „$folderName“ konnte nicht angelegt werden")
            val metadata = dir.createDirectory(METADATA_DIR) ?: throw IOException("Ordner „$METADATA_DIR“ konnte nicht angelegt werden")
            val now = Instant.now().toString()
            val record = DeviceRecord(
                deviceUuid = identity.deviceUuid,
                androidId = identity.androidId,
                manufacturer = identity.manufacturer,
                model = identity.model,
                displayName = folderName,
                createdAt = now,
                lastSeenAt = now,
                appVersion = appVersion,
            )
            writeRecord(metadata, record)
            ensureNoMedia(dir)
            DeviceFolder(dir.name ?: folderName, record)
        }

    /**
     * Ordnet einen vorhandenen Geräteordner diesem Smartphone zu (z. B. nach Neuinstallation der App)
     * bzw. aktualisiert lastSeenAt. Die bisherige UUID wird in previousUuids aufbewahrt.
     */
    suspend fun claim(root: DocumentFile, folder: DeviceFolder, identity: LocalIdentity): DeviceFolder =
        withContext(Dispatchers.IO) {
            val metadata = root.findFile(folder.folderName)?.findFile(METADATA_DIR)
                ?: throw IOException("Metadaten von „${folder.folderName}“ nicht gefunden")
            val old = folder.record
            val previous = if (old.deviceUuid != identity.deviceUuid) {
                (old.previousUuids + old.deviceUuid).distinct()
            } else {
                old.previousUuids
            }
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

    /**
     * Legt ".nomedia" im Geräteordner an: Android indiziert die Sicherungskopien dann nicht, sonst
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
    }
}
