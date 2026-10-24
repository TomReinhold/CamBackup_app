package de.trei.cambackup.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.R
import de.trei.cambackup.backup.BackupPlan
import de.trei.cambackup.backup.RunSummary
import de.trei.cambackup.backup.SpaceCheck
import de.trei.cambackup.ui.components.Banner
import de.trei.cambackup.ui.components.BannerTone
import de.trei.cambackup.ui.components.FeatureCard
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes

/** Planung, Fortschritt und Ergebnis des Backups für den erkannten Geräteordner. */
@Composable
fun BackupPanel(deviceFolderName: String, onBackupFinished: () -> Unit, vm: BackupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(deviceFolderName) { vm.prepareIfIdle(deviceFolderName) }
    // Nach Ende eines Laufs sofort neu einlesen (Status in Übersicht und Fotos)
    val finished = state is BackupState.Finished || state is BackupState.MissingFinished
    LaunchedEffect(finished) { if (finished) onBackupFinished() }
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

    FeatureCard(
        icon = R.drawable.ic_cloud_upload,
        title = "Backup",
        subtitle = "Originale bitgenau auf das Speichermedium kopieren",
    ) {
        when (val s = state) {
            BackupState.Preparing -> {
                Text("Vergleiche Medienordner mit dem Speichermedium …", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            is BackupState.Ready -> {
                if (s.missing.isNotEmpty()) {
                    var showMissing by rememberSaveable { mutableStateOf(false) }
                    MissingBanner(s.missing) { showMissing = true }
                    if (showMissing) {
                        MissingDialog(
                            missing = s.missing,
                            thumbnails = s.missingUris,
                            onDismiss = { showMissing = false },
                            onApply = { actions ->
                                showMissing = false
                                vm.applyMissing(deviceFolderName, actions)
                            },
                        )
                    }
                }
                PlanView(s.plan, s.space, onStart = start)
            }

            is BackupState.ApplyingMissing -> {
                Text(
                    if (s.total > 0) "Bearbeite gelöschte Dateien: ${minOf(s.done + 1, s.total)} von ${s.total}"
                    else "Gelöschte Dateien werden bearbeitet …",
                )
                if (s.total > 0) {
                    LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            is BackupState.MissingFinished -> {
                val m = s.summary
                Banner(
                    "${fileCount(m.restored)} wiederhergestellt, ${fileCount(m.deleted)} im Backup gelöscht" +
                        (m.abortReason?.let { " – $it" } ?: ""),
                    if (m.failures.isEmpty() && m.abortReason == null) BannerTone.SUCCESS else BannerTone.WARNING,
                )
                m.failures.take(10).forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                Button(onClick = {
                    vm.acknowledge(deviceFolderName)
                    onBackupFinished()
                }) { Text("OK") }
            }

            is BackupState.Running -> {
                val p = s.progress
                if (p == null || p.bytesTotal == 0L) {
                    Text("Backup wird vorbereitet …")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text("Datei ${minOf(p.fileIndex + 1, p.fileCount)} von ${p.fileCount}: ${p.currentName}", maxLines = 1)
                    LinearProgressIndicator(
                        progress = { (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatBytes(p.bytesDone / 2)} von ${formatBytes(p.bytesTotal / 2)} kopiert und geprüft",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Banner("Läuft auch im Hintergrund weiter. Speichermedium bitte nicht abziehen.")
                OutlinedButton(onClick = vm::cancel) { Text("Abbrechen") }
            }

            is BackupState.Finished -> ResultView(s.summary) {
                vm.acknowledge(deviceFolderName)
                onBackupFinished()
            }

            is BackupState.Error -> {
                Banner("Fehler: ${s.message}", BannerTone.ERROR)
                Button(onClick = { vm.prepare(deviceFolderName) }) { Text("Erneut versuchen") }
            }
        }
    }
}

@Composable
private fun PlanView(plan: BackupPlan, space: SpaceCheck, onStart: () -> Unit) {
    if (plan.tooLargeForFileSystem.isNotEmpty()) {
        Banner(
            "${fileCount(plan.tooLargeForFileSystem.size)} größer als 4 GB – passt nicht auf ein FAT32-Medium: " +
                "${plan.tooLargeForFileSystem.joinToString { it.displayName }}. Bitte ein mit exFAT formatiertes Medium verwenden.",
            BannerTone.WARNING,
        )
    }
    if (plan.toCopy.isEmpty()) {
        Banner(
            "Alles gesichert" + (if (plan.alreadyBackedUp > 0) " – ${fileCount(plan.alreadyBackedUp)} liegen auf dem Medium." else "."),
            BannerTone.SUCCESS,
        )
        return
    }
    Row {
        Text("Neu zu sichern", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${fileCount(plan.toCopy.size)} · ${formatBytes(plan.bytesToCopy)}", style = MaterialTheme.typography.titleSmall)
    }
    if (plan.alreadyBackedUp > 0) {
        Row {
            Text("Bereits gesichert", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(fileCount(plan.alreadyBackedUp), style = MaterialTheme.typography.bodyMedium)
        }
    }
    when (space) {
        is SpaceCheck.Enough -> Text(
            "Benötigt inkl. Reserve: ${formatBytes(space.requiredBytes)} – frei: ${formatBytes(space.freeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is SpaceCheck.NotEnough -> Banner(
            "Zu wenig Platz: benötigt ${formatBytes(space.requiredBytes)} (inkl. Reserve), frei nur " +
                "${formatBytes(space.freeBytes)}. Bitte Platz schaffen oder ein größeres Medium verwenden.",
            BannerTone.ERROR,
        )
        is SpaceCheck.Unknown -> Text(
            "Freier Platz unbekannt – benötigt werden ca. ${formatBytes(space.requiredBytes)}.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = onStart, enabled = space !is SpaceCheck.NotEnough, modifier = Modifier.fillMaxWidth()) {
        Text("Backup starten")
    }
}

@Composable
private fun ResultView(s: RunSummary, onDone: () -> Unit) {
    Banner(
        if (s.isComplete) "Backup abgeschlossen: ${fileCount(s.copied)} (${formatBytes(s.bytesCopied)}) kopiert und geprüft."
        else "Backup unvollständig: ${fileCount(s.copied)} kopiert" + (s.abortReason?.let { " – $it" } ?: ""),
        if (s.isComplete) BannerTone.SUCCESS else BannerTone.WARNING,
    )
    if (s.adopted > 0) Text("Bereits vorhanden und übernommen: ${fileCount(s.adopted)}", style = MaterialTheme.typography.bodySmall)
    if (s.failures.isNotEmpty()) {
        Text("Fehler bei ${fileCount(s.failures.size)}:", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        s.failures.take(10).forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
    }
    Button(onClick = onDone) { Text("OK") }
}
