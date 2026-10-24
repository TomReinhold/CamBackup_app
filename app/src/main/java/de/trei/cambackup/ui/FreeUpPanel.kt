package de.trei.cambackup.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.R
import de.trei.cambackup.freeup.FreeUpOverview
import de.trei.cambackup.freeup.FreeUpSummary
import de.trei.cambackup.freeup.PendingBatch
import de.trei.cambackup.ui.components.Banner
import de.trei.cambackup.ui.components.BannerTone
import de.trei.cambackup.ui.components.FeatureCard
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes

/** Speicher freigeben: vorbereiten, prüfen, nach Zustimmung ersetzen. */
@Composable
fun FreeUpPanel(deviceFolderName: String, onFinished: () -> Unit, vm: FreeUpViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(deviceFolderName) { vm.load(deviceFolderName) }
    // Nach Ende eines Laufs sofort neu einlesen (Größen/Status in Übersicht und Fotos)
    val finished = state is FreeUpState.Finished
    LaunchedEffect(finished) { if (finished) onFinished() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load(deviceFolderName) }

    // System-Dialog „Darf CamBackup diese Dateien ändern?“ – erst danach wird ersetzt
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.replaceApproved()
    }

    FeatureCard(
        icon = R.drawable.ic_compress,
        title = "Speicher freigeben",
        subtitle = "Gesicherte Originale auf dem Smartphone verkleinern",
    ) {
        when (val s = state) {
            FreeUpState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())

            is FreeUpState.Overview -> OverviewView(s.overview, s.dryRun, s.minAgeDays) { vm.prepare(deviceFolderName) }

            is FreeUpState.Working -> {
                val p = s.progress
                Text(
                    when {
                        p == null -> if (s.replacing) "Ersetzen wird gestartet …" else "Vorbereitung wird gestartet …"
                        s.replacing -> "Ersetze ${p.done + 1} von ${p.total}: ${p.currentName}"
                        else -> "Prüfe und verkleinere ${p.done + 1} von ${p.total}: ${p.currentName}"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (p == null || p.total == 0) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                }
                Banner(
                    if (s.replacing) "Speichermedium bitte nicht abziehen."
                    else "Auf dem Smartphone wird dabei noch nichts verändert. Videos brauchen etwas Zeit.",
                )
                OutlinedButton(onClick = vm::cancel) { Text("Abbrechen") }
            }

            is FreeUpState.Ready -> ReadyView(
                batch = s.batch,
                dryRun = s.dryRun,
                onReplace = { writeRequest.launch(IntentSenderRequest.Builder(vm.writeRequest(s.batch).intentSender).build()) },
                onDiscard = { vm.discard(deviceFolderName) },
            )

            is FreeUpState.Finished -> SummaryView(s.summary) {
                vm.acknowledge(deviceFolderName)
                onFinished()
            }

            is FreeUpState.Error -> {
                Banner("Fehler: ${s.message}", BannerTone.ERROR)
                Button(onClick = { vm.load(deviceFolderName) }) { Text("Erneut versuchen") }
            }
        }
    }
}

@Composable
private fun OverviewView(o: FreeUpOverview, dryRun: Boolean, minAgeDays: Int, onPrepare: () -> Unit) {
    val candidateBytes = o.candidates.sumOf { it.item.sizeBytes }
    Row {
        Text("Gesichert, noch im Original", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${fileCount(o.candidates.size)} · ${formatBytes(candidateBytes)}", style = MaterialTheme.typography.titleSmall)
    }
    if (o.alreadyCompressed > 0) {
        Row {
            Text("Bereits verkleinert", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${fileCount(o.alreadyCompressed)} · ${formatBytes(o.alreadyCompressedSaved)} frei", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (o.notBackedUp > 0) {
        Text(
            "${fileCount(o.notBackedUp)} noch nicht gesichert – diese werden nicht verändert.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (dryRun) {
        Banner("Probelauf ist aktiv: Du kannst die Verkleinerung vorbereiten und ansehen, ersetzt wird nichts.")
    }
    if (o.candidates.isEmpty()) {
        Text("Nichts zu verkleinern.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(
        when (minAgeDays) {
            0 -> "Alle gesicherten Fotos und Videos werden berücksichtigt (Mindestalter einstellbar)."
            1 -> "Es werden nur Dateien älter als 1 Tag berücksichtigt (einstellbar)."
            else -> "Es werden nur Dateien älter als $minAgeDays Tage berücksichtigt (einstellbar)."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onPrepare, modifier = Modifier.fillMaxWidth()) { Text("Verkleinerung vorbereiten") }
}

@Composable
private fun ReadyView(batch: PendingBatch, dryRun: Boolean, onReplace: () -> Unit, onDiscard: () -> Unit) {
    val before = batch.items.sumOf { it.originalSize }
    val after = batch.items.sumOf { it.newSize }
    Banner("${fileCount(batch.items.size)} bereit: ${formatBytes(before)} → ${formatBytes(after)} – spart ${formatBytes(before - after)}", BannerTone.SUCCESS)
    batch.items.take(5).forEach { p ->
        Row {
            Text(p.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${formatBytes(p.originalSize)} → ${formatBytes(p.newSize)}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (batch.items.size > 5) Text("… und ${batch.items.size - 5} weitere", style = MaterialTheme.typography.bodySmall)
    if (dryRun) {
        Banner("Probelauf aktiv – zum Ersetzen in den Einstellungen ausschalten.", BannerTone.WARNING)
    } else {
        Text(
            "Die Originale bleiben auf dem Speichermedium. Android fragt gleich, ob CamBackup die Dateien ändern darf.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onReplace, enabled = !dryRun) { Text("Jetzt ersetzen") }
        TextButton(onClick = onDiscard) { Text("Verwerfen") }
    }
}

@Composable
private fun SummaryView(s: FreeUpSummary, onDone: () -> Unit) {
    val ok = s.abortReason == null && s.failures.isEmpty()
    Banner(
        when {
            s.replacing -> "${fileCount(s.done)} ersetzt – ${formatBytes(s.bytesSaved)} frei geworden"
            else -> "${fileCount(s.done)} vorbereitet – spart ${formatBytes(s.bytesSaved)}"
        } + (s.abortReason?.let { " · $it" } ?: ""),
        if (ok) BannerTone.SUCCESS else BannerTone.WARNING,
    )
    if (s.skipped.isNotEmpty()) {
        Text("Unverändert gelassen:", style = MaterialTheme.typography.labelLarge)
        s.skipped.forEach { (reason, count) -> Text("• $reason: $count", style = MaterialTheme.typography.bodySmall) }
    }
    if (s.failures.isNotEmpty()) {
        Text("Fehler bei ${fileCount(s.failures.size)}:", color = MaterialTheme.colorScheme.error)
        s.failures.take(10).forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
    }
    Button(onClick = onDone) { Text("OK") }
}
