package de.trei.cambackup.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.net.Uri
import de.trei.cambackup.util.formatRemaining
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TextButton
import de.trei.cambackup.compress.TrialCompressor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.R
import de.trei.cambackup.freeup.FreeUpOverview
import de.trei.cambackup.freeup.FreeUpSummary
import de.trei.cambackup.ui.components.Banner
import de.trei.cambackup.ui.components.BannerTone
import de.trei.cambackup.ui.components.FeatureCard
import de.trei.cambackup.ui.components.HazardProgressBar
import de.trei.cambackup.ui.components.JobControls
import de.trei.cambackup.ui.components.JobRing
import de.trei.cambackup.ui.components.StickerButton
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes

/** Speicher freigeben: nach Zustimmung verkleinern und sofort ersetzen – oder im Probelauf nur rechnen. */
@Composable
fun FreeUpPanel(folderUri: String, onFinished: () -> Unit, vm: FreeUpViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val probeCount by vm.probeCount.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()

    val result by vm.result.collectAsStateWithLifecycle()

    LaunchedEffect(folderUri) { vm.load(folderUri) }
    // Nach Ende eines Laufs sofort neu einlesen (Größen/Status in Übersicht und Galerie)
    LaunchedEffect(result?.finishedAt) {
        if (result != null) {
            vm.reload(folderUri)
            onFinished()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load(folderUri) }
    // Ergebnis verschwindet, sobald die App verlassen wird – ohne „OK“
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.dismissResult() }

    // System-Dialoge „Darf CamBackup diese Dateien ändern?“ – nacheinander, erst danach wird verkleinert
    var pending by remember { mutableStateOf(emptyList<List<Uri>>()) }
    val scope = rememberCoroutineScope()
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val rest = pending.drop(1)
        pending = if (result.resultCode == Activity.RESULT_OK) rest else emptyList()
        if (result.resultCode == Activity.RESULT_OK && rest.isEmpty()) vm.compress(folderUri)
    }
    LaunchedEffect(pending) {
        pending.firstOrNull()?.let { writeRequest.launch(IntentSenderRequest.Builder(vm.writeRequest(it).intentSender).build()) }
    }

    FeatureCard(
        icon = R.drawable.ic_step_compress,
        title = stringResource(R.string.freeup_title),
        subtitle = stringResource(R.string.freeup_subtitle),
    ) {
        if (state !is FreeUpState.Working) result?.let { SummaryView(it) }
        when (val s = state) {
            FreeUpState.Loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                JobControls(paused = false, onPause = null, onCancel = vm::cancelLoad)
            }

            is FreeUpState.Overview -> OverviewView(
                o = s.overview,
                dryRun = s.dryRun,
                minAgeDays = s.minAgeDays,
                probeCount = probeCount,
                onDeleteProbes = vm::deleteProbes,
                onStart = {
                    if (s.dryRun) {
                        vm.dryRun(folderUri)
                    } else {
                        scope.launch {
                            val chunks = vm.writeChunks(s.overview, s.minAgeDays)
                            // Zustimmung schon vorhanden (z. B. nach einem Abbruch): gleich los
                            if (chunks.isEmpty()) vm.compress(folderUri) else pending = chunks
                        }
                    }
                },
            )

            is FreeUpState.Working -> {
                val p = s.progress
                Text(
                    (if (paused) stringResource(R.string.paused_prefix) else "") + when {
                        p == null -> stringResource(if (s.replacing) R.string.freeup_starting else R.string.freeup_dry_starting)
                        s.replacing -> stringResource(R.string.freeup_progress, p.done + 1, p.total)
                        else -> stringResource(R.string.freeup_dry_progress, p.done + 1, p.total)
                    },
                )
                val fraction = if (p == null || p.total == 0) null else p.done.toFloat() / p.total
                // Ein Fortschritt reicht: der Ring, sobald die Zahl der Dateien bekannt ist, vorher der Balken
                if (fraction != null) {
                    JobRing(fraction, caption = p?.remainingSeconds?.takeIf { it >= 0 }?.let { formatRemaining(it) } ?: stringResource(R.string.backup_eta_pending))
                } else {
                    HazardProgressBar(null, Modifier.fillMaxWidth())
                }
                Banner(
                    stringResource(if (s.replacing) R.string.freeup_running_info else R.string.freeup_dry_running_info),
                    title = stringResource(if (s.replacing) R.string.dont_unplug else R.string.freeup_dry_running_title),
                )
                JobControls(paused, vm::setPaused, vm::cancel)
            }

            is FreeUpState.Error -> {
                Banner(stringResource(R.string.error_prefix, s.message), BannerTone.ERROR)
                Button(onClick = { vm.load(folderUri) }) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun OverviewView(
    o: FreeUpOverview,
    dryRun: Boolean,
    minAgeDays: Int,
    probeCount: Int,
    onDeleteProbes: () -> Unit,
    onStart: () -> Unit,
) {
    val candidateBytes = o.candidates.sumOf { it.item.sizeBytes }
    Row {
        Text(stringResource(R.string.freeup_candidates), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${fileCount(o.candidates.size)} · ${formatBytes(candidateBytes)}", style = MaterialTheme.typography.titleSmall)
    }
    if (o.alreadyCompressed > 0) {
        Row {
            Text(stringResource(R.string.freeup_already), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.freeup_already_value, fileCount(o.alreadyCompressed), formatBytes(o.alreadyCompressedSaved)), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (o.keptOriginal > 0) {
        Text(
            pluralStringResource(R.plurals.freeup_kept, o.keptOriginal, fileCount(o.keptOriginal)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (o.notBackedUp > 0) {
        Text(
            stringResource(R.string.freeup_not_backed_up, fileCount(o.notBackedUp)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (dryRun) {
        Banner(
            stringResource(R.string.freeup_dry_info),
            title = stringResource(R.string.freeup_dry_title),
        )
    }
    if (probeCount > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.freeup_probes, fileCount(probeCount), TrialCompressor.ALBUM),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDeleteProbes) { Text(stringResource(R.string.action_delete)) }
        }
    }
    if (o.candidates.isEmpty()) {
        Text(stringResource(R.string.freeup_nothing), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(
        when (minAgeDays) {
            0 -> stringResource(R.string.freeup_age_all)
            else -> pluralStringResource(R.plurals.freeup_age, minAgeDays, minAgeDays)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!dryRun) {
        Text(
            stringResource(R.string.freeup_permission_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    StickerButton(
        stringResource(if (dryRun) R.string.freeup_dry_start else R.string.freeup_start),
        onStart,
        Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SummaryView(s: FreeUpSummary) {
    val ok = s.abortReason == null && s.failures.isEmpty()
    Banner(
        when {
            s.replacing -> stringResource(R.string.freeup_result, fileCount(s.done), formatBytes(s.bytesSaved))
            else -> stringResource(R.string.freeup_dry_result, fileCount(s.done), formatBytes(s.bytesSaved))
        } + (s.abortReason?.let { " · $it" } ?: ""),
        if (ok) BannerTone.SUCCESS else BannerTone.WARNING,
    )
    if (s.skipped.isNotEmpty()) {
        Text(stringResource(R.string.freeup_skipped_header), style = MaterialTheme.typography.labelLarge)
        s.skipped.forEach { (reason, count) -> Text("• $reason: $count", style = MaterialTheme.typography.bodySmall) }
    }
    if (s.failures.isNotEmpty()) {
        Text(stringResource(R.string.failures_header, fileCount(s.failures.size)), color = MaterialTheme.colorScheme.error)
        s.failures.take(10).forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
    }
}
