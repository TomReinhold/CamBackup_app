package de.trei.cambackup.ui

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.net.Uri
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.trei.cambackup.compress.TrialCompressor
import de.trei.cambackup.freeup.FreeUpOverview
import de.trei.cambackup.freeup.FreeUpProgress
import de.trei.cambackup.freeup.FreeUpRunner
import de.trei.cambackup.freeup.FreeUpStore
import de.trei.cambackup.freeup.FreeUpSummary
import de.trei.cambackup.freeup.compressTargets
import de.trei.cambackup.settings.SettingsRepository
import de.trei.cambackup.work.FreeUpWorker
import de.trei.cambackup.work.JobControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import de.trei.cambackup.util.toMsg
import de.trei.cambackup.util.AppError
import de.trei.cambackup.R

sealed interface FreeUpState {
    data object Loading : FreeUpState
    data class Overview(val overview: FreeUpOverview, val dryRun: Boolean, val minAgeDays: Int) : FreeUpState
    data class Working(val progress: FreeUpProgress?, val replacing: Boolean) : FreeUpState
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

    val state: StateFlow<FreeUpState> = combine(work, overview, settings) { running, ov, s ->
        when {
            running != null -> FreeUpState.Working(
                running.takeIf { it.state == WorkInfo.State.RUNNING }?.let { FreeUpWorker.progressOf(it.progress) },
                replacing = TAG_COMPRESS in running.tags,
            )
            ov == null -> FreeUpState.Loading
            else -> ov.fold(
                onSuccess = { FreeUpState.Overview(it, s.dryRun, s.minAgeDays) },
                onFailure = { FreeUpState.Error(it.toMsg().ui) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FreeUpState.Loading)

    private val trials = TrialCompressor(app)
    private val _probeCount = MutableStateFlow(0)
    /** Probe-Dateien im Album „CamBackup Probe“. */
    val probeCount: StateFlow<Int> = _probeCount.asStateFlow()

    fun load(folderUri: String) {
        viewModelScope.launch { _probeCount.value = runCatching { trials.count() }.getOrDefault(0) }
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val r = runCatching { runner.overview(folderUri) }
            // Abgebrochen (cancelLoad): der Zustand „Abgebrochen“ ist schon gesetzt
            if (r.exceptionOrNull() !is CancellationException) overview.value = r
        }
    }

    fun deleteProbes() {
        viewModelScope.launch {
            runCatching { trials.deleteAll() }
            _probeCount.value = runCatching { trials.count() }.getOrDefault(0)
        }
    }

    /** Probelauf: berechnet die Ersparnis, auf dem Smartphone ändert sich nichts. */
    fun dryRun(folderUri: String) {
        store.saveSummary(null)
        FreeUpWorker.dryRun(getApplication(), folderUri, TAG_DRY_RUN)
    }

    /**
     * System-Dialoge, in denen der Nutzer dem Ändern der Dateien zustimmt – nur für Dateien, für die
     * es noch keine Zustimmung gibt, und in Portionen (sehr lange Listen passen nicht in einen Dialog).
     * Liefert nur die Portionen; die Abfrage selbst erzeugt [writeRequest] erst direkt vor dem Anzeigen –
     * Android verwirft eine Abfrage, sobald die nächste angelegt wird.
     */
    suspend fun writeChunks(o: FreeUpOverview, minAgeDays: Int): List<List<Uri>> = withContext(Dispatchers.Default) {
        val app = getApplication<Application>()
        compressTargets(o.candidates, minAgeDays, System.currentTimeMillis())
            .map { it.item.uri }
            .filter { app.checkUriPermission(it, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED }
            .chunked(WRITE_REQUEST_CHUNK)
    }

    fun writeRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createWriteRequest(getApplication<Application>().contentResolver, uris)

    /** Nach Zustimmung im System-Dialog: verkleinern und sofort ersetzen. */
    fun compress(folderUri: String) {
        store.saveSummary(null)
        FreeUpWorker.compress(getApplication(), folderUri, TAG_COMPRESS)
    }

    fun cancel() {
        JobControl.setPaused(JobControl.FREE_UP, false)
        FreeUpWorker.cancel(getApplication())
    }

    val paused: StateFlow<Boolean> = JobControl.paused(JobControl.FREE_UP)
    fun setPaused(paused: Boolean) = JobControl.setPaused(JobControl.FREE_UP, paused)

    /** Laden des Überblicks abbrechen. */
    fun cancelLoad() {
        loadJob?.cancel()
        overview.value = Result.failure(AppError(R.string.cancelled))
    }

    /** Ergebnis des letzten Laufs – steht über dem Überblick, bis die App verlassen wird. */
    val result: StateFlow<FreeUpSummary?> = store.summary

    /** Nach einem Lauf: Überblick neu laden (Kandidaten haben sich geändert). */
    fun reload(folderUri: String) {
        loadJob?.cancel()
        overview.value = null
        load(folderUri)
    }

    fun dismissResult() = store.saveSummary(null)

    private companion object {
        const val TAG_DRY_RUN = "freeup-dryrun"
        const val TAG_COMPRESS = "freeup-compress"
        const val WRITE_REQUEST_CHUNK = 500
    }
}
