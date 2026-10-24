package de.trei.cambackup.ui.gallery

import android.app.Application
import android.app.PendingIntent
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import de.trei.cambackup.backup.BackupRunner
import de.trei.cambackup.compress.TrialCompressor
import de.trei.cambackup.util.formatBytes
import de.trei.cambackup.freeup.FreeUpRunner
import de.trei.cambackup.library.LibraryItem
import de.trei.cambackup.R
import de.trei.cambackup.util.L10n
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.toMsg
import de.trei.cambackup.work.RestoreWorker
import de.trei.cambackup.work.JobControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
                _message.value = L10n.str(R.string.gallery_busy)
                return@launch
            }
            _message.value = try {
                val n = freeUp.setKeepOriginal(folderUri, paths, keep)
                L10n.str(if (keep) R.string.gallery_excluded_msg else R.string.gallery_included_msg, fileCount(n))
            } catch (e: Exception) {
                L10n.str(R.string.error_prefix, e.toMsg().ui)
            } finally {
                BackupRunner.lock.unlock()
            }
        }
    }

    private val _trialRunning = MutableStateFlow(false)
    /** Läuft gerade eine Probe-Verkleinerung? */
    val trialRunning: StateFlow<Boolean> = _trialRunning.asStateFlow()

    /** Verkleinert [items] zur Probe in das Album „CamBackup Probe“ – die Originale bleiben unverändert. */
    fun trial(items: List<LibraryItem>) {
        if (_trialRunning.value) return
        val media = items.take(MAX_TRIAL).map { it.media }
        _trialRunning.value = true
        JobControl.setPaused(JobControl.TRIAL, false)
        _trialProgress.value = 0 to media.size
        if (items.size > MAX_TRIAL) _message.value = L10n.str(R.string.gallery_trial_limited, MAX_TRIAL)
        trialJob = viewModelScope.launch {
            _message.value = try {
                val r = TrialCompressor(getApplication()).run(media) { done, total -> _trialProgress.value = done to total }
                buildString {
                    if (r.created > 0) {
                        append(L10n.str(R.string.gallery_trial_done, fileCount(r.created), formatBytes(r.bytesBefore), formatBytes(r.bytesAfter)))
                        append(" ").append(L10n.str(R.string.gallery_trial_where, TrialCompressor.ALBUM))
                    } else {
                        append(L10n.str(R.string.gallery_trial_none))
                    }
                    r.skipped.forEach { (reason, n) -> append(" $reason: $n.") }
                    if (r.failures > 0) append(" ").append(L10n.str(R.string.failures_header, fileCount(r.failures)))
                }
            } catch (e: CancellationException) {
                // Bereits erstellte Proben bleiben im Album
                L10n.str(R.string.gallery_trial_cancelled)
            } catch (e: Exception) {
                L10n.str(R.string.error_prefix, e.toMsg().ui)
            } finally {
                _trialRunning.value = false
                _trialProgress.value = null
            }
        }
    }

    private var trialJob: Job? = null
    private val _trialProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Fortschritt der Probe-Verkleinerung (erledigt, gesamt). */
    val trialProgress: StateFlow<Pair<Int, Int>?> = _trialProgress.asStateFlow()
    val trialPaused: StateFlow<Boolean> = JobControl.paused(JobControl.TRIAL)

    fun setTrialPaused(paused: Boolean) = JobControl.setPaused(JobControl.TRIAL, paused)

    fun cancelTrial() {
        JobControl.setPaused(JobControl.TRIAL, false)
        trialJob?.cancel()
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
        _message.value = L10n.str(R.string.gallery_restore_started, fileCount(ids.size))
    }

    private companion object {
        const val KEY_FOLDER = "restore_folder"
        const val KEY_IDS = "restore_ids"
        const val KEY_KEEP = "restore_keep"
        const val MAX_TRIAL = 30
    }
}
