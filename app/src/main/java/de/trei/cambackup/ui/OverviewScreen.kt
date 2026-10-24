package de.trei.cambackup.ui

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
import androidx.compose.foundation.lazy.LazyColumn
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

@Composable
fun OverviewScreen(library: LibraryViewModel, contentPadding: PaddingValues) {
    val state by library.state.collectAsStateWithLifecycle()
    LibraryGate(state, library::refresh, contentPadding) { loaded ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { CameraFolderCard(loaded.stats, loaded.driveConnected) }
            item { DriveSection(onLibraryChanged = library::refresh) }
        }
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
private fun CameraFolderCard(s: LibraryStats, driveConnected: Boolean) {
    FeatureCard(
        icon = R.drawable.ic_smartphone,
        title = stringResource(R.string.overview_media_title),
        subtitle = stringResource(R.string.overview_media_subtitle, fileCount(s.totalCount)),
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
