package de.trei.cambackup.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.trei.cambackup.backup.BackupPlan
import de.trei.cambackup.backup.BackupProgress
import de.trei.cambackup.backup.BackupRunner
import de.trei.cambackup.backup.LastRunStore
import de.trei.cambackup.backup.RunSummary
import de.trei.cambackup.backup.SpaceCheck
import de.trei.cambackup.work.BackupWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface BackupState {
    data object Preparing : BackupState
    data class Ready(val plan: BackupPlan, val space: SpaceCheck) : BackupState
    data class Running(val progress: BackupProgress?) : BackupState
    data class Finished(val summary: RunSummary) : BackupState
    data class Error(val message: String) : BackupState
}

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val runner = BackupRunner(app)
    private val lastRun = LastRunStore(app)

    private val planState = MutableStateFlow<BackupState>(BackupState.Preparing)
    private var prepareJob: Job? = null

    private val work = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(BackupWorker.UNIQUE_NAME)
        .map { infos -> infos.firstOrNull { !it.state.isFinished } }

    /** Laufender Job hat Vorrang, dann eine unbestätigte Zusammenfassung, sonst die Planung. */
    val state: StateFlow<BackupState> = combine(work, lastRun.unseenFlow(), planState) { running, summary, plan ->
        when {
            running != null -> BackupState.Running(
                running.takeIf { it.state == WorkInfo.State.RUNNING }?.let { BackupWorker.progressOf(it.progress) },
            )
            summary != null -> BackupState.Finished(summary)
            else -> plan
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupState.Preparing)

    /** Automatisches Neuplanen (Bildschirmstart, Rückkehr in die App). */
    fun prepareIfIdle(deviceFolderName: String) {
        if (state.value is BackupState.Running || state.value is BackupState.Finished) return
        prepare(deviceFolderName)
    }

    fun prepare(deviceFolderName: String) {
        if (prepareJob?.isActive == true) return
        prepareJob = viewModelScope.launch {
            planState.value = BackupState.Preparing
            planState.value = try {
                val p = runner.prepare(deviceFolderName)
                BackupState.Ready(p.plan, p.space)
            } catch (e: Exception) {
                BackupState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun start(deviceFolderName: String) {
        val ready = state.value as? BackupState.Ready ?: return
        // Sicherheitsnetz: die UI bietet den Start bei zu wenig Platz gar nicht erst an
        if (ready.space is SpaceCheck.NotEnough || ready.plan.toCopy.isEmpty()) return
        BackupWorker.start(getApplication(), deviceFolderName)
    }

    fun cancel() = BackupWorker.cancel(getApplication())

    /** Zusammenfassung bestätigen und neu planen. */
    fun acknowledge(deviceFolderName: String) {
        lastRun.markSeen()
        prepare(deviceFolderName)
    }
}
