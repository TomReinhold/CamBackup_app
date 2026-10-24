package de.trei.cambackup.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.backup.BackupPlan
import de.trei.cambackup.backup.RunSummary
import de.trei.cambackup.backup.SpaceCheck
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes

/** Planung, Fortschritt und Ergebnis des Backups für den erkannten Geräteordner. */
@Composable
fun BackupPanel(deviceFolderName: String, onBackupFinished: () -> Unit, vm: BackupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(deviceFolderName) { vm.prepareIfIdle(deviceFolderName) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.prepareIfIdle(deviceFolderName) }

    // Benachrichtigungen (Fortschritt/Ergebnis) beim ersten Start erfragen; das Backup startet in jedem Fall
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.start(deviceFolderName)
    }
    val start = {
        val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start(deviceFolderName)
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Backup", style = MaterialTheme.typography.titleSmall)
            when (val s = state) {
                BackupState.Preparing -> {
                    Text("Vergleiche Kameraordner mit dem Speichermedium …")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                is BackupState.Ready -> PlanView(s.plan, s.space, onStart = start)

                is BackupState.Running -> {
                    val p = s.progress
                    if (p == null || p.bytesTotal == 0L) {
                        Text("Backup wird vorbereitet …")
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
                    Text(
                        "Läuft auch im Hintergrund weiter. Speichermedium bitte nicht abziehen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = vm::cancel) { Text("Abbrechen") }
                }

                is BackupState.Finished -> ResultView(s.summary) {
                    vm.acknowledge(deviceFolderName)
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
            "${fileCount(plan.tooLargeForFileSystem.size)} größer als 4 GB – passt nicht auf ein " +
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
private fun ResultView(s: RunSummary, onDone: () -> Unit) {
    Text(
        if (s.isComplete) "Backup abgeschlossen" else "Backup unvollständig",
        color = if (s.isComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
    )
    Text("Kopiert und geprüft: ${fileCount(s.copied)} (${formatBytes(s.bytesCopied)})")
    if (s.adopted > 0) Text("Bereits vorhanden und übernommen: ${s.adopted}")
    s.abortReason?.let { Text("Abgebrochen: $it", color = MaterialTheme.colorScheme.error) }
    if (s.failures.isNotEmpty()) {
        Text("Fehler bei ${fileCount(s.failures.size)}:", color = MaterialTheme.colorScheme.error)
        s.failures.take(10).forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
    }
    Button(onClick = onDone) { Text("OK") }
}
