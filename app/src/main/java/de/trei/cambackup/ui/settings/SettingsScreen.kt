package de.trei.cambackup.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.compress.SavingsEstimate
import de.trei.cambackup.media.MediaFolder
import de.trei.cambackup.settings.CompressionSettings
import de.trei.cambackup.settings.FolderLayout
import de.trei.cambackup.settings.VideoCodec
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val estimate by vm.estimate.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
                },
                actions = {
                    TextButton(onClick = vm::reset) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Standard")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FolderSettings(folders, settings.mediaFolders, vm::toggleFolder)
            PreviewCard(preview, onPrev = { vm.showPreview(-1) }, onNext = { vm.showPreview(1) })
            EstimateCard(estimate, settings)
            ImageSettings(settings, vm::update)
            VideoSettings(settings, vm::update)
            GeneralSettings(settings, vm::update)
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------- Vorschau

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewCard(state: PreviewState, onPrev: () -> Unit, onNext: () -> Unit) {
    var detail by rememberSaveable { mutableStateOf(false) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vorschau", style = MaterialTheme.typography.titleMedium)
                    Hint("Echte Kompression eines deiner Fotos")
                }
                if (state is PreviewState.Ready && state.count > 1) {
                    FilledTonalIconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vorheriges Bild") }
                    FilledTonalIconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nächstes Bild") }
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to "Ganzes Bild", true to "Detail 100 %").forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = detail == value,
                        onClick = { detail = value },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }

            when (state) {
                PreviewState.Loading -> PreviewPlaceholder { CircularProgressIndicator() }
                PreviewState.NoImages -> PreviewPlaceholder { Hint("Keine Fotos in den gewählten Ordnern gefunden.") }
                is PreviewState.Error -> PreviewPlaceholder { Hint("Vorschau nicht möglich: ${state.message}") }
                is PreviewState.Ready -> {
                    val img = state.images
                    CompareSlider(
                        before = if (detail) img.originalDetail else img.originalFull,
                        after = if (detail) img.compressedDetail else img.compressedFull,
                        beforeLabel = "Original",
                        afterLabel = "Verkleinert",
                    )
                    Text(
                        "${img.name} · Bild ${state.index + 1} von ${state.count}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SizeComparison(img)
                }
            }
        }
    }
}

