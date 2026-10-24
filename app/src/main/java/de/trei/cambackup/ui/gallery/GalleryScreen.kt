package de.trei.cambackup.ui.gallery

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.R
import de.trei.cambackup.library.LibraryItem
import de.trei.cambackup.library.LibraryViewModel
import de.trei.cambackup.library.MediaStatus
import de.trei.cambackup.library.dayLabel
import de.trei.cambackup.library.formatDuration
import de.trei.cambackup.library.groupByDay
import de.trei.cambackup.ui.LibraryGate
import de.trei.cambackup.ui.components.JobControls
import de.trei.cambackup.ui.components.StatusColors
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes
import java.time.LocalDate

/** Gesichertes Original, das bewusst nicht verkleinert werden soll. */
val LibraryItem.excluded: Boolean get() = status == MediaStatus.BACKED_UP && entry?.keepOriginal == true

/** [needsDrive]: nur sinnvoll, wenn das Speichermedium verbunden ist (sonst ist der Status unbekannt). */
private enum class Filter(val label: String, val needsDrive: Boolean, val matches: (LibraryItem) -> Boolean) {
    ALL("Alle", false, { true }),
    OPEN("Nicht gesichert", true, { it.status == MediaStatus.NOT_BACKED_UP }),
    BACKED_UP("Gesichert", true, { it.status == MediaStatus.BACKED_UP }),
    COMPRESSED("Verkleinert", false, { it.status == MediaStatus.COMPRESSED }),
    EXCLUDED("Nicht verkleinern", true, { it.excluded }),
}

