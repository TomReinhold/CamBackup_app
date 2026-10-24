package de.trei.cambackup.ui

import android.app.Activity
import android.app.PendingIntent
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
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes

/** Speicher freigeben: nach Zustimmung verkleinern und sofort ersetzen – oder im Probelauf nur rechnen. */
@Composable
fun FreeUpPanel(folderUri: String, onFinished: () -> Unit, vm: FreeUpViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val probeCount by vm.probeCount.collectAsStateWithLifecycle()

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
    var pending by remember { mutableStateOf(emptyList<PendingIntent>()) }
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val rest = pending.drop(1)
        pending = if (result.resultCode == Activity.RESULT_OK) rest else emptyList()
        if (result.resultCode == Activity.RESULT_OK && rest.isEmpty()) vm.compress(folderUri)
    }
    LaunchedEffect(pending) {
        pending.firstOrNull()?.let { writeRequest.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
    }

    FeatureCard(
        icon = R.drawable.ic_compress,
        title = "Speicher freigeben",
        subtitle = "Gesicherte Originale auf dem Smartphone verkleinern",
    ) {
        if (state !is FreeUpState.Working) result?.let { SummaryView(it) }
        when (val s = state) {
            FreeUpState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())

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
                        val requests = vm.writeRequests(s.overview, s.minAgeDays)
                        // Zustimmung schon vorhanden (z. B. nach einem Abbruch): gleich los
                        if (requests.isEmpty()) vm.compress(folderUri) else pending = requests
                    }
                },
            )

            is FreeUpState.Working -> {
                val p = s.progress
                Text(
                    when {
                        p == null -> if (s.replacing) "Verkleinern wird gestartet …" else "Probelauf wird gestartet …"
                        s.replacing -> "Verkleinere ${p.done + 1} von ${p.total}"
                        else -> "Probelauf: prüfe ${p.done + 1} von ${p.total}"
                    },
                )
                if (p == null || p.total == 0) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                }
                Banner(
                    if (s.replacing) "Jede Datei wird verkleinert und sofort ersetzt – das Original bleibt auf dem Speichermedium. " +
                        "Speichermedium bitte nicht abziehen."
                    else "Auf dem Smartphone wird dabei nichts verändert. Videos brauchen etwas Zeit.",
                    title = if (s.replacing) "Speichermedium bitte nicht abziehen" else "Probelauf – es wird nichts verändert",
                )
                OutlinedButton(onClick = vm::cancel) { Text("Abbrechen") }
            }

            is FreeUpState.Error -> {
                Banner("Fehler: ${s.message}", BannerTone.ERROR)
                Button(onClick = { vm.load(folderUri) }) { Text("Erneut versuchen") }
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
        Text("Gesichert, noch im Original", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${fileCount(o.candidates.size)} · ${formatBytes(candidateBytes)}", style = MaterialTheme.typography.titleSmall)
    }
    if (o.alreadyCompressed > 0) {
        Row {
            Text("Bereits verkleinert", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${fileCount(o.alreadyCompressed)} · ${formatBytes(o.alreadyCompressedSaved)} frei", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (o.keptOriginal > 0) {
        Text(
            "${fileCount(o.keptOriginal)} " + (if (o.keptOriginal == 1) "bleibt" else "bleiben") +
                " auf Wunsch in Originalgröße (in der Galerie mit Schloss markiert).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (o.notBackedUp > 0) {
        Text(
            "${fileCount(o.notBackedUp)} noch nicht gesichert – diese werden nicht verändert.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (dryRun) {
        Banner(
            "Probelauf ist aktiv: Es wird nur berechnet, wie viel Platz frei würde – ersetzt wird nichts. " +
                "Einzelne Dateien kannst du in der Galerie auswählen und „Probe verkleinern“, um das Ergebnis anzusehen.",
            title = "Probelauf ist aktiv",
        )
    }
    if (probeCount > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${fileCount(probeCount)} zur Probe im Album „${TrialCompressor.ALBUM}“",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDeleteProbes) { Text("Löschen") }
        }
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
    if (!dryRun) {
        Text(
            "Die Originale bleiben auf dem Speichermedium. Android fragt gleich, ob CamBackup die Dateien ändern darf.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Text(if (dryRun) "Probelauf: Ersparnis berechnen" else "Jetzt verkleinern")
    }
}

@Composable
private fun SummaryView(s: FreeUpSummary) {
    val ok = s.abortReason == null && s.failures.isEmpty()
    Banner(
        when {
            s.replacing -> "${fileCount(s.done)} verkleinert – ${formatBytes(s.bytesSaved)} frei geworden"
            else -> "Probelauf: ${fileCount(s.done)} ließen sich verkleinern – spart ${formatBytes(s.bytesSaved)}. Auf dem Smartphone wurde nichts verändert."
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
}
