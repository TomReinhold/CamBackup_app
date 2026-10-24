package de.trei.cambackup.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class DriveSpace(val freeBytes: Long, val totalBytes: Long)

data class DriveInfo(
    val treeUri: Uri,
    /** Name des Datenträgers, z. B. "SanDisk USB-Laufwerk". */
    val volumeLabel: String,
    /** Gewählter Unterordner auf dem Datenträger ("" = oberste Ebene). */
    val path: String,
    /** true, wenn der Ordner im internen Speicher des Smartphones liegt. */
    val isInternal: Boolean,
    val space: DriveSpace?,
    /** Dateisystem (z. B. "vfat", "exfat"), falls ermittelbar. */
    val fsType: String?,
)

/** Verwaltet das per Storage Access Framework gewählte Speichermedium. */
class DriveRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("drive", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    val selectedTree: Uri?
        get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    /** Übernimmt einen vom Nutzer gewählten Ordner dauerhaft (auch nach Neustart). */
    fun select(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(treeUri, flags)
        selectedTree?.takeIf { it != treeUri }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, flags) }
        }
        prefs.edit().putString(KEY_TREE, treeUri.toString()).apply()
    }

    /**
     * Stammordner des Mediums, oder null, wenn kein Medium gewählt ist, es nicht eingesteckt ist
     * oder die Berechtigung verloren ging.
     */
    fun openRoot(): DocumentFile? {
        val tree = selectedTree ?: return null
        val hasPermission = resolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
        if (!hasPermission) return null
        return DocumentFile.fromTreeUri(context, tree)?.takeIf { runCatching { it.isDirectory }.getOrDefault(false) }
    }

    /** Gehört der eingehängte Pfad (z. B. "/storage/1234-ABCD") zum gewählten Speichermedium? */
    fun isSelectedVolume(mountPath: String?): Boolean {
        val tree = selectedTree ?: return false
        val volumeId = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
        return mountPath?.trimEnd('/')?.substringAfterLast('/').equals(volumeId, ignoreCase = true)
    }

    fun info(treeUri: Uri): DriveInfo {
        val docId = DocumentsContract.getTreeDocumentId(treeUri) // z. B. "1234-ABCD:Backups"
        val volumeId = docId.substringBefore(':')
        val path = docId.substringAfter(':', "")
        val isExternalStorageProvider = treeUri.authority == EXTERNAL_STORAGE_AUTHORITY

        val volume = context.getSystemService(StorageManager::class.java).storageVolumes.firstOrNull {
            if (volumeId == "primary") it.isPrimary else it.uuid.equals(volumeId, ignoreCase = true)
        }
        val dir = volume?.directory
        val space = dir?.let {
            runCatching { StatFs(it.path).let { s -> DriveSpace(s.availableBytes, s.totalBytes) } }.getOrNull()
        }

        return DriveInfo(
            treeUri = treeUri,
            volumeLabel = volume?.getDescription(context) ?: volumeId,
            path = path,
            isInternal = isExternalStorageProvider && (volumeId == "primary" || volume?.isPrimary == true),
            space = space,
            fsType = if (volume?.isPrimary == false) readFsType(volumeId) else null,
        )
    }

    /** Liest das Dateisystem aus /proc/mounts (best effort, nicht auf allen Geräten lesbar). */
    private fun readFsType(volumeId: String): String? = runCatching {
        File("/proc/mounts").readLines()
            .map { it.split(' ') }
            .firstOrNull { it.size > 2 && it[1] == "/mnt/media_rw/$volumeId" }
            ?.get(2)
    }.getOrNull()

    private companion object {
        const val KEY_TREE = "tree_uri"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
