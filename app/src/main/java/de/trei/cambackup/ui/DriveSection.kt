package de.trei.cambackup.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.R
import de.trei.cambackup.ui.components.Banner
import de.trei.cambackup.ui.components.BannerTone
import de.trei.cambackup.ui.components.BarSegment
import de.trei.cambackup.ui.components.FeatureCard
import de.trei.cambackup.ui.components.StackedBar
import de.trei.cambackup.storage.DeviceFolder
import de.trei.cambackup.storage.DeviceMatch
import de.trei.cambackup.storage.DriveInfo
import de.trei.cambackup.storage.FolderNameCheck
import de.trei.cambackup.storage.validateFolderName
import de.trei.cambackup.util.formatBytes
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/**
 * Karten für Speichermedium (freier Platz, Geräteordner), Backup und Speicher freigeben.
 * [onLibraryChanged]: nach Backup/Ersetzen, damit Übersicht und Fotos neu einlesen.
 */
@Composable
fun DriveSection(onLibraryChanged: () -> Unit, modifier: Modifier = Modifier, vm: DriveViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), vm::onTreePicked)
    val pick = { pickTree.launch(null) }

    // Erneut prüfen, wenn die App wieder sichtbar wird oder ein Datenträger ein-/ausgesteckt wird
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    MediaChangeListener(onChange = vm::refresh)

    val ready = state as? DriveState.Ready
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FeatureCard(
            icon = R.drawable.ic_usb,
            title = "Speichermedium",
            subtitle = ready?.info?.let { it.volumeLabel + if (it.path.isNotEmpty()) " / ${it.path}" else "" },
        ) {
            when (val s = state) {
                DriveState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())

                DriveState.NotSelected -> {
                    Text("Schließe einen USB-Stick oder eine SD-Karte an und wähle den Zielordner für die Sicherung.")
                    Banner(
                        "Android erlaubt nicht die oberste Ebene des Mediums. Lege dort einen Ordner an " +
                            "(z. B. „CamBackup“) und wähle diesen.",
                    )
                    Button(onClick = pick) { Text("Speichermedium wählen") }
                }

                DriveState.Unavailable -> {
                    Banner("Das Speichermedium ist nicht verbunden. Bitte einstecken – die App erkennt es automatisch.", BannerTone.WARNING)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::refresh) { Text("Erneut prüfen") }
                        OutlinedButton(onClick = pick) { Text("Anderes wählen") }
                    }
                }

                is DriveState.InternalStorage -> {
                    DriveDetails(s.info)
                    Banner(
                        "Dieser Ordner liegt im internen Speicher des Smartphones und ist als Backup-Ziel " +
                            "ungeeignet. Bitte einen Ordner auf dem USB-Stick oder der SD-Karte wählen.",
                        BannerTone.ERROR,
                    )
                    Button(onClick = pick) { Text("Anderes wählen") }
                }

                is DriveState.Error -> {
                    Banner("Fehler: ${s.message}", BannerTone.ERROR)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::refresh) { Text("Erneut versuchen") }
                        OutlinedButton(onClick = pick) { Text("Anderes wählen") }
                    }
                }

                is DriveState.Ready -> {
                    DriveDetails(s.info)
                    DeviceStatus(s, onCreate = vm::createDevice, onAssign = vm::assignExisting)
                    TextButton(onClick = pick, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
                        Text("Anderes Speichermedium wählen")
                    }
                }
            }
        }

        val recognized = ready?.match as? DeviceMatch.Recognized
        if (recognized != null) {
            BackupPanel(
                deviceFolderName = recognized.folder.folderName,
                onBackupFinished = {
                    vm.refresh()
                    onLibraryChanged()
                },
            )
            FreeUpPanel(deviceFolderName = recognized.folder.folderName, onFinished = onLibraryChanged)
        }
    }
}

