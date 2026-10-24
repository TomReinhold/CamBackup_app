package de.trei.cambackup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.backup.BackupPlan
import de.trei.cambackup.backup.BackupResult
import de.trei.cambackup.backup.SpaceCheck
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes

/** Planung, Fortschritt und Ergebnis des Backups für den erkannten Geräteordner. */
@Composable
fun BackupPanel(deviceFolderName: String, onBackupFinished: () -> Unit, vm: BackupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(deviceFolderName) { vm.prepareIfIdle(deviceFolderName) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.prepareIfIdle(deviceFolderName) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Backup", style = MaterialTheme.typography.titleSmall)
            when (val s = state) {
                BackupState.Preparing -> {
                    Text("Vergleiche Kameraordner mit dem Speichermedium …")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                is BackupState.Ready -> PlanView(s.plan, s.space, onStart = vm::start)

                is BackupState.Running -> {
                    KeepScreenOn()
                    val p = s.progress
                    if (p == null || p.bytesTotal == 0L) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Text("Datei ${minOf(p.fileIndex + 1, p.fileCount)} von ${p.fileCount}: ${p.currentName}")
                        LinearProgressIndicator(
                            progress = { (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${formatBytes(p.bytesDone / 2)} von ${formatBytes(p.bytesTotal / 2)} kopiert und geprüft",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = vm::cancel, enabled = !s.cancelling) {
                        Text(if (s.cancelling) "Wird abgebrochen …" else "Abbrechen")
                    }
                }

                is BackupState.Finished -> ResultView(s.result) {
                    vm.prepare(deviceFolderName)
                    onBackupFinished()
                }

                is BackupState.Error -> {
                    Text("Fehler: ${s.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.prepare(deviceFolderName) }) { Text("Erneut versuchen") }
                }
            }
        }
    }
}

@Composable
private fun PlanView(plan: BackupPlan, space: SpaceCheck, onStart: () -> Unit) {
    if (plan.alreadyBackedUp > 0) Text("Bereits gesichert: ${fileCount(plan.alreadyBackedUp)}")
    if (plan.tooLargeForFileSystem.isNotEmpty()) {
        Text(
            "${plan.tooLargeForFileSystem.size} Datei(en) sind größer als 4 GB und passen nicht auf ein " +
                "FAT32-Medium: ${plan.tooLargeForFileSystem.joinToString { it.displayName }}. " +
                "Bitte ein mit exFAT formatiertes Medium verwenden.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (plan.toCopy.isEmpty()) {
        Text("Alles gesichert – keine neuen Fotos oder Videos.", color = MaterialTheme.colorScheme.primary)
        return
    }
    Text("Neu zu sichern: ${fileCount(plan.toCopy.size)} (${formatBytes(plan.bytesToCopy)})")
    when (space) {
        is SpaceCheck.Enough -> Text(
            "Benötigt inkl. Reserve: ${formatBytes(space.requiredBytes)} – frei: ${formatBytes(space.freeBytes)}",
            style = MaterialTheme.typography.bodySmall,
        )
        is SpaceCheck.NotEnough -> Text(
            "Zu wenig Platz: benötigt ${formatBytes(space.requiredBytes)} (inkl. Reserve), " +
                "frei nur ${formatBytes(space.freeBytes)}. Bitte Platz schaffen oder ein größeres Medium verwenden.",
            color = MaterialTheme.colorScheme.error,
        )
        is SpaceCheck.Unknown -> Text(
            "Freier Platz unbekannt – benötigt werden ca. ${formatBytes(space.requiredBytes)}.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = onStart, enabled = space !is SpaceCheck.NotEnough) { Text("Backup starten") }
}

@Composable
private fun ResultView(result: BackupResult, onDone: () -> Unit) {
    val ok = result.abortReason == null && result.failures.isEmpty()
    Text(
        if (ok) "Backup abgeschlossen" else "Backup unvollständig",
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
    )
    Text("Kopiert und geprüft: ${fileCount(result.copied)} (${formatBytes(result.bytesCopied)})")
    if (result.adopted > 0) Text("Bereits vorhanden und übernommen: ${result.adopted}")
    result.abortReason?.let { Text("Abgebrochen: $it", color = MaterialTheme.colorScheme.error) }
    if (result.failures.isNotEmpty()) {
        Text("Fehler bei ${result.failures.size} Datei(en):", color = MaterialTheme.colorScheme.error)
        result.failures.take(10).forEach {
            Text("• ${it.item.displayName}: ${it.reason}", style = MaterialTheme.typography.bodySmall)
        }
    }
    Row { Button(onClick = onDone) { Text("OK") } }
}

/** Verhindert, dass der Bildschirm während des Backups ausgeht (und die App in den Hintergrund gerät). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
