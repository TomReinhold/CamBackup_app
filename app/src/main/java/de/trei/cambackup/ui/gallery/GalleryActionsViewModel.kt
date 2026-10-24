package de.trei.cambackup.ui.gallery

import android.app.Application
import android.app.PendingIntent
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import de.trei.cambackup.backup.BackupRunner
import de.trei.cambackup.freeup.FreeUpRunner
import de.trei.cambackup.library.LibraryItem
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.work.RestoreWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Aktionen für ausgewählte Dateien in der Galerie: gezielt zurückholen bzw. vom Verkleinern ausschließen. */
class GalleryActionsViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    private val freeUp = FreeUpRunner(app)
    private val _message = MutableStateFlow<String?>(null)
    /** Kurze Rückmeldung für den Nutzer (wird nach dem Anzeigen gelöscht). */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    fun setKeepOriginal(folderUri: String, items: List<LibraryItem>, keep: Boolean) {
        val paths = items.mapNotNull { it.entry?.backupPath }.toSet()
        viewModelScope.launch {
            // Nicht parallel zu einem laufenden Backup oder Ersetzen in den Index schreiben
            if (!BackupRunner.lock.tryLock()) {
                _message.value = "Gerade läuft ein Vorgang – bitte danach erneut versuchen"
                return@launch
            }
            _message.value = try {
                val n = freeUp.setKeepOriginal(folderUri, paths, keep)
                if (keep) "${fileCount(n)} vom Verkleinern ausgeschlossen" else "${fileCount(n)} wieder zum Verkleinern freigegeben"
            } catch (e: Exception) {
                "Fehler: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                BackupRunner.lock.unlock()
            }
        }
    }

    /**
     * System-Dialog, in dem der Nutzer dem Ändern genau dieser Dateien zustimmt. Die Auswahl übersteht
     * im SavedStateHandle auch ein Beenden der App, während der Dialog offen ist.
     */
    fun restoreRequest(folderUri: String, items: List<LibraryItem>, keepOriginal: Boolean): PendingIntent {
        saved[KEY_FOLDER] = folderUri
        saved[KEY_IDS] = items.map { it.media.id }.toLongArray()
        saved[KEY_KEEP] = keepOriginal
        return MediaStore.createWriteRequest(getApplication<Application>().contentResolver, items.map { it.media.uri })
    }

    fun restoreApproved() {
        val folder = saved.get<String>(KEY_FOLDER) ?: return
        val ids = saved.get<LongArray>(KEY_IDS) ?: return
        RestoreWorker.start(getApplication(), folder, saved.get<Boolean>(KEY_KEEP) ?: true, ids)
        _message.value = "${fileCount(ids.size)} werden zurückgeholt – Fortschritt in der Benachrichtigung"
    }

    private companion object {
        const val KEY_FOLDER = "restore_folder"
        const val KEY_IDS = "restore_ids"
        const val KEY_KEEP = "restore_keep"
    }
}