@Composable
private fun DriveDetails(info: DriveInfo) {
    val space = info.space
    if (space == null) {
        Text("Freier Speicher: unbekannt", style = MaterialTheme.typography.bodyMedium)
    } else {
        StackedBar(
            listOf(BarSegment(space.totalBytes - space.freeBytes, MaterialTheme.colorScheme.primary)),
            height = 8,
            total = space.totalBytes,
        )
        Row {
            Text(
                "${formatBytes(space.freeBytes)} frei",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "von ${formatBytes(space.totalBytes)}" + (info.fsType?.let { " · ${fsLabel(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun fsLabel(fs: String) = when (fs.lowercase()) {
    "vfat", "msdos", "fat", "fat32" -> "FAT32"
    "exfat", "sdfat" -> "exFAT"
    else -> fs
}

@Composable
private fun DeviceStatus(
    s: DriveState.Ready,
    onCreate: (String) -> Unit,
    onAssign: (DeviceFolder) -> Unit,
) {
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showAssign by rememberSaveable { mutableStateOf(false) }

    when (val m = s.match) {
        is DeviceMatch.Recognized -> {
            val r = m.folder.record
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_smartphone), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(m.folder.folderName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Letzte Sicherung: " + (r.lastBackupAt?.let(::formatInstant) ?: "noch keine"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        is DeviceMatch.Unknown -> {
            Text("Dieses Smartphone ist auf dem Speichermedium noch nicht bekannt.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreate = true }) { Text("Geräteordner anlegen") }
                if (m.candidates.isNotEmpty()) {
                    OutlinedButton(onClick = { showAssign = true }) { Text("Zuordnen…") }
                }
            }
            if (showCreate) {
                CreateDeviceDialog(
                    suggestion = s.identity.suggestedFolderName,
                    existingNames = s.existingNames,
                    onDismiss = { showCreate = false },
                    onConfirm = { showCreate = false; onCreate(it) },
                )
            }
            if (showAssign) {
                AssignDeviceDialog(
                    candidates = m.candidates,
                    onDismiss = { showAssign = false },
                    onSelect = { showAssign = false; onAssign(it) },
                )
            }
        }
    }
}

@Composable
private fun CreateDeviceDialog(
    suggestion: String,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Cursor ans Ende des Vorschlags setzen
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(suggestion, TextRange(suggestion.length)))
    }
    val check = remember(field.text, existingNames) { validateFolderName(field.text, existingNames) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neuer Geräteordner") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Unter diesem Namen werden die Originale dieses Smartphones gespeichert.")
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    label = { Text("Ordnername") },
                    isError = check is FolderNameCheck.Invalid,
                    supportingText = { (check as? FolderNameCheck.Invalid)?.let { Text(it.reason) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { (check as? FolderNameCheck.Ok)?.let { onConfirm(it.name) } },
                enabled = check is FolderNameCheck.Ok,
            ) { Text("Anlegen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun AssignDeviceDialog(candidates: List<DeviceFolder>, onDismiss: () -> Unit, onSelect: (DeviceFolder) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vorhandenen Ordner zuordnen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Nur verwenden, wenn dies dasselbe Smartphone ist (z. B. nach Neuinstallation der App). " +
                        "Ein anderes Gerät bitte als neuen Ordner anlegen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(candidates, key = { it.folderName }) { f ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(f) }.padding(vertical = 8.dp)) {
                            Text(f.folderName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${f.record.manufacturer} ${f.record.model} · zuletzt ${formatInstant(f.record.lastSeenAt)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Ruft [onChange] auf, solange sichtbar, wenn ein Datenträger eingehängt oder entfernt wird. */
@Composable
private fun MediaChangeListener(onChange: () -> Unit) {
    val context = LocalContext.current
    val currentOnChange by rememberUpdatedState(onChange)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = currentOnChange()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
}

private fun formatInstant(iso: String): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(Instant.parse(iso)))
}.getOrDefault(iso)
