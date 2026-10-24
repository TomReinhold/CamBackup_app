package de.trei.cambackup.storage

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface DetectResult {
    /** Der aktive Backup-Ordner ist erreichbar. */
    data class Active(val folder: BackupFolder) : DetectResult
    /** Ein passender Ordner wurde (auf einem gerade eingesteckten Medium) gefunden und aktiviert. */
    data class Activated(val folder: BackupFolder, val volumeLabel: String) : DetectResult
    /**
     * Nichts gefunden. [newVolume]: ein eingestecktes Medium, auf das die App noch keinen Zugriff hat –
     * die Ordnerauswahl kann direkt dort starten.
     */
    data class NotFound(val newVolume: RemovableVolume?) : DetectResult
}

/**
 * Findet den Backup-Ordner dieses Smartphones automatisch. Android erlaubt nur den Blick in Ordner,
 * die der Nutzer einmal freigegeben hat – diese werden durchsucht (der Ordner selbst und seine
 * Unterordner). Ein unbekanntes Medium muss einmal per Ordnerauswahl freigegeben werden.
 */
class DriveDetector(private val context: Context) {

    private val drives = DriveRepository(context)
    private val identities = LocalIdentityStore(context)
    private val registry = DeviceRegistry(context, DeviceRegistry.appVersion(context))

    suspend fun detect(): DetectResult = withContext(Dispatchers.IO) {
        val identity = identities.get()
        drives.openActive()?.let { dir ->
            val record = registry.read(dir)
            if (record != null && relationOf(identity, record) == DeviceRelation.SAME) {
                return@withContext DetectResult.Active(BackupFolder(dir, record))
            }
        }
        for (tree in drives.grantedTrees()) {
            val dir = DocumentFile.fromTreeUri(context, tree)?.takeIf { runCatching { it.isDirectory }.getOrDefault(false) } ?: continue
            val found = when (val c = registry.check(dir, identity)) {
                is FolderCheck.Own -> c.folder
                is FolderCheck.ContainsMatch -> c.folder.takeIf { c.relation == DeviceRelation.SAME }
                else -> null
            } ?: continue
            val claimed = registry.claim(found, identity)
            drives.activate(claimed.dir.uri)
            return@withContext DetectResult.Activated(claimed, drives.info(claimed.dir.uri).volumeLabel)
        }
        val known = drives.grantedTrees().map { drives.volumeIdOf(it) }.toSet()
        DetectResult.NotFound(drives.removableVolumes().firstOrNull { it.id !in known })
    }
}
