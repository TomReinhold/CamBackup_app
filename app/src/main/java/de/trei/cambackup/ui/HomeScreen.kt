package de.trei.cambackup.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.media.MediaPermissions
import de.trei.cambackup.media.MediaSummary
import de.trei.cambackup.util.formatBytes
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(modifier: Modifier = Modifier, vm: HomeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.refresh() }

    // Auch nach Rückkehr aus den System-Einstellungen neu prüfen
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }

    Box(modifier.fillMaxSize()) {
        when (val s = state) {
            HomeState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

            HomeState.NeedsPermission -> Message(
                text = "CamBackup benötigt Zugriff auf alle Fotos und Videos, um den Kameraordner sichern zu können.",
                buttonText = "Zugriff erlauben",
                onClick = { permissionLauncher.launch(MediaPermissions.required) },
            )

            HomeState.PartialAccess -> Message(
                text = "Es wurde nur Zugriff auf ausgewählte Fotos erteilt. Für ein vollständiges Backup bitte " +
                    "in den Einstellungen unter „Fotos und Videos“ → „Immer alle zulassen“ wählen.",
                buttonText = "Einstellungen öffnen",
                onClick = openAppSettings,
            )

            is HomeState.Error -> Message(
                text = "Fehler beim Lesen des Kameraordners:\n${s.message}",
                buttonText = "Erneut versuchen",
                onClick = vm::refresh,
            )

            is HomeState.Loaded -> MediaOverview(s.summary, s.items)
        }
    }
}

@Composable
private fun Message(text: String, buttonText: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center)
        Button(onClick = onClick) { Text(buttonText) }
    }
}

@Composable
private fun MediaOverview(summary: MediaSummary, items: List<MediaItem>) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { DriveSection(neededBytes = summary.totalBytes, modifier = Modifier.padding(top = 16.dp)) }
        item { SummaryCard(summary) }
        items(items, key = { (if (it.isVideo) "v" else "i") + it.id }) { item ->
            MediaRow(item)
            HorizontalDivider()
        }
    }
}

@Composable
private fun SummaryCard(summary: MediaSummary) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Kameraordner (DCIM/Camera)", style = MaterialTheme.typography.titleMedium)
            Text("Bilder: ${summary.imageCount} (${formatBytes(summary.imageBytes)})")
            Text("Videos: ${summary.videoCount} (${formatBytes(summary.videoBytes)})")
            Text(
                "Gesamt: ${summary.totalCount} Dateien, ${formatBytes(summary.totalBytes)}",
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun MediaRow(item: MediaItem) {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.dateTakenMillis))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text((if (item.isVideo) "🎬 " else "🖼 ") + item.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(date, style = MaterialTheme.typography.bodySmall)
        }
        Text(formatBytes(item.sizeBytes), style = MaterialTheme.typography.bodyMedium)
    }
}
