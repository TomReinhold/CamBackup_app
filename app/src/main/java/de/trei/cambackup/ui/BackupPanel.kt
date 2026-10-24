package de.trei.cambackup.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.R
import de.trei.cambackup.backup.BackupPlan
import de.trei.cambackup.backup.MissingSummary
import de.trei.cambackup.backup.RunSummary
import de.trei.cambackup.backup.SpaceCheck
import de.trei.cambackup.ui.components.Banner
import de.trei.cambackup.ui.components.BannerTone
import de.trei.cambackup.ui.components.FeatureCard
import de.trei.cambackup.ui.settings.layoutLabel
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes
import de.trei.cambackup.util.formatRemaining

/** Planung, Fortschritt und Ergebnis des Backups für den erkannten Geräteordner. */
@Composable
fun BackupPanel(folderUri: String, onBackupFinished: () -> Unit, vm: BackupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val result by vm.result.collectAsStateWithLifecycle()
    val missingResult by vm.missingResult.collectAsStateWithLifecycle()

    LaunchedEffect(folderUri) { vm.prepareIfIdle(folderUri) }
    // Nach Ende eines Laufs sofort neu planen und einlesen (Status in Übersicht und Galerie)
    LaunchedEffect(result?.finishedAt, missingResult?.finishedAt) {
        if (result != null || missingResult != null) {
            vm.prepare(folderUri)
            onBackupFinished()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.prepareIfIdle(folderUri) }
    // Erledigte Ergebnisse verschwinden, sobald die App verlassen wird – ohne „OK“
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.dismissCompleted() }

    // Benachrichtigungen (Fortschritt/Ergebnis) beim ersten Start erfragen; das Backup startet in jedem Fall
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.start(folderUri)
    }
    val start = {
        val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start(folderUri)
    }

    FeatureCard(
        icon = R.drawable.ic_cloud_upload,
        title = "Backup",
        subtitle = "Originale bitgenau auf das Speichermedium kopieren",
    ) {
        if (state !is BackupState.Running && state !is BackupState.ApplyingMissing) {
            result?.let { ResultView(it) }
            missingResult?.let { MissingResultView(it) }
        }
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
                                vm.applyMissing(folderUri, actions)
                            },
                        )
                    }
                }
                PlanView(
                    s.plan, s.space,
                    resume = result?.abortReason != null,
                    showDone = result?.isComplete != true,
                    onStart = start,
                )
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

            is BackupState.Running -> {
                val p = s.progress
                if (p != null && p.moving) {
                    Text("Neue Ordnerstruktur: ${minOf(p.fileIndex + 1, p.fileCount)} von ${p.fileCount} Dateien verschoben")
                    LinearProgressIndicator(
                        progress = { if (p.fileCount > 0) p.fileIndex.toFloat() / p.fileCount else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (p == null || p.bytesTotal == 0L) {
                    Text("Backup wird vorbereitet …")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Datei ${minOf(p.fileIndex + 1, p.fileCount)} von ${p.fileCount}", modifier = Modifier.weight(1f))
                        Text(
                            if (p.remainingSeconds >= 0) formatRemaining(p.remainingSeconds) else "Restzeit wird ermittelt …",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatBytes(p.bytesDone / 2)} von ${formatBytes(p.bytesTotal / 2)} kopiert und geprüft",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Banner(
                    "Läuft auch im Hintergrund weiter. Speichermedium bitte nicht abziehen – falls doch, bleibt alles " +
                        "bereits Geprüfte gesichert und das Backup lässt sich später fortsetzen.",
                    title = "Speichermedium bitte nicht abziehen",
                )
                OutlinedButton(onClick = vm::cancel) { Text("Abbrechen") }
            }

            is BackupState.Error -> {
                Banner("Fehler: ${s.message}", BannerTone.ERROR)
                Button(onClick = { vm.prepare(folderUri) }) { Text("Erneut versuchen") }
            }
        }
    }
}

