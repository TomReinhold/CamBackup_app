package de.trei.cambackup.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.trei.cambackup.backup.BackupPlan
import de.trei.cambackup.backup.BackupProgress
import de.trei.cambackup.backup.BackupRunner
import de.trei.cambackup.backup.LastRunStore
import de.trei.cambackup.backup.MissingAction
import de.trei.cambackup.backup.MissingDecisions
import de.trei.cambackup.backup.MissingItem
import de.trei.cambackup.backup.MissingStore
import de.trei.cambackup.backup.MissingSummary
import de.trei.cambackup.backup.RunSummary
import de.trei.cambackup.backup.SpaceCheck
import de.trei.cambackup.work.BackupWorker
import de.trei.cambackup.work.JobControl
import de.trei.cambackup.work.MissingWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import de.trei.cambackup.util.toMsg

sealed interface BackupState {
    data object Preparing : BackupState
    /** Vergleich vom Nutzer abgebrochen. */
    data object Cancelled : BackupState
    data class Ready(
        val plan: BackupPlan,
        val space: SpaceCheck,
        val missing: List<MissingItem>,
        val missingUris: Map<String, Uri>,
    ) : BackupState
    data class Running(val progress: BackupProgress?) : BackupState
    /** Gelöschte Dateien werden wiederhergestellt bzw. ihre Sicherungen gelöscht. */
    data class ApplyingMissing(val done: Int, val total: Int) : BackupState
    data class Error(val message: String) : BackupState
}

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val runner = BackupRunner(app)
    private val lastRun = LastRunStore(app)
    private val missingStore = MissingStore.get(app)

    private val planState = MutableStateFlow<BackupState>(BackupState.Preparing)
    private var prepareJob: Job? = null

    private fun running(name: String) = WorkManager.getInstance(getApplication())
        .getWorkInfosForUniqueWorkFlow(name)
        .map { infos -> infos.firstOrNull { !it.state.isFinished } }

    private val work = combine(running(BackupWorker.UNIQUE_NAME), running(MissingWorker.UNIQUE_NAME)) { b, m -> b to m }

    /** Laufende Jobs haben Vorrang, sonst die Planung. */
    val state: StateFlow<BackupState> = combine(work, planState) { (backup, missing), plan ->
        when {
            backup != null -> BackupState.Running(
                backup.takeIf { it.state == WorkInfo.State.RUNNING }?.let { BackupWorker.progressOf(it.progress) },
            )
            missing != null -> {
                val p = MissingWorker.progressOf(missing.progress)
                BackupState.ApplyingMissing(p?.first ?: 0, p?.second ?: 0)
            }
            else -> plan
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupState.Preparing)

    /** Ergebnis des letzten Laufs – steht über der Planung, bis es nicht mehr gebraucht wird. */
    val result: StateFlow<RunSummary?> = lastRun.unseenFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val missingResult: StateFlow<MissingSummary?> = missingStore.summary

    /** Automatisches Neuplanen (Bildschirmstart, Rückkehr in die App). */
    fun prepareIfIdle(folderUri: String) {
        val s = state.value
        if (s is BackupState.Running || s is BackupState.ApplyingMissing) return
        prepare(folderUri)
    }

    /**
     * Ergebnisse ausblenden, wenn die App verlassen wird. Ein unterbrochenes Backup bleibt stehen,
     * bis es fortgesetzt wird (auch die Benachrichtigung beim Einstecken bietet dann das Fortsetzen an).
     */
    /** Welche Laufergebnisse die Oberfläche schon zum Neuplanen genutzt hat. */
    var handledFinish: String? = null

    fun dismissCompleted() {
        if (lastRun.unseen()?.abortReason == null) lastRun.markSeen()
        missingStore.saveSummary(null)
    }

    private fun clearResults() {
        lastRun.markSeen()
        missingStore.saveSummary(null)
    }

    fun prepare(folderUri: String) {
        if (prepareJob?.isActive == true) return
        prepareJob = viewModelScope.launch {
            planState.value = BackupState.Preparing
            planState.value = try {
                val p = runner.prepare(folderUri)
                BackupState.Ready(p.plan, p.space, p.missing, p.missingUris)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupState.Error(e.toMsg().ui)
            }
        }
    }

    fun start(folderUri: String) {
        startIfPossible(folderUri, state.value as? BackupState.Ready ?: return)
    }

    private fun startIfPossible(folderUri: String, ready: BackupState.Ready) {
        // Sicherheitsnetz: die UI bietet den Start bei zu wenig Platz gar nicht erst an
        if (ready.space is SpaceCheck.NotEnough || !ready.plan.hasWork) return
        clearResults()
        BackupWorker.start(getApplication(), folderUri)
    }

    fun cancel() {
        JobControl.setPaused(JobControl.BACKUP, false)
        BackupWorker.cancel(getApplication())
    }

    val backupPaused: StateFlow<Boolean> = JobControl.paused(JobControl.BACKUP)
    val missingPaused: StateFlow<Boolean> = JobControl.paused(JobControl.MISSING)

    fun setBackupPaused(paused: Boolean) = JobControl.setPaused(JobControl.BACKUP, paused)
    fun setMissingPaused(paused: Boolean) = JobControl.setPaused(JobControl.MISSING, paused)

    fun cancelMissing() {
        JobControl.setPaused(JobControl.MISSING, false)
        WorkManager.getInstance(getApplication()).cancelUniqueWork(MissingWorker.UNIQUE_NAME)
    }

    /** Vergleich von Medienordnern und Speichermedium abbrechen. */
    fun cancelPrepare() {
        prepareJob?.cancel()
        planState.value = BackupState.Cancelled
    }

    /** Entscheidungen zu gelöschten Dateien umsetzen (Pfade ohne Entscheidung bleiben unberührt). */
    fun applyMissing(folderUri: String, actions: Map<String, MissingAction>) {
        if (actions.isEmpty()) return
        clearResults()
        MissingWorker.start(getApplication(), MissingDecisions(folderUri, actions))
    }
}
