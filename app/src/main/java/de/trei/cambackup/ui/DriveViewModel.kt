package de.trei.cambackup.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.trei.cambackup.storage.BackupFolder
import de.trei.cambackup.storage.DetectResult
import de.trei.cambackup.storage.DeviceRecord
import de.trei.cambackup.storage.DeviceRegistry
import de.trei.cambackup.storage.DeviceRelation
import de.trei.cambackup.storage.DriveDetector
import de.trei.cambackup.storage.DriveInfo
import de.trei.cambackup.storage.DriveRepository
import de.trei.cambackup.storage.FolderCheck
import de.trei.cambackup.storage.LocalIdentityStore
import de.trei.cambackup.storage.RemovableVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DriveState {
    data object Loading : DriveState
    /**
     * Kein erreichbarer Backup-Ordner. [hadFolder]: es gab schon einmal einen (Medium nicht eingesteckt).
     * [newVolume]: ein eingestecktes, noch unbekanntes Medium – dort kann die Auswahl direkt starten.
     */
    data class NoFolder(val hadFolder: Boolean, val newVolume: RemovableVolume?) : DriveState
    data class Ready(val info: DriveInfo, val record: DeviceRecord) : DriveState
    data class Error(val message: String) : DriveState
}

/** Rückfragen nach der Auswahl eines Ordners. */
sealed interface PickDialog {
    data object Checking : PickDialog
    data class Rejected(val title: String, val message: String) : PickDialog
    /** Gleiches Modell, aber keine passende Kennung: nur verwenden, wenn es wirklich dieses Smartphone ist. */
    data class ConfirmSameModel(val folder: BackupFolder) : PickDialog
    /** Im gewählten Ordner liegt der Backup-Ordner dieses Smartphones. */
    data class FoundInside(val folder: BackupFolder, val parentName: String) : PickDialog
    /** Noch kein Backup-Ordner: hier einrichten oder Unterordner anlegen. */
    data class Setup(
        val dir: DocumentFile,
        val isEmpty: Boolean,
        val otherDevices: List<BackupFolder>,
        val existingNames: Set<String>,
        val suggestion: String,
    ) : PickDialog
}

class DriveViewModel(app: Application) : AndroidViewModel(app) {

    private val drives = DriveRepository(app)
    private val identities = LocalIdentityStore(app)
    private val detector = DriveDetector(app)
    private val registry = DeviceRegistry(app, DeviceRegistry.appVersion(app))

    private val _state = MutableStateFlow<DriveState>(DriveState.Loading)
    val state: StateFlow<DriveState> = _state.asStateFlow()

    private val _pick = MutableStateFlow<PickDialog?>(null)
    val pick: StateFlow<PickDialog?> = _pick.asStateFlow()

    /** Hinweis, wenn der Backup-Ordner automatisch gefunden wurde. */
    private val _autoFound = MutableStateFlow<String?>(null)
    val autoFound: StateFlow<String?> = _autoFound.asStateFlow()

    /** Zuletzt gewählter (noch nicht übernommener) Ordner – wird bei Abbruch wieder freigegeben. */
    private var pendingTree: Uri? = null

    fun refresh() {
        viewModelScope.launch {
            _state.value = try {
                when (val r = detector.detect()) {
                    is DetectResult.Active -> ready(r.folder)
                    is DetectResult.Activated -> {
                        _autoFound.value = "Backup-Ordner „${r.folder.record.displayName}“ auf ${r.volumeLabel} automatisch erkannt"
                        ready(r.folder)
                    }
                    is DetectResult.NotFound -> DriveState.NoFolder(hadFolder = drives.activeFolder != null, newVolume = r.newVolume)
                }
            } catch (e: Exception) {
                DriveState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun dismissAutoFound() {
        _autoFound.value = null
    }

    private fun ready(folder: BackupFolder) = DriveState.Ready(drives.info(folder.dir.uri), folder.record)

    /** Prüft den gewählten Ordner, bevor er verwendet wird. */
    fun onTreePicked(tree: Uri?) {
        if (tree == null) return
        viewModelScope.launch {
            _pick.value = PickDialog.Checking
            _pick.value = try {
                drives.takePermission(tree)
                pendingTree = tree
                val dir = DocumentFile.fromTreeUri(getApplication(), tree) ?: error("Ordner nicht lesbar")
                val identity = identities.get()
                if (drives.info(tree).isInternal) {
                    reject(
                        "Interner Speicher",
                        "Dieser Ordner liegt im internen Speicher des Smartphones und ist als Backup-Ziel ungeeignet. " +
                            "Bitte einen Ordner auf dem USB-Stick oder der SD-Karte wählen.",
                    )
                } else when (val c = registry.check(dir, identity)) {
                    is FolderCheck.Own -> {
                        use(c.folder)
                        null
                    }
                    is FolderCheck.SameModel -> PickDialog.ConfirmSameModel(c.folder)
                    is FolderCheck.Foreign -> reject(
                        "Ordner gehört zu einem anderen Smartphone",
                        "„${c.record.displayName}“ (${c.record.manufacturer} ${c.record.model}) sichert in diesen Ordner. " +
                            "Bitte einen anderen Ordner wählen oder einen neuen anlegen.",
                    )
                    is FolderCheck.ContainsMatch ->
                        if (c.relation == DeviceRelation.SAME) PickDialog.FoundInside(c.folder, dir.name ?: "")
                        else PickDialog.ConfirmSameModel(c.folder)
                    is FolderCheck.Unused -> PickDialog.Setup(c.dir, c.isEmpty, c.otherDevices, c.existingNames, identity.suggestedFolderName)
                }
            } catch (e: Exception) {
                reject("Ordner nicht verwendbar", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun reject(title: String, message: String): PickDialog {
        pendingTree?.let(drives::releaseIfUnused)
        pendingTree = null
        return PickDialog.Rejected(title, message)
    }

    /** Übernimmt einen (bestätigten) Backup-Ordner dieses Smartphones. */
    fun confirm(folder: BackupFolder) = action { use(folder) }

    /** Richtet den gewählten Ordner selbst als Backup-Ordner ein. */
    fun setupHere(dir: DocumentFile, phoneName: String) = action {
        use(registry.setup(dir, identities.get(), phoneName))
    }

    /** Legt im gewählten Ordner einen Unterordner an und richtet ihn ein. */
    fun setupSubfolder(parent: DocumentFile, folderName: String, phoneName: String) = action {
        use(registry.createAndSetup(parent, folderName, identities.get(), phoneName))
    }

    fun dismissPick() {
        pendingTree?.let(drives::releaseIfUnused)
        pendingTree = null
        _pick.value = null
    }

    /** Ändert den Namen des Smartphones in den Metadaten. */
    fun rename(phoneName: String) {
        viewModelScope.launch {
            val dir = drives.openActive() ?: return@launch
            runCatching { registry.rename(dir, phoneName) }
            refresh()
        }
    }

    private suspend fun use(folder: BackupFolder) {
        val claimed = registry.claim(folder, identities.get())
        drives.activate(claimed.dir.uri)
        pendingTree = null
        _autoFound.value = null
        _state.value = ready(claimed)
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            _pick.value = PickDialog.Checking
            _pick.value = try {
                block()
                null
            } catch (e: Exception) {
                reject("Einrichten fehlgeschlagen", e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