@Composable
private fun PlanView(plan: BackupPlan, space: SpaceCheck, resume: Boolean, showDone: Boolean, onStart: () -> Unit) {
    if (plan.tooLargeForFileSystem.isNotEmpty()) {
        Banner(
            "${fileCount(plan.tooLargeForFileSystem.size)} größer als 4 GB – passt nicht auf ein FAT32-Medium: " +
                "${plan.tooLargeForFileSystem.joinToString { it.displayName }}. Bitte ein mit exFAT formatiertes Medium verwenden.",
            BannerTone.WARNING,
        )
    }
    if (plan.toMove.isNotEmpty()) {
        val from = plan.currentLayout?.let { d ->
            " (bisher: ${layoutLabel(d.layout)}" + (if (d.separateFolders) ", je Medienordner" else "") + ")"
        }.orEmpty()
        Banner(
            "Neue Ordnerstruktur: ${fileCount(plan.toMove.size)} liegen noch in der bisherigen Ablage$from und werden " +
                "beim Backup auf dem Medium verschoben. Der Inhalt bleibt unverändert, es wird nichts neu kopiert.",
            title = "Neue Ordnerstruktur: ${fileCount(plan.toMove.size)} werden verschoben",
        )
    }
    if (!plan.hasWork) {
        // Direkt nach einem erfolgreichen Lauf sagt das Ergebnis darüber schon dasselbe
        if (showDone) {
            Banner(
                "Alles gesichert" + (if (plan.alreadyBackedUp > 0) " – ${fileCount(plan.alreadyBackedUp)} liegen auf dem Medium." else "."),
                BannerTone.SUCCESS,
            )
        }
        return
    }
    if (plan.toCopy.isNotEmpty()) {
        Row {
            Text("Neu zu sichern", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${fileCount(plan.toCopy.size)} · ${formatBytes(plan.bytesToCopy)}", style = MaterialTheme.typography.titleSmall)
        }
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
        Text(
            when {
                resume -> "Fortsetzen"
                plan.toCopy.isEmpty() -> "Ordnerstruktur anpassen"
                else -> "Backup starten"
            },
        )
    }
}

/** Ergebnis gelöschter Dateien (wiederhergestellt bzw. im Backup gelöscht). */
@Composable
private fun MissingResultView(m: MissingSummary) {
    Banner(
        "${fileCount(m.restored)} wiederhergestellt, ${fileCount(m.deleted)} im Backup gelöscht" +
            (m.abortReason?.let { " – $it" } ?: ""),
        if (m.failures.isEmpty() && m.abortReason == null) BannerTone.SUCCESS else BannerTone.WARNING,
    )
    m.failures.take(10).forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ResultView(s: RunSummary) {
    Banner(
        if (s.isComplete && s.copied == 0 && s.moved > 0) "Ordnerstruktur angepasst: ${fileCount(s.moved)} verschoben."
        else if (s.isComplete && s.copied == 0 && s.adopted == 0) "Backup abgeschlossen – es gab nichts Neues zu sichern."
        else if (s.isComplete) "Backup abgeschlossen: ${fileCount(s.copied)} (${formatBytes(s.bytesCopied)}) kopiert und geprüft."
        else "Backup unvollständig: ${fileCount(s.copied)} kopiert" + (s.abortReason?.let { " – $it" } ?: ""),
        if (s.isComplete) BannerTone.SUCCESS else BannerTone.WARNING,
    )
    if (s.adopted > 0) Text("Bereits vorhanden und übernommen: ${fileCount(s.adopted)}", style = MaterialTheme.typography.bodySmall)
    if (s.moved > 0) Text("In die neue Ordnerstruktur verschoben: ${fileCount(s.moved)}", style = MaterialTheme.typography.bodySmall)
    if (s.failures.isNotEmpty()) {
        Text("Fehler bei ${fileCount(s.failures.size)}:", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        s.failures.take(10).forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
    }
    if (s.abortReason != null) {
        Text(
            "Alles bis zur Unterbrechung ist geprüft gesichert. Beim Fortsetzen werden nur die fehlenden Dateien kopiert.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
