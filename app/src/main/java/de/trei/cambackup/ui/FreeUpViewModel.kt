package de.trei.cambackup.ui

import android.app.Application
import android.app.PendingIntent
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.trei.cambackup.freeup.FreeUpOverview
import de.trei.cambackup.freeup.FreeUpProgress
import de.trei.cambackup.freeup.FreeUpRunner
import de.trei.cambackup.freeup.FreeUpStore
import de.trei.cambackup.freeup.FreeUpSummary
import de.trei.cambackup.freeup.PendingBatch
import de.trei.cambackup.settings.SettingsRepository
import de.trei.cambackup.work.FreeUpWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface FreeUpState {
    data object Loading : FreeUpState
    data class Overview(val overview: FreeUpOverview, val dryRun: Boolean, val minAgeDays: Int) : FreeUpState
    data class Working(val progress: FreeUpProgress?, val replacing: Boolean) : FreeUpState
    data class Ready(val batch: PendingBatch, val dryRun: Boolean) : FreeUpState
    data class Finished(val summary: FreeUpSummary) : FreeUpState
    data class Error(val message: String) : FreeUpState
}

class FreeUpViewModel(app: Application) : AndroidViewModel(app) {

    private val runner = FreeUpRunner(app)
    private val store = FreeUpStore.get(app)
    private val settings = SettingsRepository(app).settings
    private val overview = MutableStateFlow<Result<FreeUpOverview>?>(null)
    private var loadJob: Job? = null

    private val work = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(FreeUpWorker.UNIQUE_NAME)
        .map { infos -> infos.firstOrNull { !it.state.isFinished } }

    val state: StateFlow<FreeUpState> = combine(work, store.summary, store.batch, overview, settings) { running, summary, batch, ov, s ->
        when {
            running != null -> FreeUpState.Working(
                running.takeIf { it.state == WorkInfo.State.RUNNING }?.let { FreeUpWorker.progressOf(it.progress) },
                replacing = TAG_REPLACE in running.tags,
            )
            summary != null -> FreeUpState.Finished(summary)
            batch != null -> FreeUpState.Ready(batch, s.dryRun)
            ov == null -> FreeUpState.Loading
            else -> ov.fold(
                onSuccess = { FreeUpState.Overview(it, s.dryRun, s.minAgeDays) },
                onFailure = { FreeUpState.Error(it.message ?: it.javaClass.simpleName) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FreeUpState.Loading)

    fun load(deviceFolderName: String) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { overview.value = runCatching { runner.overview(deviceFolderName) } }
    }

    fun prepare(deviceFolderName: String) = FreeUpWorker.prepare(getApplication(), deviceFolderName, TAG_PREPARE)

    /** System-Dialog, in dem der Nutzer dem Ändern der Dateien zustimmt. */
    fun writeRequest(batch: PendingBatch): PendingIntent =
        MediaStore.createWriteRequest(getApplication<Application>().contentResolver, batch.items.map { Uri.parse(it.uri) })

    fun replaceApproved() = FreeUpWorker.replace(getApplication(), TAG_REPLACE)

    fun cancel() = FreeUpWorker.cancel(getApplication())

    /** Vorbereitete Dateien verwerfen (z. B. im Probelauf). */
    fun discard(deviceFolderName: String) {
        store.batch.value?.items?.forEach { File(it.newFile).delete() }
        store.saveBatch(null)
        load(deviceFolderName)
    }

    fun acknowledge(deviceFolderName: String) {
        store.saveSummary(null)
        overview.value = null
        load(deviceFolderName)
    }

    private companion object {
        const val TAG_PREPARE = "freeup-prepare"
        const val TAG_REPLACE = "freeup-replace"
    }
}
