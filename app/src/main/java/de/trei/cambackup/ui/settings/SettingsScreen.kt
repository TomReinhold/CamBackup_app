package de.trei.cambackup.ui.settings

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.annotation.StringRes
import de.trei.cambackup.util.L10n
import de.trei.cambackup.util.AppLanguage
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import de.trei.cambackup.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.compress.SavingsEstimate
import de.trei.cambackup.media.MediaFolder
import de.trei.cambackup.settings.CompressionSettings
import de.trei.cambackup.settings.FolderLayout
import de.trei.cambackup.settings.VideoCodec
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes

/** Unterseiten der Einstellungen. */
enum class SettingsPage(@StringRes val title: Int) {
    FOLDERS(R.string.settings_folders),
    STORAGE(R.string.settings_storage),
    PHOTOS(R.string.settings_photos_title),
    VIDEOS(R.string.settings_videos_title),
    RULES(R.string.settings_rules),
    LANGUAGE(R.string.settings_language),
}

/**
 * Einstellungen als Übersicht mit Gruppen; jeder Eintrag zeigt den aktuellen Wert und öffnet eine
 * eigene Unterseite. [bottomPadding]: Platz für die Navigationsleiste.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(bottomPadding: Dp, vm: SettingsViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val estimate by vm.estimate.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    BackHandler(enabled = page != null) { page = null }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page?.title ?: R.string.tab_settings)) },
                navigationIcon = {
                    if (page != null) IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                scrollBehavior = scrollBehavior,
                // Statusleiste und Warnstreifen liegen schon darüber (MainActivity)
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        // Jede Seite mit eigenem Scrollzustand, damit eine Unterseite oben beginnt
        val scroll = key(page) { rememberScrollState() }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (page) {
                null -> {
                    SettingsHome(settings, estimate, folders) { page = it }
                    TextButton(onClick = { confirmReset = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_reset))
                    }
                }
                SettingsPage.FOLDERS -> FolderSettings(folders, settings.mediaFolders, vm::toggleFolder)
                SettingsPage.STORAGE -> StorageSettings(settings, vm::update)
                SettingsPage.PHOTOS -> {
                    PreviewCard(preview, onPrev = { vm.showPreview(-1) }, onNext = { vm.showPreview(1) })
                    ImageSettings(settings, vm::update)
                }
                SettingsPage.VIDEOS -> VideoSettings(settings, vm::update)
                SettingsPage.RULES -> RulesSettings(settings, vm::update)
                SettingsPage.LANGUAGE -> LanguageSettings()
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_text)) },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.reset() }) { Text(stringResource(R.string.settings_reset_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Startseite der Einstellungen: Schätzung und Gruppen mit Kurzfassung der Werte. */
@Composable
private fun SettingsHome(
    s: CompressionSettings,
    estimate: EstimateState,
    folders: List<MediaFolder>?,
    open: (SettingsPage) -> Unit,
) {
    EstimateCard(estimate, s)

    SettingsGroup(stringResource(R.string.settings_group_backup)) {
        NavItem(
            R.drawable.ic_folder, stringResource(R.string.settings_folders),
            s.mediaFolders.sortedBy { it != CompressionSettings.DEFAULT_FOLDER }.joinToString { folderLabel(it) },
        ) { open(SettingsPage.FOLDERS) }
        NavItem(
            R.drawable.ic_usb, stringResource(R.string.settings_storage),
            stringResource(layoutLabel(s.folderLayout)) +
                if (s.separateFolders) stringResource(R.string.settings_per_folder_suffix) else "",
        ) { open(SettingsPage.STORAGE) }
    }

    SettingsGroup(stringResource(R.string.settings_group_shrink)) {
        NavItem(
            R.drawable.ic_image, stringResource(R.string.settings_photos),
            (s.imageMaxEdge?.let { stringResource(R.string.settings_photos_max, it) } ?: stringResource(R.string.settings_photos_original)) +
                stringResource(R.string.settings_photos_quality, s.jpegQuality) +
                stringResource(if (s.keepGps) R.string.settings_photos_with_gps else R.string.settings_photos_without_gps),
        ) { open(SettingsPage.PHOTOS) }
        NavItem(
            R.drawable.ic_videocam, stringResource(R.string.settings_videos),
            (s.videoMaxShortEdge?.let { "${it}p" } ?: stringResource(R.string.settings_videos_original)) + " · ${s.videoCodec.label} · ${s.videoBitrateMbps} Mbit/s" +
                if (s.videoLimit30Fps) " · 30 fps" else "",
        ) { open(SettingsPage.VIDEOS) }
        NavItem(
            R.drawable.ic_schedule, stringResource(R.string.settings_rules),
            (if (s.minAgeDays == 0) stringResource(R.string.settings_rules_now) else stringResource(R.string.settings_rules_from, minAgeLabel(s.minAgeDays))) +
                stringResource(R.string.settings_rules_min_saving, s.minSavingsPercent),
            badge = if (s.dryRun) stringResource(R.string.settings_dry_run) else null,
        ) { open(SettingsPage.RULES) }
    }

    SettingsGroup(stringResource(R.string.settings_group_app)) {
        NavItem(R.drawable.ic_language, stringResource(R.string.settings_language), stringResource(languageLabel(AppLanguage.current(LocalContext.current)))) {
            open(SettingsPage.LANGUAGE)
        }
    }
}