@Composable
private fun PreviewPlaceholder(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SizeComparison(img: PreviewImages) {
    val saved = if (img.originalBytes > 0) 100 - (img.compressedBytes * 100 / img.originalBytes).toInt() else 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        SizeColumn("Original", formatBytes(img.originalBytes), "${img.originalSize.first} × ${img.originalSize.second}", Modifier.weight(1f))
        Text("→", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SizeColumn(
            "Verkleinert",
            formatBytes(img.compressedBytes),
            "${img.compressedSize.first} × ${img.compressedSize.second}",
            Modifier.weight(1f).padding(start = 16.dp),
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (saved > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ) {
            Text(
                if (saved > 0) "−$saved %" else "+${-saved} %",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SizeColumn(label: String, size: String, dims: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(size, style = MaterialTheme.typography.titleMedium)
        Text(dims, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Schätzung

@Composable
private fun EstimateCard(state: EstimateState, settings: CompressionSettings) {
    val e: SavingsEstimate? = when (state) {
        is EstimateState.Ready -> state.estimate
        is EstimateState.Calculating -> state.previous
        EstimateState.Loading -> null
    }
    val calculating = state !is EstimateState.Ready

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Voraussichtlicher Speicherplatz", style = MaterialTheme.typography.titleMedium)
            if (e == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Hint("Probebilder werden komprimiert …")
                return@Column
            }

            Row(verticalAlignment = Alignment.Bottom) {
                if (e.imageCount + e.videoCount == 0) {
                    Text(
                        "Noch nichts zu verkleinern",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        "≈ ${formatBytes(e.saved)}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("werden frei", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                }
                if (calculating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            // Balken umfassen alle Kamera-Dateien; zu junge Dateien bleiben unverändert (grau)
            val total = e.before + e.tooRecentBytes
            UsageBar("Jetzt", e.imagesBefore, e.videosBefore, e.tooRecentBytes, 0, total)
            UsageBar("Danach", e.imagesAfter, e.videosAfter, e.tooRecentBytes, e.saved, total)
            Legend(showUnchanged = e.tooRecentCount > 0)

            BreakdownRow("Bilder", e.imageCount, e.imagesBefore, e.imagesAfter)
            BreakdownRow("Videos", e.videoCount, e.videosBefore, e.videosAfter)

            if (e.tooRecentCount > 0) {
                Hint(
                    "${fileCount(e.tooRecentCount)} (${formatBytes(e.tooRecentBytes)}) " +
                        (if (e.tooRecentCount == 1) "ist" else "sind") +
                        " jünger als ${minAgeLabel(settings.minAgeDays)} und " +
                        (if (e.tooRecentCount == 1) "bleibt" else "bleiben") + " in Originalqualität.",
                )
            }
            Hint(
                "Schätzung: Bilder anhand von ${(state as? EstimateState.Ready)?.sampleCount ?: "einigen"} Probebildern, " +
                    "Videos anhand Dauer × Bitrate.",
            )
        }
    }
}

/** Gestapelter Balken: Bilder, Videos, unverändert bleibende Dateien und frei werdender Platz. */
@Composable
private fun UsageBar(label: String, images: Long, videos: Long, unchanged: Long, freed: Long, total: Long) {
    val totalF = total.coerceAtLeast(1).toFloat()
    val imgFrac by animateFloatAsState(images / totalF, label = "img")
    val vidFrac by animateFloatAsState(videos / totalF, label = "vid")
    val keepFrac by animateFloatAsState(unchanged / totalF, label = "keep")
    val freeFrac by animateFloatAsState(freed / totalF, label = "free")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(56.dp))
        Row(
            Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (imgFrac > 0f) Box(Modifier.fillMaxHeight().weight(imgFrac).background(MaterialTheme.colorScheme.primary))
            if (vidFrac > 0f) Box(Modifier.fillMaxHeight().weight(vidFrac).background(MaterialTheme.colorScheme.tertiary))
            if (keepFrac > 0f) Box(Modifier.fillMaxHeight().weight(keepFrac).background(MaterialTheme.colorScheme.outline))
            if (freeFrac > 0f) Box(Modifier.fillMaxHeight().weight(freeFrac).background(FreedColor))
            val rest = 1f - imgFrac - vidFrac - keepFrac - freeFrac
            if (rest > 0.001f) Spacer(Modifier.weight(rest))
        }
        Text(
            formatBytes(images + videos + unchanged),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(72.dp).padding(start = 8.dp),
            maxLines = 1,
        )
    }
}

private val FreedColor = Color(0xFF7CC68D)

@Composable
private fun Legend(showUnchanged: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = 56.dp)) {
        LegendItem(MaterialTheme.colorScheme.primary, "Bilder")
        LegendItem(MaterialTheme.colorScheme.tertiary, "Videos")
        if (showUnchanged) LegendItem(MaterialTheme.colorScheme.outline, "bleibt")
        LegendItem(FreedColor, "frei")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun BreakdownRow(label: String, count: Int, before: Long, after: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Hint(fileCount(count))
        }
        Text("${formatBytes(before)} → ${formatBytes(after)}", style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- Einstellungen

private typealias Update = ((CompressionSettings) -> CompressionSettings) -> Unit

/** Auswahl der zu sichernden Ordner, mit Anzahl und Größe. */
@Composable
private fun FolderSettings(folders: List<MediaFolder>?, selected: Set<String>, onToggle: (String) -> Unit) {
    SettingsSection("Medienordner", "Welche Ordner gesichert und verkleinert werden") {
        if (folders == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@SettingsSection
        }
        // Gewählte Ordner, die (noch) leer sind, trotzdem anzeigen, damit man sie abwählen kann
        val all = folders + selected.filter { s -> folders.none { it.relativePath == s } }.map { MediaFolder(it, 0, 0) }
        Column {
            all.forEach { f ->
                val checked = f.relativePath in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !(checked && selected.size == 1)) { onToggle(f.relativePath) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { onToggle(f.relativePath) }, enabled = !(checked && selected.size == 1))
                    Column(Modifier.weight(1f)) {
                        Text(folderLabel(f.relativePath), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            f.relativePath.trimEnd('/') + " · ${fileCount(f.count)} · ${formatBytes(f.bytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Hint(
            if (selected.size > 1) {
                "Mehrere Ordner: Auf dem Speichermedium bekommt jeder einen eigenen Unterordner (z. B. „DCIM/Camera/2026/10“). " +
                    "Bereits gesicherte Dateien bleiben, wo sie sind."
            } else {
                "Bei einem Ordner liegen die Sicherungen direkt im Geräteordner. Bei mehreren bekommt jeder einen eigenen Unterordner."
            },
        )
    }
}

/** Verständlicher Name für bekannte Ordner. */
private fun folderLabel(path: String): String = when (path.trimEnd('/')) {
    "DCIM/Camera" -> "Kamera"
    "Pictures/Screenshots", "DCIM/Screenshots" -> "Screenshots"
    "Movies" -> "Filme"
    "Pictures" -> "Bilder"
    "Download" -> "Downloads"
    else -> path.trimEnd('/').substringAfterLast('/')
}

@Composable
private fun ImageSettings(s: CompressionSettings, update: Update) {
    SettingsSection("Bilder", "Werden als JPEG neu gespeichert") {
        SegmentedSetting(
            label = "Maximale Kantenlänge",
            options = CompressionSettings.IMAGE_EDGE_OPTIONS,
            selected = s.imageMaxEdge,
            optionLabel = { it?.toString() ?: "Original" },
            onSelect = { v -> update { it.copy(imageMaxEdge = v) } },
            hint = when (s.imageMaxEdge) {
                2048 -> "≈ 4 MP – gut für Bildschirm und Teilen"
                3072 -> "≈ 9 MP – auch für Fotoabzüge bis ca. 20 × 30 cm"
                4096 -> "≈ 16 MP – kaum sichtbarer Unterschied zum Original"
                else -> "Auflösung bleibt, nur die JPEG-Qualität wird angepasst"
            },
        )
        SliderSetting(
            label = "JPEG-Qualität",
            value = s.jpegQuality,
            range = CompressionSettings.QUALITY_RANGE,
            step = 5,
            valueLabel = { "$it" },
            onChange = { v -> update { it.copy(jpegQuality = v) } },
            startHint = "kleiner",
            endHint = "besser",
        )
        SwitchSetting(
            title = "Standort (GPS) behalten",
            description = "Aufnahmedatum und Kameradaten bleiben immer erhalten",
            checked = s.keepGps,
            onChange = { v -> update { it.copy(keepGps = v) } },
        )
    }
}

@Composable
private fun VideoSettings(s: CompressionSettings, update: Update) {
    SettingsSection("Videos", "Werden neu kodiert – das dauert und braucht Akku") {
        SegmentedSetting(
            label = "Maximale Auflösung",
            options = CompressionSettings.VIDEO_EDGE_OPTIONS,
            selected = s.videoMaxShortEdge,
            optionLabel = { it?.let { p -> "${p}p" } ?: "Original" },
            onSelect = { v -> update { it.copy(videoMaxShortEdge = v) } },
        )
        SegmentedSetting(
            label = "Codec",
            options = VideoCodec.entries,
            selected = s.videoCodec,
            optionLabel = { it.label },
            onSelect = { v -> update { it.copy(videoCodec = v) } },
            hint = if (s.videoCodec == VideoCodec.HEVC) {
                "Etwa halb so groß bei gleicher Qualität; spielt auf aktuellen Geräten problemlos"
            } else {
                "Läuft überall, auch auf älteren Geräten und Fernsehern"
            },
        )
        SliderSetting(
            label = "Videobitrate",
            value = s.videoBitrateMbps,
            range = CompressionSettings.BITRATE_RANGE,
            step = 1,
            valueLabel = { "$it Mbit/s" },
            onChange = { v -> update { it.copy(videoBitrateMbps = v) } },
            startHint = "≈ ${formatBytes(bytesPerMinute(CompressionSettings.BITRATE_RANGE.first))}/min",
            endHint = "≈ ${formatBytes(bytesPerMinute(CompressionSettings.BITRATE_RANGE.last))}/min",
        )
        Hint("Aktuell ≈ ${formatBytes(bytesPerMinute(s.videoBitrateMbps))} pro Minute Video")
        SwitchSetting(
            title = "Auf 30 Bilder/s begrenzen",
            description = "Spart bei 60-fps-Videos zusätzlich Platz",
            checked = s.videoLimit30Fps,
            onChange = { v -> update { it.copy(videoLimit30Fps = v) } },
        )
    }
}

private fun bytesPerMinute(mbps: Int): Long =
    (mbps * 1_000_000L + CompressionSettings.AUDIO_BITRATE_KBPS * 1000L) / 8 * 60

@Composable
private fun GeneralSettings(s: CompressionSettings, update: Update) {
    SettingsSection("Allgemein") {
        SegmentedSetting(
            label = "Erst verkleinern ab einem Alter von",
            options = CompressionSettings.MIN_AGE_OPTIONS,
            selected = s.minAgeDays,
            optionLabel = ::minAgeShort,
            onSelect = { v -> update { it.copy(minAgeDays = v) } },
            hint = "Neuere Fotos und Videos bleiben auf dem Smartphone in Originalqualität",
        )
        SliderSetting(
            label = "Mindest-Ersparnis",
            value = s.minSavingsPercent,
            range = CompressionSettings.MIN_SAVINGS_RANGE,
            step = 5,
            valueLabel = { "$it %" },
            onChange = { v -> update { it.copy(minSavingsPercent = v) } },
        )
        Hint("Dateien, die dadurch nicht mindestens ${s.minSavingsPercent} % kleiner werden, bleiben unverändert.")
        SegmentedSetting(
            label = "Ordner auf dem Speichermedium",
            options = FolderLayout.entries,
            selected = s.folderLayout,
            optionLabel = { if (it == FolderLayout.YEAR_MONTH) "Jahr / Monat" else "Ein Ordner" },
            onSelect = { v -> update { it.copy(folderLayout = v) } },
            hint = "Gilt für neu gesicherte Dateien",
        )
        SwitchSetting(
            title = "Probelauf",
            description = "Nur sichern – auf dem Smartphone wird nichts ersetzt",
            checked = s.dryRun,
            onChange = { v -> update { it.copy(dryRun = v) } },
        )
        if (!s.dryRun) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Nach erfolgreicher, geprüfter Sicherung werden Fotos und Videos auf dem Smartphone " +
                            "durch die verkleinerten Versionen ersetzt. Die Originale liegen dann nur noch auf dem Speichermedium.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

private fun minAgeShort(days: Int) = when (days) {
    0 -> "sofort"
    365 -> "1 Jahr"
    else -> "$days T."
}

private fun minAgeLabel(days: Int) = when (days) {
    0 -> "0 Tage"
    1 -> "1 Tag"
    365 -> "1 Jahr"
    else -> "$days Tage"
}
