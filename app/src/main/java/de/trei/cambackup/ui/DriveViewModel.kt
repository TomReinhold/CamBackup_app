package de.trei.cambackup.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.trei.cambackup.storage.DeviceFolder
import de.trei.cambackup.storage.DeviceMatch
import de.trei.cambackup.storage.DeviceRegistry
import de.trei.cambackup.storage.DriveInfo
import de.trei.cambackup.storage.DriveRepository
import de.trei.cambackup.storage.LocalIdentity
import de.trei.cambackup.storage.LocalIdentityStore
import de.trei.cambackup.storage.matchDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DriveState {
    data object Loading : DriveState
    data object NotSelected : DriveState
    /** Medium gewählt, aber nicht eingesteckt oder Zugriff verloren. */
    data object Unavailable : DriveState
    /** Gewählter Ordner liegt im internen Speicher – als Backup-Ziel ungeeignet. */
    data class InternalStorage(val info: DriveInfo) : DriveState
    data class Ready(
        val info: DriveInfo,
        val identity: LocalIdentity,
        val match: DeviceMatch,
        val existingNames: Set<String>,
    ) : DriveState
    data class Error(val message: String) : DriveState
}

class DriveViewModel(app: Application) : AndroidViewModel(app) {

    private val drives = DriveRepository(app)
    private val identities = LocalIdentityStore(app)
    private val registry = DeviceRegistry(
        app,
        appVersion = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?",
    )

    private val _state = MutableStateFlow<DriveState>(DriveState.Loading)
    val state: StateFlow<DriveState> = _state.asStateFlow()

    /** Geräteordner, deren lastSeenAt in dieser Sitzung schon aktualisiert wurde. */
    private var claimedFolder: String? = null

    fun refresh() {
        viewModelScope.launch { _state.value = load() }
    }

    fun onTreePicked(uri: Uri?) {
        if (uri == null) return
        runCatching { drives.select(uri) }
            .onFailure { _state.value = DriveState.Error("Zugriff auf den Ordner nicht möglich: ${it.message}") }
            .onSuccess {
                claimedFolder = null
                refresh()
            }
    }

    fun createDevice(folderName: String) = mutate { root, identity ->
        registry.createDevice(root, folderName, identity).also { claimedFolder = it.folderName }
    }

    fun assignExisting(folder: DeviceFolder) = mutate { root, identity ->
        registry.claim(root, folder, identity).also { claimedFolder = it.folderName }
    }

    private fun mutate(action: suspend (root: DocumentFile, LocalIdentity) -> Unit) {
        viewModelScope.launch {
            val root = drives.openRoot() ?: run {
                _state.value = DriveState.Unavailable
                return@launch
            }
            _state.value = DriveState.Loading
            _state.value = try {
                action(root, identities.get())
                load()
            } catch (e: Exception) {
                DriveState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun load(): DriveState {
        val tree = drives.selectedTree ?: return DriveState.NotSelected
        val root = drives.openRoot() ?: return DriveState.Unavailable
        return try {
            val info = drives.info(tree)
            if (info.isInternal) return DriveState.InternalStorage(info)

            val identity = identities.get()
            val scan = registry.scan(root)
            var match = matchDevice(identity, scan.devices)

            // Beim ersten Erkennen pro Sitzung lastSeenAt (und ggf. die UUID nach Neuinstallation) aktualisieren
            if (match is DeviceMatch.Recognized && claimedFolder != match.folder.folderName) {
                val updated = registry.claim(root, match.folder, identity)
                claimedFolder = updated.folderName
                match = match.copy(folder = updated)
            }
            DriveState.Ready(info, identity, match, scan.existingNames)
        } catch (e: Exception) {
            DriveState.Error(e.message ?: e.javaClass.simpleName)
        }
    }
}
