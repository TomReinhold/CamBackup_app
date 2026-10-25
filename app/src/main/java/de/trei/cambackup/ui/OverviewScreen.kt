package de.trei.cambackup.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trei.cambackup.R
import de.trei.cambackup.library.LibraryState
import de.trei.cambackup.library.LibraryStats
import de.trei.cambackup.library.LibraryViewModel
import de.trei.cambackup.media.MediaPermissions
import de.trei.cambackup.ui.components.BarSegment
import de.trei.cambackup.ui.components.FeatureCard
import de.trei.cambackup.ui.components.LegendStat
import de.trei.cambackup.ui.components.StackedBar
import de.trei.cambackup.ui.components.StatusColors
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes
import de.trei.cambackup.ui.components.StepIndicator
import de.trei.cambackup.freeup.compressTargets
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background

/** Die drei Schritte der Übersicht. */
private enum class Step { BACKUP, FREE_UP, DONE }

/**
 * Übersicht: oben die drei Schritte (Sichern · Verkleinern · Fertig), darunter immer nur EINE große
 * Karte für den aktuellen Schritt. Welcher Schritt dran ist, ergibt sich aus Backup und Verkleinern;
 * ein Tipp auf einen Schritt zeigt dessen Karte trotzdem an. Speichermedium, Zurückholen und die
 * Zahlen zu Fotos und Videos stehen klein darunter.
 */
@Composable
fun OverviewScreen(library: LibraryViewModel, contentPadding: PaddingValues) {
    val state by library.state.collectAsStateWithLifecycle()
    val driveVm: DriveViewModel = viewModel()
    val backupVm: BackupViewModel = viewModel()
    val freeUpVm: FreeUpViewModel = viewModel()
    val drive by driveVm.state.collectAsStateWithLifecycle()
    val backup by backupVm.state.collectAsStateWithLifecycle()
    val backupResult by backupVm.result.collectAsStateWithLifecycle()
    val freeUp by freeUpVm.state.collectAsStateWithLifecycle()
    val freeUpResult by freeUpVm.result.collectAsStateWithLifecycle()

    LibraryGate(state, library::refresh, contentPadding) { loaded ->
        val ready = drive as? DriveState.Ready
        val folder = ready?.info?.folderUri?.toString()
        // Beide Bereiche kennen ihren Stand auch, wenn gerade die Karte des anderen Schritts zu sehen ist
        LaunchedEffect(folder) {
            if (folder != null) {
                backupVm.prepareIfIdle(folder)
                freeUpVm.load(folder)
            }
        }
        // Welcher Schritt gerade dran ist; null, solange nur kurz verglichen/geladen wird – das soll
        // die Ansicht nicht umspringen lassen
        val current: Step? = when {
            ready == null -> Step.BACKUP
            backup is BackupState.Running || backup is BackupState.ApplyingMissing -> Step.BACKUP
            freeUp is FreeUpState.Working -> Step.FREE_UP
            backup is BackupState.Preparing -> null
            backupResult != null -> Step.BACKUP
            backup !is BackupState.Ready -> Step.BACKUP
            (backup as BackupState.Ready).let { it.plan.hasWork || it.missing.isNotEmpty() } -> Step.BACKUP
            freeUpResult != null -> Step.FREE_UP
            freeUp is FreeUpState.Loading -> null
            freeUp is FreeUpState.Overview -> (freeUp as FreeUpState.Overview).let {
                if (compressTargets(it.overview.candidates, it.minAgeDays, System.currentTimeMillis()).isNotEmpty()) Step.FREE_UP else Step.DONE
            }
            else -> Step.FREE_UP
        }
        var auto by rememberSaveable { mutableStateOf(Step.BACKUP) }
        LaunchedEffect(current) { if (current != null) auto = current }

        val pager = rememberPagerState(initialPage = auto.ordinal) { Step.entries.size }
        val scope = rememberCoroutineScope()
        // Nur wenn sich die Lage wirklich ändert (Backup fertig, Stick eingesteckt …), blättert die App selbst
        LaunchedEffect(auto) { pager.animateScrollToPage(auto.ordinal) }
        val goTo = { step: Step -> scope.launch { pager.animateScrollToPage(step.ordinal) }; Unit }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = contentPadding.calculateTopPadding() + 4.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val side = Modifier.padding(horizontal = 16.dp)
            StepIndicator(
                labels = listOf(stringResource(R.string.step_backup), stringResource(R.string.step_free_up), stringResource(R.string.step_done)),
                current = if (folder == null) 0 else pager.targetPage,
                done = Step.entries.filter { it.ordinal < auto.ordinal }.map { it.ordinal }.toSet(),
                onSelect = { i -> if (folder != null) goTo(Step.entries[i]) },
                modifier = side,
            )
            if (folder == null) {
                // Noch kein Backup-Ordner: die Karte zum Einrichten ist der erste Schritt
                DriveSection(side)
            } else {
                // Wischen nach links/rechts wechselt die Karte; die Seiten reichen bis an den Rand,
                // damit der Sticker-Schatten nicht abgeschnitten wird
                HorizontalPager(pager, verticalAlignment = Alignment.Top) { page ->
                    Box(side.padding(bottom = 8.dp)) {
                        when (Step.entries[page]) {
                            Step.BACKUP -> BackupPanel(
                                folderUri = folder,
                                onBackupFinished = {
                                    driveVm.refresh()
                                    library.refresh()
                                },
                                onNext = { goTo(Step.FREE_UP) },
                            )
                            Step.FREE_UP -> FreeUpPanel(folderUri = folder, onFinished = library::refresh)
                            Step.DONE -> SummaryCard(loaded.stats, allDone = auto == Step.DONE)
                        }
                    }
                }
                Column(side, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DriveSection()
                    RestorePanel(folderUri = folder, onFinished = library::refresh)
                }
            }
            CameraFolderCard(loaded.stats, loaded.driveConnected, side)
        }
    }
}