@StringRes
private fun languageLabel(language: AppLanguage) = when (language) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.GERMAN -> R.string.language_german
    AppLanguage.ENGLISH -> R.string.language_english
}

/** Sprache der App: wie das System (sonst Englisch), Deutsch oder Englisch. */
@Composable
private fun LanguageSettings() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppLanguage.current(context)) }
    SettingsSection(stringResource(R.string.settings_language), stringResource(R.string.language_hint)) {
        AppLanguage.entries.forEach { language ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = current == language, role = Role.RadioButton) {
                        if (current != language) {
                            current = language
                            AppLanguage.set(context, language)
                            // Vor Android 13 übernimmt erst ein Neustart die Sprache überall (auch in Benachrichtigungen)
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) restartApp(context)
                        }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == language, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(languageLabel(language)), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(intent.component))
    Runtime.getRuntime().exit(0)
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
        ElevatedCard(Modifier.fillMaxWidth()) { Column { content() } }
    }
}

@Composable
private fun NavItem(@DrawableRes icon: Int, title: String, summary: String, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                badge?.let {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(stringResource(R.string.preview_title), style = MaterialTheme.typography.titleMedium)
                    Hint(stringResource(R.string.preview_hint))
                }
                if (state is PreviewState.Ready && state.count > 1) {
                    FilledTonalIconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.preview_prev)) }
                    FilledTonalIconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.preview_next)) }
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to stringResource(R.string.preview_whole), true to stringResource(R.string.preview_detail)).forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = detail == value,
                        onClick = { detail = value },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }

            when (state) {
                PreviewState.Loading -> PreviewPlaceholder { CircularProgressIndicator() }
                PreviewState.NoImages -> PreviewPlaceholder { Hint(stringResource(R.string.preview_no_images)) }
                is PreviewState.Error -> PreviewPlaceholder { Hint(stringResource(R.string.preview_error, state.message)) }
                is PreviewState.Ready -> {
                    val img = state.images
                    CompareSlider(
                        before = if (detail) img.originalDetail else img.originalFull,
                        after = if (detail) img.compressedDetail else img.compressedFull,
                        beforeLabel = stringResource(R.string.label_original),
                        afterLabel = stringResource(R.string.label_compressed),
                    )
                    Text(
                        stringResource(R.string.preview_position, img.name, state.index + 1, state.count),
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
        SizeColumn(stringResource(R.string.label_original), formatBytes(img.originalBytes), "${img.originalSize.first} × ${img.originalSize.second}", Modifier.weight(1f))
        Text("→", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SizeColumn(
            stringResource(R.string.label_compressed),
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
            Text(stringResource(R.string.estimate_title), style = MaterialTheme.typography.titleMedium)
            if (e == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Hint(stringResource(R.string.estimate_calculating))
                return@Column
            }

            Row(verticalAlignment = Alignment.Bottom) {
                if (e.imageCount + e.videoCount == 0) {
                    Text(
                        stringResource(R.string.estimate_nothing),
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
                    Text(stringResource(R.string.estimate_freed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                }
                if (calculating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            // Balken umfassen alle Kamera-Dateien; zu junge Dateien bleiben unverändert (grau)
            val total = e.before + e.tooRecentBytes
            UsageBar(stringResource(R.string.estimate_now), e.imagesBefore, e.videosBefore, e.tooRecentBytes, 0, total)
            UsageBar(stringResource(R.string.estimate_after), e.imagesAfter, e.videosAfter, e.tooRecentBytes, e.saved, total)
            Legend(showUnchanged = e.tooRecentCount > 0)

            BreakdownRow(stringResource(R.string.label_images), e.imageCount, e.imagesBefore, e.imagesAfter)
            BreakdownRow(stringResource(R.string.label_videos), e.videoCount, e.videosBefore, e.videosAfter)

            if (e.tooRecentCount > 0) {
                Hint(
                    pluralStringResource(
                        R.plurals.estimate_too_recent, e.tooRecentCount,
                        fileCount(e.tooRecentCount), formatBytes(e.tooRecentBytes), minAgeLabel(settings.minAgeDays),
                    ),
                )
            }
            Hint(
                (state as? EstimateState.Ready)?.sampleCount?.let { stringResource(R.string.estimate_basis, it) }
                    ?: stringResource(R.string.estimate_basis_some),
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
        LegendItem(MaterialTheme.colorScheme.primary, stringResource(R.string.label_images))
        LegendItem(MaterialTheme.colorScheme.tertiary, stringResource(R.string.label_videos))
        if (showUnchanged) LegendItem(MaterialTheme.colorScheme.outline, stringResource(R.string.estimate_legend_unchanged))
        LegendItem(FreedColor, stringResource(R.string.estimate_legend_free))
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
    SettingsSection(stringResource(R.string.settings_folders), stringResource(R.string.folders_subtitle)) {
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
                stringResource(R.string.folders_hint_multiple)
            } else {
                stringResource(R.string.folders_hint_single)
            },
        )
    }
}

/** Verständlicher Name für bekannte Ordner. */
private fun folderLabel(path: String): String = when (path.trimEnd('/')) {
    "DCIM/Camera" -> L10n.str(R.string.folder_camera)
    "Pictures/Screenshots", "DCIM/Screenshots" -> L10n.str(R.string.folder_screenshots)
    "Movies" -> L10n.str(R.string.folder_movies)
    "Pictures" -> L10n.str(R.string.folder_pictures)
    "Download" -> L10n.str(R.string.folder_downloads)
    else -> path.trimEnd('/').substringAfterLast('/')
}

@Composable
private fun ImageSettings(s: CompressionSettings, update: Update) {
    SettingsSection(stringResource(R.string.label_images), stringResource(R.string.photos_subtitle)) {
        SegmentedSetting(
            label = stringResource(R.string.photos_max_edge),
            options = CompressionSettings.IMAGE_EDGE_OPTIONS,
            selected = s.imageMaxEdge,
            optionLabel = { it?.toString() ?: L10n.str(R.string.label_original) },
            onSelect = { v -> update { it.copy(imageMaxEdge = v) } },
            hint = when (s.imageMaxEdge) {
                2048 -> stringResource(R.string.photos_edge_2048)
                3072 -> stringResource(R.string.photos_edge_3072)
                4096 -> stringResource(R.string.photos_edge_4096)
                else -> stringResource(R.string.photos_edge_original)
            },
        )
        SliderSetting(
            label = stringResource(R.string.photos_quality),
            value = s.jpegQuality,
            range = CompressionSettings.QUALITY_RANGE,
            step = 5,
            valueLabel = { "$it" },
            onChange = { v -> update { it.copy(jpegQuality = v) } },
            startHint = stringResource(R.string.photos_quality_smaller),
            endHint = stringResource(R.string.photos_quality_better),
        )
        SwitchSetting(
            title = stringResource(R.string.photos_keep_gps),
            description = stringResource(R.string.photos_keep_gps_hint),
            checked = s.keepGps,
            onChange = { v -> update { it.copy(keepGps = v) } },
        )
    }
}

@Composable
private fun VideoSettings(s: CompressionSettings, update: Update) {
    SettingsSection(stringResource(R.string.label_videos), stringResource(R.string.videos_subtitle)) {
        SegmentedSetting(
            label = stringResource(R.string.videos_max_resolution),
            options = CompressionSettings.VIDEO_EDGE_OPTIONS,
            selected = s.videoMaxShortEdge,
            optionLabel = { it?.let { p -> "${p}p" } ?: L10n.str(R.string.label_original) },
            onSelect = { v -> update { it.copy(videoMaxShortEdge = v) } },
        )
        SegmentedSetting(
            label = "Codec",
            options = VideoCodec.entries,
            selected = s.videoCodec,
            optionLabel = { it.label },
            onSelect = { v -> update { it.copy(videoCodec = v) } },
            hint = if (s.videoCodec == VideoCodec.HEVC) {
                stringResource(R.string.videos_hevc_hint)
            } else {
                stringResource(R.string.videos_h264_hint)
            },
        )
        SliderSetting(
            label = stringResource(R.string.videos_bitrate),
            value = s.videoBitrateMbps,
            range = CompressionSettings.BITRATE_RANGE,
            step = 1,
            valueLabel = { "$it Mbit/s" },
            onChange = { v -> update { it.copy(videoBitrateMbps = v) } },
            startHint = "≈ ${formatBytes(bytesPerMinute(CompressionSettings.BITRATE_RANGE.first))}/min",
            endHint = "≈ ${formatBytes(bytesPerMinute(CompressionSettings.BITRATE_RANGE.last))}/min",
        )
        Hint(stringResource(R.string.videos_per_minute, formatBytes(bytesPerMinute(s.videoBitrateMbps))))
        SwitchSetting(
            title = stringResource(R.string.videos_limit_30),
            description = stringResource(R.string.videos_limit_30_hint),
            checked = s.videoLimit30Fps,
            onChange = { v -> update { it.copy(videoLimit30Fps = v) } },
        )
    }
}

private fun bytesPerMinute(mbps: Int): Long =
    (mbps * 1_000_000L + CompressionSettings.AUDIO_BITRATE_KBPS * 1000L) / 8 * 60

@Composable
private fun RulesSettings(s: CompressionSettings, update: Update) {
    SettingsSection(stringResource(R.string.rules_title), stringResource(R.string.rules_subtitle)) {
        SegmentedSetting(
            label = stringResource(R.string.rules_min_age),
            options = CompressionSettings.MIN_AGE_OPTIONS,
            selected = s.minAgeDays,
            optionLabel = ::minAgeShort,
            onSelect = { v -> update { it.copy(minAgeDays = v) } },
            hint = stringResource(R.string.rules_min_age_hint),
        )
        SliderSetting(
            label = stringResource(R.string.rules_min_saving),
            value = s.minSavingsPercent,
            range = CompressionSettings.MIN_SAVINGS_RANGE,
            step = 5,
            valueLabel = { "$it %" },
            onChange = { v -> update { it.copy(minSavingsPercent = v) } },
        )
        Hint(stringResource(R.string.rules_min_saving_hint, s.minSavingsPercent))
        SwitchSetting(
            title = stringResource(R.string.settings_dry_run),
            description = stringResource(R.string.rules_dry_run_hint),
            checked = s.dryRun,
            onChange = { v -> update { it.copy(dryRun = v) } },
        )
        if (!s.dryRun) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.rules_dry_run_off_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageSettings(s: CompressionSettings, update: Update) {
    SettingsSection(stringResource(R.string.storage_title), stringResource(R.string.storage_subtitle)) {
        SegmentedSetting(
            label = stringResource(R.string.storage_subfolders),
            options = FolderLayout.entries,
            selected = s.folderLayout,
            optionLabel = ::layoutShort,
            onSelect = { v -> update { it.copy(folderLayout = v) } },
            hint = stringResource(R.string.storage_hint),
        )
        FolderPreview(s.folderLayout, s.separateFolders)
        Hint(
            stringResource(R.string.storage_hint_folders),
        )
    }
}

private fun layoutShort(layout: FolderLayout) = when (layout) {
    FolderLayout.FLAT -> L10n.str(R.string.layout_short_flat)
    FolderLayout.YEAR -> L10n.str(R.string.layout_short_year)
    FolderLayout.YEAR_MONTH -> L10n.str(R.string.layout_short_year_month)
}

@StringRes
fun layoutLabel(layout: FolderLayout) = when (layout) {
    FolderLayout.FLAT -> R.string.layout_flat
    FolderLayout.YEAR -> R.string.layout_year
    FolderLayout.YEAR_MONTH -> R.string.layout_year_month
}

/** Beispielhafte Ordnerstruktur auf dem Medium für die gewählte Ablage. */
@Composable
private fun FolderPreview(layout: FolderLayout, separateFolders: Boolean) {
    // (Einrückungstiefe, Name, ist Ordner)
    val lines = buildList {
        add(Triple(0, L10n.str(R.string.storage_preview_root), true))
        var depth = 1
        if (separateFolders) add(Triple(depth++, "DCIM/Camera", true))
        when (layout) {
            FolderLayout.FLAT -> {}
            FolderLayout.YEAR -> add(Triple(depth++, "2026", true))
            FolderLayout.YEAR_MONTH -> {
                add(Triple(depth++, "2026", true))
                add(Triple(depth++, "10", true))
            }
        }
        add(Triple(depth, "20261024_101500.jpg", false))
        add(Triple(depth, "20261024_183012.mp4", false))
        add(Triple(1, "Metadata", true))
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEach { (depth, name, isFolder) ->
                Row(Modifier.padding(start = (depth * 18).dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (isFolder) R.drawable.ic_folder else R.drawable.ic_image),
                        null,
                        tint = if (isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun minAgeShort(days: Int) = when (days) {
    0 -> L10n.str(R.string.age_short_now)
    365 -> L10n.str(R.string.age_year)
    else -> L10n.str(R.string.age_short_days, days)
}

private fun minAgeLabel(days: Int) = when (days) {
    365 -> L10n.str(R.string.age_year)
    else -> L10n.plural(R.plurals.age_days, days, days)
}