/**
 * Raster aller Kamera-Dateien nach Aufnahmetag, mit Sicherungsstatus – ähnlich Google Fotos.
 * Langes Drücken wählt Dateien aus; für die Auswahl lassen sich Originale gezielt zurückholen oder
 * vom Verkleinern ausschließen ([folderUri] = verbundener Backup-Ordner, sonst null).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    library: LibraryViewModel,
    contentPadding: PaddingValues,
    folderUri: String?,
    actions: GalleryActionsViewModel = viewModel(),
) {
    val state by library.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var openIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmRestore by remember { mutableStateOf<List<LibraryItem>?>(null) }

    val context = LocalContext.current
    val message by actions.message.collectAsStateWithLifecycle()
    val trialRunning by actions.trialRunning.collectAsStateWithLifecycle()
    val trialProgress by actions.trialProgress.collectAsStateWithLifecycle()
    val trialPaused by actions.trialPaused.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            actions.messageShown()
        }
    }
    // System-Dialog „Darf CamBackup diese Dateien ändern?“ – erst danach wird zurückgeholt
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) actions.restoreApproved()
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptyList() }

    LibraryGate(state, library::refresh, contentPadding) { loaded ->
        val filters = Filter.entries.filter { f ->
            (loaded.driveConnected || !f.needsDrive) && (f != Filter.EXCLUDED || f == filter || loaded.items.any(f.matches))
        }
        val active = filter.takeIf { it in filters } ?: Filter.ALL
        val filtered = remember(loaded.items, active) { loaded.items.filter(active.matches).sortedByDescending { it.media.dateTakenMillis } }
        val groups = remember(filtered) { groupByDay(filtered) }
        val today = remember { LocalDate.now() }
        val thumbPx = with(LocalDensity.current) { 160.dp.roundToPx() }
        val selection = remember(loaded.items, selected) {
            val keys = selected.toSet()
            loaded.items.filter { it.media.uri.toString() in keys }
        }
        val selecting = selection.isNotEmpty()
        fun toggle(items: List<LibraryItem>) {
            val keys = items.map { it.media.uri.toString() }
            selected = if (keys.all { it in selected }) selected - keys.toSet() else (selected + keys).distinct()
        }

        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding(),
                    // Unter der Auswahlleiste bleibt das Raster erreichbar
                    bottom = contentPadding.calculateBottomPadding() + if (selecting || trialProgress != null) 160.dp else 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "filter") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        filters.forEach { f ->
                            val count = loaded.items.count(f.matches)
                            FilterChip(
                                selected = active == f,
                                onClick = { filter = f },
                                label = { Text("${f.label} · $count") },
                            )
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        Text(
                            "Keine Einträge",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                var offset = 0
                groups.forEach { (day, items) ->
                    val start = offset
                    offset += items.size
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h$day") {
                        // In der Auswahl wählt ein Tipp auf den Tag alle Dateien dieses Tages (ab)
                        DayHeader(dayLabel(day, today), items, onClick = if (selecting) ({ toggle(items) }) else null)
                    }
                    items(items.size, key = { i -> "m${items[i].media.uri}" }) { i ->
                        val item = items[i]
                        Tile(
                            item = item,
                            thumbPx = thumbPx,
                            selected = item.media.uri.toString() in selected,
                            modifier = Modifier.animateItem(),
                            onClick = { if (selecting) toggle(listOf(item)) else openIndex = start + i },
                            onLongClick = { toggle(listOf(item)) },
                        )
                    }
                }
            }

            trialProgress?.takeIf { !selecting }?.let { (done, total) ->                TrialBar(                    done = done,                    total = total,                    paused = trialPaused,                    onPause = actions::setTrialPaused,                    onCancel = actions::cancelTrial,                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding()),                )            }
            if (selecting) {
                SelectionBar(
                    selection = selection,
                    actionsEnabled = folderUri != null && loaded.driveConnected,
                    onClear = { selected = emptyList() },
                    onRestore = { confirmRestore = it },
                    onKeep = { items, keep ->
                        folderUri?.let { actions.setKeepOriginal(it, items, keep) }
                        selected = emptyList()
                    },
                    onTrial = {
                        actions.trial(it)
                        selected = emptyList()
                    },
                    trialRunning = trialRunning,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding()),
                )
            }
        }

        confirmRestore?.let { items ->
            RestoreDialog(
                items = items,
                onDismiss = { confirmRestore = null },
                onConfirm = { keep ->
                    confirmRestore = null
                    folderUri?.let { folder ->
                        writeRequest.launch(IntentSenderRequest.Builder(actions.restoreRequest(folder, items, keep).intentSender).build())
                    }
                    selected = emptyList()
                },
            )
        }

        openIndex?.let { index ->
            if (index in filtered.indices) {
                MediaViewer(filtered, index, onClose = { openIndex = null })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(
    selection: List<LibraryItem>,
    actionsEnabled: Boolean,
    onClear: () -> Unit,
    onRestore: (List<LibraryItem>) -> Unit,
    onKeep: (List<LibraryItem>, Boolean) -> Unit,
    onTrial: (List<LibraryItem>) -> Unit,
    trialRunning: Boolean,
    modifier: Modifier,
) {
    val compressed = selection.filter { it.status == MediaStatus.COMPRESSED }
    val excludable = selection.filter { it.status == MediaStatus.BACKED_UP && !it.excluded }
    val excluded = selection.filter { it.excluded }
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Auswahl aufheben") }
                Text("${selection.size} ausgewählt", style = MaterialTheme.typography.titleMedium)
            }
            // Probe geht immer (auch ohne Medium): das Original bleibt unverändert
            val trialable = selection.filter { it.status != MediaStatus.COMPRESSED }
            FlowRow(
                Modifier.padding(start = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (actionsEnabled && compressed.isNotEmpty()) {
                    FilledTonalButton(onClick = { onRestore(compressed) }) { Text("Original zurückholen (${compressed.size})") }
                }
                if (actionsEnabled && excludable.isNotEmpty()) {
                    FilledTonalButton(onClick = { onKeep(excludable, true) }) { Text("Nicht verkleinern (${excludable.size})") }
                }
                if (actionsEnabled && excluded.isNotEmpty()) {
                    FilledTonalButton(onClick = { onKeep(excluded, false) }) { Text("Verkleinern erlauben (${excluded.size})") }
                }
                if (trialable.isNotEmpty()) {
                    OutlinedButton(onClick = { onTrial(trialable) }, enabled = !trialRunning) {
                        Text(if (trialRunning) "Probe läuft …" else "Probe verkleinern (${trialable.size})")
                    }
                }
            }
            if (!actionsEnabled && (compressed.isNotEmpty() || excludable.isNotEmpty() || excluded.isNotEmpty())) {
                Text(
                    "Speichermedium verbinden, um Originale zurückzuholen oder Dateien auszuschließen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RestoreDialog(items: List<LibraryItem>, onDismiss: () -> Unit, onConfirm: (keepOriginal: Boolean) -> Unit) {
    var keep by rememberSaveable { mutableStateOf(true) }
    val extra = items.sumOf { (it.entry?.originalSize ?: it.media.sizeBytes) - it.media.sizeBytes }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Originale zurückholen?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${fileCount(items.size)} werden wieder in voller Größe vom Speichermedium geholt. Das belegt zusätzlich ${formatBytes(extra)} auf dem Smartphone.")
                Row(
                    Modifier.fillMaxWidth().toggleable(value = keep, role = Role.Checkbox, onValueChange = { keep = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = keep, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Danach nicht erneut verkleinern", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(keep) }) { Text("Zurückholen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun DayHeader(label: String, items: List<LibraryItem>, onClick: (() -> Unit)?) {
    val allSafe = items.all { it.driveConnected && (it.status == MediaStatus.BACKED_UP || it.status == MediaStatus.COMPRESSED) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (allSafe) {
            Icon(painterResource(R.drawable.ic_drive_done), "Alle gesichert", tint = StatusColors.backedUp(), modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(item: LibraryItem, thumbPx: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier),
    ) {
        MediaThumbnail(item.media.uri, thumbPx, Modifier.fillMaxSize().then(if (selected) Modifier.padding(10.dp) else Modifier))
        // Leichter Verlauf, damit die weißen Symbole auf hellen Bildern lesbar bleiben
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f)),
            ),
        )
        if (item.media.isVideo) {
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(item.media.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle, "Ausgewählt", tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(24.dp).clip(CircleShape).background(Color.White),
            )
        }
        if (item.excluded) {
            Icon(Icons.Default.Lock, "Wird nicht verkleinert", tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp))
        }
        StatusBadge(item.status, Modifier.align(Alignment.BottomEnd).padding(6.dp))
    }
}

@Composable
fun StatusBadge(status: MediaStatus, modifier: Modifier = Modifier) {
    when (status) {
        MediaStatus.NOT_BACKED_UP, MediaStatus.UNKNOWN -> Unit
        MediaStatus.BACKED_UP -> Icon(
            painterResource(R.drawable.ic_drive_done), "Gesichert", tint = Color.White, modifier = modifier.size(18.dp),
        )
        MediaStatus.COMPRESSED -> Box(
            modifier.size(22.dp).clip(CircleShape).background(StatusColors.compressed),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_compress), "Verkleinert", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/** Fortschritt der Probe-Verkleinerung mit Pausieren und Abbrechen. */
@Composable
private fun TrialBar(done: Int, total: Int, paused: Boolean, onPause: (Boolean) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (paused) "Pausiert – " else "") + "Probe: ${minOf(done + 1, total)} von $total",
                style = MaterialTheme.typography.titleSmall,
            )
            LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
            JobControls(paused, onPause, onCancel)
        }
    }
}
