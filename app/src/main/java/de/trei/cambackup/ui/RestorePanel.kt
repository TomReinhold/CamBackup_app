package de.trei.cambackup.ui

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkManager
import de.trei.cambackup.R
import de.trei.cambackup.freeup.RestoreOverview
import de.trei.cambackup.freeup.RestoreRunner
import de.trei.cambackup.freeup.RestoreStore
import de.trei.cambackup.freeup.RestoreSummary
import de.trei.cambackup.ui.components.Banner
import de.trei.cambackup.ui.components.BannerTone
import de.trei.cambackup.ui.components.FeatureCard
import de.trei.cambackup.ui.components.JobControls
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes
import de.trei.cambackup.work.JobControl
import de.trei.cambackup.work.RestoreWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RestoreState {
    data object Loading : RestoreState
    /** Keine verkleinerten Dateien – die Karte wird nicht angezeigt. */
    data object Nothing : RestoreState
    data class Ready(val overview: RestoreOverview) : RestoreState
    data class Running(val done: Int, val total: Int) : RestoreState
    data class Error(val message: String) : RestoreState
}

class RestoreViewModel(app: Application) : AndroidViewModel(app) {

    private val runner = RestoreRunner(app)
    private val store = RestoreStore.get(app)
    private val overview = MutableStateFlow<Result<RestoreOverview>?>(null)
    private var job: Job? = null

    private val work = WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(RestoreWorker.UNIQUE_NAME)
        .map { infos -> infos.firstOrNull { !it.state.isFinished } }

    val state: StateFlow<RestoreState> = combine(work, overview) { running, ov ->
        when {
            running != null -> RestoreWorker.progressOf(running.progress).let { RestoreState.Running(it?.first ?: 0, it?.second ?: 0) }
            ov == null -> RestoreState.Loading
            else -> ov.fold(
                onSuccess = { if (it.candidates.isEmpty()) RestoreState.Nothing else RestoreState.Ready(it) },
                onFailure = { RestoreState.Error(it.message ?: it.javaClass.simpleName) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RestoreState.Loading)

    fun load(folderUri: String) {
        if (job?.isActive == true) return
        job = viewModelScope.launch { overview.value = runCatching { runner.overview(folderUri) } }
    }

    /** System-Dialog, in dem der Nutzer dem Ändern der Dateien zustimmt. */
    fun writeRequest(o: RestoreOverview): PendingIntent =
        MediaStore.createWriteRequest(getApplication<Application>().contentResolver, o.candidates.map { it.item.uri })

    fun start(folderUri: String, keepOriginal: Boolean) {
        store.save(null)
        RestoreWorker.start(getApplication(), folderUri, keepOriginal)
    }

    fun cancel() {
        JobControl.setPaused(JobControl.RESTORE, false)
        RestoreWorker.cancel(getApplication())
    }

    val paused: StateFlow<Boolean> = JobControl.paused(JobControl.RESTORE)
    fun setPaused(paused: Boolean) = JobControl.setPaused(JobControl.RESTORE, paused)

    /** Ergebnis des letzten Laufs – steht über dem Überblick, bis die App verlassen wird. */
    val result: StateFlow<RestoreSummary?> = store.summary

    /** Nach einem Lauf: Überblick neu laden. */
    fun reload(folderUri: String) {
        job?.cancel()
        overview.value = null
        load(folderUri)
    }

    fun dismissResult() = store.save(null)
}

/** Originale aller verkleinerten Dateien vom Medium zurück aufs Smartphone holen. */
@Composable
fun RestorePanel(folderUri: String, onFinished: () -> Unit, vm: RestoreViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var keepOriginal by rememberSaveable { mutableStateOf(true) }

    val result by vm.result.collectAsStateWithLifecycle()

    LaunchedEffect(folderUri) { vm.load(folderUri) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load(folderUri) }
    LaunchedEffect(result?.finishedAt) {
        if (result != null) {
            vm.reload(folderUri)
            onFinished()
        }
    }
    // Ergebnis verschwindet, sobald die App verlassen wird – ohne „OK“
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.dismissResult() }

    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.start(folderUri, keepOriginal)
    }

    // Ohne verkleinerte Dateien keine Karte – außer um das Ergebnis eines eben beendeten Laufs zu zeigen
    if ((state is RestoreState.Nothing || state is RestoreState.Loading) && result == null) return

    FeatureCard(icon = R.drawable.ic_restore, title = "Originale zurückholen", subtitle = "Verkleinerte Dateien wieder in voller Größe") {
        if (state !is RestoreState.Running) {
            result?.let { r ->
                Banner(
                    "${fileCount(r.restored)} wieder in voller Größe (${formatBytes(r.bytesAdded)})" + (r.abortReason?.let { " – $it" } ?: ""),
                    if (r.failures.isEmpty() && r.abortReason == null) BannerTone.SUCCESS else BannerTone.WARNING,
                )
                r.failures.take(10).forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        when (val s = state) {
            is RestoreState.Ready -> {
                val o = s.overview
                Row {
                    Text("Verkleinert auf dem Smartphone", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(fileCount(o.candidates.size), style = MaterialTheme.typography.titleSmall)
                }
                Row {
                    Text("Braucht zusätzlich", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(formatBytes(o.candidates.sumOf { it.extraBytes }), style = MaterialTheme.typography.titleSmall)
                }
                if (o.enoughSpace) {
                    Text(
                        "Frei auf dem Smartphone: ${formatBytes(o.phoneFree)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Banner(
                        "Zu wenig Platz auf dem Smartphone: benötigt ${formatBytes(o.spaceNeeded)} (inkl. Reserve), frei ${formatBytes(o.phoneFree)}.",
                        BannerTone.ERROR,
                    )
                }
                OutlinedButton(onClick = { confirm = true }, enabled = o.enoughSpace, modifier = Modifier.fillMaxWidth()) {
                    Text("Alle Originale zurückholen")
                }
                if (confirm) {
                    AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Originale zurückholen?") },
                        text = {
                            Column {
                                Text(
                                    "${fileCount(o.candidates.size)} " + (if (o.candidates.size == 1) "wird" else "werden") +
                                        " durch das Original vom Speichermedium ersetzt. Das belegt ${formatBytes(o.candidates.sumOf { it.extraBytes })} mehr auf dem Smartphone.",
                                )
                                Row(
                                    Modifier.fillMaxWidth().toggleable(value = keepOriginal, onValueChange = { keepOriginal = it }, role = Role.Checkbox),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = keepOriginal, onCheckedChange = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Danach nicht erneut automatisch verkleinern", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirm = false
                                writeRequest.launch(IntentSenderRequest.Builder(vm.writeRequest(o).intentSender).build())
                            }) { Text("Zurückholen") }
                        },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
                    )
                }
            }

            is RestoreState.Running -> {
                Text(
                    (if (paused) "Pausiert – " else "") +
                        if (s.total > 0) "Original ${minOf(s.done + 1, s.total)} von ${s.total}" else "Wird gestartet …",
                )
                if (s.total > 0) LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Banner("Speichermedium bitte nicht abziehen.")
                JobControls(paused, vm::setPaused, vm::cancel)
            }

            is RestoreState.Error -> {
                Banner("Fehler: ${s.message}", BannerTone.ERROR)
                Button(onClick = { vm.load(folderUri) }) { Text("Erneut versuchen") }
            }

            RestoreState.Loading, RestoreState.Nothing -> Unit
        }
    }
}
