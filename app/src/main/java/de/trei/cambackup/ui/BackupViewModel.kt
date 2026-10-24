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
import de.trei.cambackup.work.MissingWorker
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
    data class Ready(
        val plan: BackupPlan,
        val space: SpaceCheck,
        val missing: List<MissingItem>,
        val missingUris: Map<String, Uri>,
    ) : BackupState
    data class Running(val progress: BackupProgress?) : BackupState
    data class Finished(val summary: RunSummary) : BackupState
    /** Gelöschte Dateien werden wiederhergestellt bzw. ihre Sicherungen gelöscht. */
    data class ApplyingMissing(val done: Int, val total: Int) : BackupState
    data class MissingFinished(val summary: MissingSummary) : BackupState
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

    /** Laufende Jobs haben Vorrang, dann unbestätigte Zusammenfassungen, sonst die Planung. */
    val state: StateFlow<BackupState> = combine(work, lastRun.unseenFlow(), missingStore.summary, planState) { (backup, missing), summary, missingSummary, plan ->
        when {
            backup != null -> BackupState.Running(
                backup.takeIf { it.state == WorkInfo.State.RUNNING }?.let { BackupWorker.progressOf(it.progress) },
            )
            missing != null -> {
                val p = MissingWorker.progressOf(missing.progress)
                BackupState.ApplyingMissing(p?.first ?: 0, p?.second ?: 0)
            }
            summary != null -> BackupState.Finished(summary)
            missingSummary != null -> BackupState.MissingFinished(missingSummary)
            else -> plan
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupState.Preparing)

    /** Automatisches Neuplanen (Bildschirmstart, Rückkehr in die App). */
    fun prepareIfIdle(folderUri: String) {
        val s = state.value
        if (s is BackupState.Running || s is BackupState.Finished || s is BackupState.ApplyingMissing || s is BackupState.MissingFinished) return
        prepare(folderUri)
    }

    fun prepare(folderUri: String) {
        if (prepareJob?.isActive == true) return
        prepareJob = viewModelScope.launch {
            planState.value = BackupState.Preparing
            planState.value = try {
                val p = runner.prepare(folderUri)
                BackupState.Ready(p.plan, p.space, p.missing, p.missingUris)
            } catch (e: Exception) {
                BackupState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun start(folderUri: String) {
        startIfPossible(folderUri, state.value as? BackupState.Ready ?: return)
    }

    private fun startIfPossible(folderUri: String, ready: BackupState.Ready) {
        // Sicherheitsnetz: die UI bietet den Start bei zu wenig Platz gar nicht erst an
        if (ready.space is SpaceCheck.NotEnough || ready.plan.toCopy.isEmpty()) return
        BackupWorker.start(getApplication(), folderUri)
    }

    /** Unterbrochenes Backup fortsetzen: neu planen (bereits Gesichertes entfällt) und gleich starten. */
    fun resume(folderUri: String) {
        lastRun.markSeen()
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch {
            planState.value = BackupState.Preparing
            val ready = try {
                val p = runner.prepare(folderUri)
                BackupState.Ready(p.plan, p.space, p.missing, p.missingUris)
            } catch (e: Exception) {
                planState.value = BackupState.Error(e.message ?: e.javaClass.simpleName)
                return@launch
            }
            planState.value = ready
            startIfPossible(folderUri, ready)
        }
    }

    fun cancel() = BackupWorker.cancel(getApplication())

    /** Entscheidungen zu gelöschten Dateien umsetzen (Pfade ohne Entscheidung bleiben unberührt). */
    fun applyMissing(folderUri: String, actions: Map<String, MissingAction>) {
        if (actions.isEmpty()) return
        MissingWorker.start(getApplication(), MissingDecisions(folderUri, actions))
    }

    /** Zusammenfassung bestätigen und neu planen. */
    fun acknowledge(folderUri: String) {
        lastRun.markSeen()
        missingStore.saveSummary(null)
        prepare(folderUri)
    }
}
