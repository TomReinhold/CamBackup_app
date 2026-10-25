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
import androidx.compose.ui.res.stringResource
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
import de.trei.cambackup.ui.components.HazardProgressBar
import de.trei.cambackup.ui.components.JobControls
import de.trei.cambackup.ui.components.JobRing
import de.trei.cambackup.ui.components.StickerButton
import de.trei.cambackup.ui.settings.layoutLabel
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes
import de.trei.cambackup.util.formatRemaining

/** Planung, Fortschritt und Ergebnis des Backups für den erkannten Geräteordner. */
@Composable
fun BackupPanel(
    folderUri: String,
    onBackupFinished: () -> Unit,
    onNext: (() -> Unit)? = null,
    vm: BackupViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val result by vm.result.collectAsStateWithLifecycle()
    val missingResult by vm.missingResult.collectAsStateWithLifecycle()
    val backupPaused by vm.backupPaused.collectAsStateWithLifecycle()
    val missingPaused by vm.missingPaused.collectAsStateWithLifecycle()

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
        icon = R.drawable.ic_step_backup,
        title = stringResource(R.string.backup_title),
        subtitle = stringResource(R.string.backup_subtitle),
    ) {
        if (state !is BackupState.Running && state !is BackupState.ApplyingMissing) {
            result?.let { ResultView(it) }
            missingResult?.let { MissingResultView(it) }
        }
        when (val s = state) {
            BackupState.Preparing -> {
                Text(stringResource(R.string.backup_comparing), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                // Dauert nur Sekunden – Pausieren lohnt nicht, Abbrechen schon
                JobControls(paused = false, onPause = null, onCancel = vm::cancelPrepare)
            }

            BackupState.Cancelled -> {
                Text(stringResource(R.string.backup_compare_cancelled), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { vm.prepare(folderUri) }) { Text(stringResource(R.string.backup_compare_again)) }
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
                if (!s.plan.hasWork && onNext != null) {
                    StickerButton(stringResource(R.string.action_next_free_up), onNext, Modifier.fillMaxWidth())
                }
            }

            is BackupState.ApplyingMissing -> {
                Text(
                    when {
                        missingPaused -> stringResource(R.string.paused_prefix) +
                            stringResource(R.string.missing_progress, minOf(s.done + 1, s.total), s.total)
                        s.total > 0 -> stringResource(R.string.missing_progress, minOf(s.done + 1, s.total), s.total)
                        else -> stringResource(R.string.missing_starting)
                    },
                )
                HazardProgressBar(if (s.total > 0) s.done.toFloat() / s.total else null, Modifier.fillMaxWidth())
                JobControls(missingPaused, vm::setMissingPaused, vm::cancelMissing)
            }

            is BackupState.Running -> {
                val p = s.progress
                if (backupPaused) {
                    Text(stringResource(R.string.paused), style = MaterialTheme.typography.titleSmall)
                }
                if (p != null && p.moving) {
                    Text(stringResource(R.string.backup_moving_progress, minOf(p.fileIndex + 1, p.fileCount), p.fileCount))
                    HazardProgressBar(if (p.fileCount > 0) p.fileIndex.toFloat() / p.fileCount else 0f, Modifier.fillMaxWidth())
                } else if (p == null || p.bytesTotal == 0L) {
                    Text(stringResource(R.string.backup_starting))
                    HazardProgressBar(null, Modifier.fillMaxWidth())
                } else {
                    val fraction = (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f)
                    JobRing(
                        fraction,
                        if (p.remainingSeconds >= 0) formatRemaining(p.remainingSeconds) else stringResource(R.string.backup_eta_pending),
                    )
                    Text(stringResource(R.string.backup_file_progress, minOf(p.fileIndex + 1, p.fileCount), p.fileCount))
                    HazardProgressBar(fraction, Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.backup_bytes_progress, formatBytes(p.bytesDone / 2), formatBytes(p.bytesTotal / 2)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Banner(
                    stringResource(R.string.backup_running_info),
                    title = stringResource(R.string.dont_unplug),
                )
                JobControls(backupPaused, vm::setBackupPaused, vm::cancel)
            }

            is BackupState.Error -> {
                Banner(stringResource(R.string.error_prefix, s.message), BannerTone.ERROR)
                Button(onClick = { vm.prepare(folderUri) }) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun PlanView(plan: BackupPlan, space: SpaceCheck, resume: Boolean, showDone: Boolean, onStart: () -> Unit) {
    if (plan.tooLargeForFileSystem.isNotEmpty()) {
        Banner(
            stringResource(R.string.backup_too_large_fat, fileCount(plan.tooLargeForFileSystem.size), plan.tooLargeForFileSystem.joinToString { it.displayName }),
            BannerTone.WARNING,
        )
    }
    if (plan.toMove.isNotEmpty()) {
        val from = plan.currentLayout?.let { d ->
            val label = stringResource(layoutLabel(d.layout))
            stringResource(if (d.separateFolders) R.string.backup_layout_was_separate else R.string.backup_layout_was, label)
        }.orEmpty()
        Banner(
            stringResource(R.string.backup_relocate_info, fileCount(plan.toMove.size), from),
            title = stringResource(R.string.backup_relocate_title, fileCount(plan.toMove.size)),
        )
    }
    if (!plan.hasWork) {
        // Direkt nach einem erfolgreichen Lauf sagt das Ergebnis darüber schon dasselbe
        if (showDone) {
            Banner(
                if (plan.alreadyBackedUp > 0) stringResource(R.string.backup_all_done_count, fileCount(plan.alreadyBackedUp))
                else stringResource(R.string.backup_all_done),
                BannerTone.SUCCESS,
            )
        }
        return
    }
    if (plan.toCopy.isNotEmpty()) {
        Row {
            Text(stringResource(R.string.backup_to_copy), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${fileCount(plan.toCopy.size)} · ${formatBytes(plan.bytesToCopy)}", style = MaterialTheme.typography.titleSmall)
        }
    }
    if (plan.alreadyBackedUp > 0) {
        Row {
            Text(stringResource(R.string.backup_already), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(fileCount(plan.alreadyBackedUp), style = MaterialTheme.typography.bodyMedium)
        }
    }
    when (space) {
        is SpaceCheck.Enough -> Text(
            stringResource(R.string.backup_space_ok, formatBytes(space.requiredBytes), formatBytes(space.freeBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is SpaceCheck.NotEnough -> Banner(
            stringResource(R.string.backup_space_low, formatBytes(space.requiredBytes), formatBytes(space.freeBytes)),
            BannerTone.ERROR,
        )
        is SpaceCheck.Unknown -> Text(
            stringResource(R.string.backup_space_unknown, formatBytes(space.requiredBytes)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    StickerButton(
        text = when {
            resume -> stringResource(R.string.action_resume)
            plan.toCopy.isEmpty() -> stringResource(R.string.backup_relocate_start)
            else -> stringResource(R.string.backup_start)
        },
        onClick = onStart,
        modifier = Modifier.fillMaxWidth(),
        enabled = space !is SpaceCheck.NotEnough,
    )
}

/** Ergebnis gelöschter Dateien (wiederhergestellt bzw. im Backup gelöscht). */
@Composable
private fun MissingResultView(m: MissingSummary) {
    Banner(
        stringResource(R.string.missing_result, fileCount(m.restored), fileCount(m.deleted)) +
            (m.abortReason?.let { " – $it" } ?: ""),
        if (m.failures.isEmpty() && m.abortReason == null) BannerTone.SUCCESS else BannerTone.WARNING,
    )
    m.failures.take(10).forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ResultView(s: RunSummary) {
    Banner(
        if (s.isComplete && s.copied == 0 && s.moved > 0) stringResource(R.string.backup_result_moved, fileCount(s.moved))
        else if (s.isComplete && s.copied == 0 && s.adopted == 0) stringResource(R.string.backup_result_nothing)
        else if (s.isComplete) stringResource(R.string.backup_result_done, fileCount(s.copied), formatBytes(s.bytesCopied))
        else stringResource(R.string.backup_result_incomplete, fileCount(s.copied)) + (s.abortReason?.let { " – $it" } ?: ""),
        if (s.isComplete) BannerTone.SUCCESS else BannerTone.WARNING,
    )
    if (s.adopted > 0) Text(stringResource(R.string.backup_result_adopted, fileCount(s.adopted)), style = MaterialTheme.typography.bodySmall)
    if (s.moved > 0) Text(stringResource(R.string.backup_result_moved_count, fileCount(s.moved)), style = MaterialTheme.typography.bodySmall)
    if (s.failures.isNotEmpty()) {
        Text(stringResource(R.string.failures_header, fileCount(s.failures.size)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        s.failures.take(10).forEach { Text("• ${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
    }
    if (s.abortReason != null) {
        Text(
            stringResource(R.string.backup_result_resume_info),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
