package de.trei.cambackup.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import de.trei.cambackup.index.BackupIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import de.trei.cambackup.util.toMsg
import de.trei.cambackup.util.msg
import de.trei.cambackup.util.L10n
import de.trei.cambackup.util.AppError
import de.trei.cambackup.R

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

    private val io = SafIo(context.contentResolver)

    /** device.json eines Ordners, oder null, wenn er kein Backup-Ordner ist. */
    suspend fun read(dir: DocumentFile): DeviceRecord? = withContext(Dispatchers.IO) {
        dir.findFile(METADATA_DIR)?.let(::readRecordOrNull)
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
        if (read(dir) != null) throw AppError(R.string.err_already_backup_folder)
        val metadata = dir.findFile(METADATA_DIR) ?: dir.createDirectory(METADATA_DIR)
            ?: throw AppError(R.string.err_create_folder, METADATA_DIR)
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
            if (parent.findFile(folderName) != null) throw AppError(R.string.err_folder_exists, folderName)
            val dir = parent.createDirectory(folderName) ?: throw AppError(R.string.err_create_folder, folderName)
            setup(dir, identity, displayName)
        }

    /**
     * Übernimmt einen Backup-Ordner für dieses Smartphone (z. B. nach Neuinstallation der App) bzw.
     * aktualisiert lastSeenAt. Die bisherige UUID wird in previousUuids aufbewahrt.
     */
    suspend fun claim(folder: BackupFolder, identity: LocalIdentity): BackupFolder = withContext(Dispatchers.IO) {
        val metadata = folder.dir.findFile(METADATA_DIR) ?: throw AppError(R.string.err_no_metadata)
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

    /**
     * Stellt eine leere oder beschädigte device.json wieder her. Nur für den aktiven Backup-Ordner
     * dieses Smartphones gedacht: Die App hat ihn früher selbst eingerichtet bzw. geprüft – und der
     * Index im Ordner Metadata belegt, dass es ein Backup-Ordner ist.
     */
    suspend fun repair(dir: DocumentFile, identity: LocalIdentity): BackupFolder? = withContext(Dispatchers.IO) {
        val metadata = dir.findFile(METADATA_DIR) ?: return@withContext null
        if (readRecordOrNull(metadata) != null) return@withContext null
        if (metadata.findFile(BackupIndex.INDEX_FILE) == null && metadata.findFile(BackupIndex.JOURNAL_FILE) == null) return@withContext null
        val now = Instant.now().toString()
        // Zeitpunkt der letzten Sicherung lässt sich aus dem Index ablesen
        val lastBackup = runCatching { BackupIndex.open(io, metadata).state.entries.maxOfOrNull { it.backedUpAt } }.getOrNull()
        val record = DeviceRecord(
            deviceUuid = identity.deviceUuid,
            androidId = identity.androidId,
            manufacturer = identity.manufacturer,
            model = identity.model,
            displayName = "${identity.manufacturer} ${identity.model}",
            createdAt = now,
            lastSeenAt = now,
            lastBackupAt = lastBackup,
            appVersion = appVersion,
        )
        writeRecord(metadata, record)
        runCatching { io.appendText(metadata, "backup_log.txt", "$now  $DEVICE_FILE was damaged and has been recreated\n") }
        BackupFolder(dir, record)
    }

    /** Ändert den in device.json gespeicherten Namen des Smartphones. */
    suspend fun rename(dir: DocumentFile, displayName: String) = withContext(Dispatchers.IO) {
        val metadata = dir.findFile(METADATA_DIR) ?: throw AppError(R.string.err_no_metadata)
        val record = read(dir) ?: throw AppError(R.string.err_device_file_missing, DEVICE_FILE)
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
        val metadata = deviceDir.findFile(METADATA_DIR) ?: throw AppError(R.string.err_no_metadata)
        val record = readRecordOrNull(metadata) ?: throw AppError(R.string.err_device_file_broken, DEVICE_FILE)
        writeRecord(metadata, record.copy(lastBackupAt = at.toString(), lastSeenAt = at.toString()))
    }

    /**
     * Liest device.json. Ist sie leer oder beschädigt (z. B. Medium beim Schreiben abgezogen), gilt die
     * vollständig geschriebene Zwischenkopie "device.json.tmp" aus [SafIo.replaceAtomically].
     */
    private fun readRecordOrNull(metadataDir: DocumentFile): DeviceRecord? =
        listOf(DEVICE_FILE, "$DEVICE_FILE.tmp").firstNotNullOfOrNull { name ->
            metadataDir.findFile(name)?.let { file ->
                runCatching { metadataJson.decodeFromString(DeviceRecord.serializer(), io.readText(file)) }.getOrNull()
            }
        }

    /** Schreibt device.json atomar und per fsync – nie eine halb geschriebene oder leere Datei. */
    private fun writeRecord(metadataDir: DocumentFile, record: DeviceRecord) {
        io.replaceAtomically(metadataDir, DEVICE_FILE, metadataJson.encodeToString(DeviceRecord.serializer(), record))
    }

    companion object {
        const val METADATA_DIR = "Metadata"
        const val DEVICE_FILE = "device.json"
        const val NO_MEDIA = ".nomedia"

        fun appVersion(context: Context): String =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }
}
