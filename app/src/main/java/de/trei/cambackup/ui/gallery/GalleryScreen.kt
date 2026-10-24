package de.trei.cambackup.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trei.cambackup.R
import de.trei.cambackup.library.LibraryItem
import de.trei.cambackup.library.LibraryViewModel
import de.trei.cambackup.library.MediaStatus
import de.trei.cambackup.library.dayLabel
import de.trei.cambackup.library.formatDuration
import de.trei.cambackup.library.groupByDay
import de.trei.cambackup.ui.LibraryGate
import de.trei.cambackup.ui.components.StatusColors
import java.time.LocalDate

/** [needsDrive]: nur sinnvoll, wenn das Speichermedium verbunden ist (sonst ist der Status unbekannt). */
private enum class Filter(val label: String, val needsDrive: Boolean, val matches: (LibraryItem) -> Boolean) {
    ALL("Alle", false, { true }),
    OPEN("Nicht gesichert", true, { it.status == MediaStatus.NOT_BACKED_UP }),
    BACKED_UP("Gesichert", true, { it.status == MediaStatus.BACKED_UP }),
    COMPRESSED("Verkleinert", false, { it.status == MediaStatus.COMPRESSED }),
}

/** Raster aller Kamera-Dateien nach Aufnahmetag, mit Sicherungsstatus – ähnlich Google Fotos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(library: LibraryViewModel, contentPadding: PaddingValues) {
    val state by library.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var openIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    LibraryGate(state, library::refresh, contentPadding) { loaded ->
        val filters = Filter.entries.filter { loaded.driveConnected || !it.needsDrive }
        val active = filter.takeIf { it in filters } ?: Filter.ALL
        val filtered = remember(loaded.items, active) { loaded.items.filter(active.matches).sortedByDescending { it.media.dateTakenMillis } }
        val groups = remember(filtered) { groupByDay(filtered) }
        val today = remember { LocalDate.now() }
        val thumbPx = with(LocalDensity.current) { 160.dp.roundToPx() }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 8.dp,
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
                    DayHeader(dayLabel(day, today), items)
                }
                items(items.size, key = { i -> "m${items[i].media.uri}" }) { i ->
                    Tile(items[i], thumbPx, Modifier.animateItem()) { openIndex = start + i }
                }
            }
        }

        openIndex?.let { index ->
            if (index in filtered.indices) {
                MediaViewer(filtered, index, onClose = { openIndex = null })
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, items: List<LibraryItem>) {
    val allSafe = items.all { it.driveConnected && (it.status == MediaStatus.BACKED_UP || it.status == MediaStatus.COMPRESSED) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (allSafe) {
            Icon(painterResource(R.drawable.ic_drive_done), "Alle gesichert", tint = StatusColors.backedUp(), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Tile(item: LibraryItem, thumbPx: Int, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(1f).clickable(onClick = onClick)) {
        MediaThumbnail(item.media.uri, thumbPx, Modifier.fillMaxSize())
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

