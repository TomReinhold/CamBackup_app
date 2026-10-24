package de.trei.cambackup.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.trei.cambackup.backup.MissingAction
import de.trei.cambackup.backup.MissingItem
import de.trei.cambackup.ui.components.Banner
import de.trei.cambackup.ui.components.BannerTone
import de.trei.cambackup.ui.gallery.MediaThumbnail
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes
import java.text.DateFormat
import java.util.Date

/** Hinweis in der Backup-Karte, wenn gesicherte Dateien auf dem Smartphone gelöscht wurden. */
@Composable
fun MissingBanner(missing: List<MissingItem>, onOpen: () -> Unit) {
    Banner(
        "${fileCount(missing.size)} (${formatBytes(missing.sumOf { it.bytes })}) " +
            (if (missing.size == 1) "wurde" else "wurden") + " auf dem Smartphone gelöscht, " +
            (if (missing.size == 1) "ist" else "sind") + " aber noch gesichert.",
        BannerTone.WARNING,
    )
    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Ansehen und entscheiden") }
}

/**
 * Auswahl, was mit gelöschten Dateien geschehen soll – einzeln oder für alle:
 * wiederherstellen (Original zurück aufs Smartphone) oder im Backup löschen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissingDialog(
    missing: List<MissingItem>,
    thumbnails: Map<String, Uri>,
    onDismiss: () -> Unit,
    onApply: (Map<String, MissingAction>) -> Unit,
) {
    val choices = remember { mutableStateMapOf<String, MissingAction>() }
    var confirmDelete by remember { mutableStateOf(false) }
    val restoreCount = choices.values.count { it == MissingAction.RESTORE }
    val deleteCount = choices.values.count { it == MissingAction.DELETE_BACKUP }
    val thumbPx = with(LocalDensity.current) { 64.dp.roundToPx() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Schließen") }
                    Text("Gelöschte Dateien (${missing.size})", style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Diese Fotos und Videos fehlen auf dem Smartphone, liegen aber auf dem Speichermedium. " +
                            "Wähle für jede Datei oder für alle:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { missing.forEach { choices[it.phonePath] = MissingAction.RESTORE } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Alle zurückholen", maxLines = 1) }
                        OutlinedButton(
                            onClick = { missing.forEach { choices[it.phonePath] = MissingAction.DELETE_BACKUP } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Alle löschen", maxLines = 1) }
                    }
                }
                Spacer(Modifier.size(8.dp))
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(missing, key = { it.phonePath }) { m ->
                        MissingRow(m, thumbnails[m.phonePath], thumbPx, choices[m.phonePath]) { action ->
                            if (action == null) choices.remove(m.phonePath) else choices[m.phonePath] = action
                        }
                        HorizontalDivider()
                    }
                }
                Surface(tonalElevation = 3.dp) {
                    // Zusätzlicher Abstand unten: im Vollbild-Dialog kommen die Systemleisten-Abstände nicht immer an
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (choices.isEmpty()) "Noch nichts ausgewählt"
                            else "$restoreCount zurückholen · $deleteCount löschen",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { if (deleteCount > 0) confirmDelete = true else onApply(choices.toMap()) },
                            enabled = choices.isNotEmpty(),
                        ) { Text("Ausführen") }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Endgültig löschen?") },
            text = {
                Text(
                    "${fileCount(deleteCount)} " + (if (deleteCount == 1) "wird" else "werden") +
                        " vom Speichermedium gelöscht. Da " + (if (deleteCount == 1) "sie auch auf dem Smartphone fehlt" else "sie auch auf dem Smartphone fehlen") +
                        ", gibt es danach keine Kopie mehr.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onApply(choices.toMap()) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissingRow(m: MissingItem, thumb: Uri?, thumbPx: Int, choice: MissingAction?, onChoice: (MissingAction?) -> Unit) {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.latest.dateTakenMillis))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))) {
                if (thumb != null) MediaThumbnail(thumb, thumbPx, Modifier.fillMaxSize())
                else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {}
                if (m.isVideo) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$date · ${formatBytes(m.latest.originalSize)}" + if (m.versions.size > 1) " · ${m.versions.size} Versionen" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(m.phonePath.substringBeforeLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // „Zurückholen“ statt „Wiederherstellen“: passt auch auf schmalen Geräten in die Leiste
        val options = listOf<Pair<MissingAction?, String>>(null to "Offen", MissingAction.RESTORE to "Zurückholen", MissingAction.DELETE_BACKUP to "Löschen")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (action, label) ->
                SegmentedButton(
                    selected = choice == action,
                    onClick = { onChoice(action) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    // Ohne Häkchen – sonst wird „Wiederherstellen“ auf schmalen Geräten abgeschnitten
                    icon = {},
                    colors = if (action == MissingAction.DELETE_BACKUP) {
                        SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.errorContainer)
                    } else {
                        SegmentedButtonDefaults.colors()
                    },
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}
