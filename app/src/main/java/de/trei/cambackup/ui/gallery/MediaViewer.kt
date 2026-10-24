package de.trei.cambackup.ui.gallery

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.trei.cambackup.library.LibraryItem
import de.trei.cambackup.library.MediaStatus
import de.trei.cambackup.library.formatDuration
import de.trei.cambackup.ui.components.StatusColors
import de.trei.cambackup.util.formatBytes
import java.text.DateFormat
import java.util.Date

/** Vollbild-Ansicht mit Wischen zwischen den Einträgen und Infos zum Sicherungsstatus. */
@Composable
fun MediaViewer(items: List<LibraryItem>, startIndex: Int, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pager = rememberPagerState(initialPage = startIndex) { items.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it].media.uri.toString() }) { page ->
                ViewerPage(items[page])
            }
            val current = items[pager.currentPage]
            // Kopfzeile
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Schließen", tint = Color.White) }
                Text(
                    current.media.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${pager.currentPage + 1} / ${items.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(12.dp))
            }
            InfoPanel(current, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ViewerPage(item: LibraryItem) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MediaThumbnail(item.media.uri, 2048, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, placeholder = Color.Black)
        if (item.media.isVideo) {
            FilledIconButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(item.media.uri, item.media.mimeType)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f)),
            ) {
                Icon(Icons.Default.PlayArrow, "Abspielen", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
    }
}

@Composable
private fun InfoPanel(item: LibraryItem, modifier: Modifier) {
    val m = item.media
    val date = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(m.dateTakenMillis))
    val (statusText, color) = when (item.status) {
        MediaStatus.NOT_BACKED_UP -> "Noch nicht gesichert" to Color.White.copy(alpha = 0.7f)
        MediaStatus.BACKED_UP -> "Original gesichert" to Color(0xFF9EC5FF)
        MediaStatus.COMPRESSED -> "Verkleinert – Original gesichert" to StatusColors.compressed
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // Zusätzlicher Abstand: im Vollbild-Dialog kommen die Systemleisten-Abstände nicht immer an
            .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(date, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Text(
            buildString {
                append(formatBytes(m.sizeBytes))
                if (m.isVideo) append(" · ${formatDuration(m.durationMs)}")
                val entry = item.entry
                if (item.status == MediaStatus.COMPRESSED && entry != null) append(" (Original ${formatBytes(entry.originalSize)})")
            },
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(item.status)
            if (item.status != MediaStatus.NOT_BACKED_UP) Spacer(Modifier.width(8.dp))
            Text(statusText, color = color, style = MaterialTheme.typography.labelLarge)
        }
        item.entry?.backupPath?.let {
            Text("Auf dem Medium: $it", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
        }
        item.entry?.compressionParams?.takeIf { item.status == MediaStatus.COMPRESSED }?.let {
            Text("Verkleinert mit: $it", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
