package de.trei.cambackup.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.util.Log
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class DriveSpace(val freeBytes: Long, val totalBytes: Long)

data class DriveInfo(
    val folderUri: Uri,
    /** Name des Datenträgers, z. B. "SanDisk USB-Laufwerk". */
    val volumeLabel: String,
    /** Pfad des Backup-Ordners auf dem Datenträger, z. B. "Backups/Pixel". */
    val path: String,
    /** true, wenn der Ordner im internen Speicher des Smartphones liegt. */
    val isInternal: Boolean,
    val space: DriveSpace?,
    /** Dateisystem (z. B. "vfat", "exfat"), falls ermittelbar. */
    val fsType: String?,
)

/** Ein eingehängter Wechseldatenträger (USB-Stick, SD-Karte). */
data class RemovableVolume(val id: String, val label: String) {
    /** Startpunkt der Ordnerauswahl direkt auf diesem Datenträger. */
    val pickerStart: Uri get() = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$id:")
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * Verwaltet den Backup-Ordner dieses Smartphones auf dem Speichermedium. Zugriffsrechte auf alle
 * einmal gewählten Ordner bleiben erhalten – so findet die App ihren Ordner beim erneuten Einstecken
 * eines bekannten Mediums selbst (siehe [DriveDetector]).
 */
class DriveRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("drive", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    /** Aktiver Backup-Ordner (Dokument-URI innerhalb eines freigegebenen Ordners). */
    val activeFolder: Uri?
        get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)

    /** Dauerhaftes Zugriffsrecht auf einen vom Nutzer gewählten Ordner (übersteht Neustarts). */
    fun takePermission(treeUri: Uri) {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    /** Gibt das Zugriffsrecht wieder ab (z. B. für einen abgelehnten Ordner), sofern nicht der aktive Ordner darin liegt. */
    fun releaseIfUnused(treeUri: Uri) {
        if (activeFolder?.let { treeOf(it) } == treeUri) return
        runCatching {
            resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun activate(folderUri: Uri) {
        prefs.edit().putString(KEY_FOLDER, folderUri.toString()).apply()
    }

    /** Alle freigegebenen Ordner (für die automatische Suche nach dem Backup-Ordner). */
    fun grantedTrees(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && it.isWritePermission }
        .map { it.uri }

    /** Öffnet einen Ordner, wenn er erreichbar ist und das Zugriffsrecht noch besteht. */
    fun open(folderUri: Uri): DocumentFile? {
        if (treeOf(folderUri) !in grantedTrees()) {
            Log.i(TAG, "Kein Zugriffsrecht für $folderUri")
            return null
        }
        val dir = DocumentFile.fromTreeUri(context, folderUri)
        val isDir = runCatching { dir?.isDirectory == true }.onFailure { Log.w(TAG, "Ordner nicht lesbar: $folderUri", it) }.getOrDefault(false)
        if (!isDir) Log.i(TAG, "Ordner nicht erreichbar: $folderUri (${dir?.uri})")
        return dir?.takeIf { isDir }
    }

    /** Öffnet einen Backup-Ordner für Backup/Verkleinern – mit verständlicher Meldung, wenn das nicht geht. */
    fun requireFolder(folderUri: String): DocumentFile {
        val dir = open(Uri.parse(folderUri)) ?: throw java.io.IOException("Speichermedium nicht verbunden")
        if (dir.findFile(DeviceRegistry.METADATA_DIR) == null) throw java.io.IOException("Ordner Metadata fehlt im Backup-Ordner")
        return dir
    }

    /** Der aktive Backup-Ordner, oder null (nicht gewählt, Medium nicht eingesteckt, Zugriff verloren). */
    fun openActive(): DocumentFile? = activeFolder?.let(::open)

    /** Eingehängte Wechseldatenträger. */
    fun removableVolumes(): List<RemovableVolume> =
        context.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED && it.uuid != null }
            .map { RemovableVolume(it.uuid!!, it.getDescription(context)) }

    fun volumeIdOf(uri: Uri): String = documentIdOf(uri).substringBefore(':')

    /** Gehört der eingehängte Pfad (z. B. "/storage/1234-ABCD") zum Medium des aktiven Backup-Ordners? */
    fun isActiveVolume(mountPath: String?): Boolean {
        val folder = activeFolder ?: return false
        return mountPath?.trimEnd('/')?.substringAfterLast('/').equals(volumeIdOf(folder), ignoreCase = true)
    }

    fun info(folderUri: Uri): DriveInfo {
        val docId = documentIdOf(folderUri) // z. B. "1234-ABCD:Backups/Pixel"
        val volumeId = docId.substringBefore(':')
        val volume = volumeById(volumeId)
        val space = volume?.directory?.let {
            runCatching { StatFs(it.path).let { s -> DriveSpace(s.availableBytes, s.totalBytes) } }.getOrNull()
        } ?: fileSpace(folderUri)
        return DriveInfo(
            folderUri = folderUri,
            volumeLabel = volume?.getDescription(context) ?: volumeId,
            path = docId.substringAfter(':', ""),
            isInternal = folderUri.authority == EXTERNAL_STORAGE_AUTHORITY && (volumeId == "primary" || volume?.isPrimary == true),
            space = space,
            fsType = if (volume?.isPrimary == false) readFsType(volumeId) else null,
        )
    }

    private fun volumeById(volumeId: String): StorageVolume? =
        context.getSystemService(StorageManager::class.java).storageVolumes.firstOrNull {
            if (volumeId == "primary") it.isPrimary else it.uuid.equals(volumeId, ignoreCase = true)
        }

    /** Dokument-ID eines Ordners – auch für Unterordner innerhalb eines freigegebenen Ordners. */
    private fun documentIdOf(uri: Uri): String =
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)

    /** Der freigegebene Ordner, zu dem eine (Unter-)Ordner-URI gehört. */
    private fun treeOf(uri: Uri): Uri = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))

    /**
     * Letzter Ausweg: freier Platz des Dateisystems, auf dem die device.json des Backup-Ordners liegt
     * (fstatvfs auf der geöffneten Datei). Samsung meldet für USB-Datenträger weder Pfad noch Platz.
     */
    private fun fileSpace(folderUri: Uri): DriveSpace? = runCatching {
        val file = DocumentFile.fromTreeUri(context, folderUri)
            ?.findFile(DeviceRegistry.METADATA_DIR)
            ?.findFile(DeviceRegistry.DEVICE_FILE)
            ?: return@runCatching null
        context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
            val st = android.system.Os.fstatvfs(pfd.fileDescriptor)
            DriveSpace(st.f_bavail * st.f_frsize, st.f_blocks * st.f_frsize)
        }
    }.getOrNull()

    /**
     * Liest das Dateisystem aus /proc/mounts (best effort, nicht auf allen Geräten lesbar).
     * Samsung meldet seinen Treiber "sdfat" und nennt das eigentliche Dateisystem in der Option "fs=".
     */
    private fun readFsType(volumeId: String): String? = runCatching {
        File("/proc/mounts").readLines()
            .map { it.split(' ') }
            .firstOrNull { it.size > 3 && it[1] == "/mnt/media_rw/$volumeId" }
            ?.let { parts -> parts[3].split(',').firstOrNull { it.startsWith("fs=") }?.substringAfter("fs=") ?: parts[2] }
    }.getOrNull()

    private companion object {
        const val KEY_FOLDER = "device_folder_uri"
        const val TAG = "DriveRepository"
    }
}