/** Schritt 3: Zusammenfassung – gelb mit „Alles erledigt!“, sobald gesichert und verkleinert ist. */
@Composable
private fun SummaryCard(s: LibraryStats, allDone: Boolean) {
    FeatureCard(
        icon = R.drawable.ic_step_done,
        title = stringResource(if (allDone) R.string.done_title else R.string.summary_title),
        subtitle = stringResource(if (allDone) R.string.done_subtitle else R.string.summary_subtitle),
        done = allDone,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(formatBytes(s.savedBytes), stringResource(R.string.done_freed), Modifier.weight(1f))
            StatTile("${s.backedUpCount + s.compressedCount}", stringResource(R.string.done_safe), Modifier.weight(1f))
        }
    }
}

/** Kleine Kachel mit großer Zahl. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Zeigt [content] erst, wenn der Zugriff auf die Fotos erteilt und die Medienordner gelesen sind. */
@Composable
fun LibraryGate(
    state: LibraryState,
    refresh: () -> Unit,
    contentPadding: PaddingValues,
    content: @Composable (LibraryState.Loaded) -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val openAppSettings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
    if (state is LibraryState.Loaded) {
        content(state)
        return
    }
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        when (state) {
            LibraryState.Loading, is LibraryState.Loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            LibraryState.NeedsPermission -> CenteredMessage(
                title = stringResource(R.string.permission_title),
                text = stringResource(R.string.permission_text),
                button = stringResource(R.string.permission_button),
                onClick = { permissionLauncher.launch(MediaPermissions.required) },
            )
            LibraryState.PartialAccess -> CenteredMessage(
                title = stringResource(R.string.permission_partial_title),
                text = stringResource(R.string.permission_partial_text),
                button = stringResource(R.string.permission_partial_button),
                onClick = openAppSettings,
            )
            is LibraryState.Error -> CenteredMessage(stringResource(R.string.error_title), state.message, stringResource(R.string.action_retry), refresh)
        }
    }
}

@Composable
private fun CenteredMessage(title: String, text: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_photo_library), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun CameraFolderCard(s: LibraryStats, driveConnected: Boolean, modifier: Modifier = Modifier) {
    FeatureCard(
        icon = R.drawable.ic_smartphone,
        title = stringResource(R.string.overview_media_title),
        subtitle = stringResource(R.string.overview_media_subtitle, fileCount(s.totalCount)),
        compact = true,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatBytes(s.totalBytes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.overview_used), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        }
        StackedBar(
            listOf(
                BarSegment(s.compressedBytes, StatusColors.compressed),
                BarSegment(s.backedUpBytes, StatusColors.backedUp()),
                BarSegment(s.openBytes + s.unknownBytes, StatusColors.open()),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendStat(StatusColors.compressed, stringResource(R.string.label_compressed),"${s.compressedCount}", formatBytes(s.compressedBytes), Modifier.weight(1f))
            if (driveConnected) {
                LegendStat(StatusColors.backedUp(), stringResource(R.string.status_backed_up), "${s.backedUpCount}", formatBytes(s.backedUpBytes), Modifier.weight(1f))
                LegendStat(StatusColors.open(), stringResource(R.string.status_open),"${s.openCount}", formatBytes(s.openBytes), Modifier.weight(1f))
            } else {
                LegendStat(StatusColors.open(), stringResource(R.string.status_unchecked),"${s.unknownCount}", formatBytes(s.unknownBytes), Modifier.weight(2f))
            }
        }
        if (!driveConnected) {
            Text(
                stringResource(R.string.overview_unchecked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (s.savedBytes > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_compress), null, tint = StatusColors.compressed, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.overview_saved, formatBytes(s.savedBytes)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
