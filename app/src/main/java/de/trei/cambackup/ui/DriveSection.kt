package de.trei.cambackup.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trei.cambackup.R
import de.trei.cambackup.storage.DeviceRecord
import de.trei.cambackup.storage.DriveInfo
import de.trei.cambackup.storage.FolderNameCheck
import de.trei.cambackup.storage.MAX_FOLDER_NAME_LENGTH
import de.trei.cambackup.storage.sanitizeFolderName
import de.trei.cambackup.storage.validateFolderName
import de.trei.cambackup.ui.components.Banner
import de.trei.cambackup.ui.components.BannerTone
import de.trei.cambackup.ui.components.BarSegment
import de.trei.cambackup.ui.components.FeatureCard
import de.trei.cambackup.ui.components.StackedBar
import de.trei.cambackup.util.formatBytes
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/**
 * Karten für Speichermedium (Backup-Ordner, freier Platz), Backup und Speicher freigeben.
 * [onLibraryChanged]: nach Backup/Ersetzen, damit Übersicht und Fotos neu einlesen.
 */
@Composable
fun DriveSection(onLibraryChanged: () -> Unit, modifier: Modifier = Modifier, vm: DriveViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pick by vm.pick.collectAsStateWithLifecycle()
    val autoFound by vm.autoFound.collectAsStateWithLifecycle()
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), vm::onTreePicked)

    val ready = state as? DriveState.Ready
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FeatureCard(
            icon = R.drawable.ic_usb,
            title = "Speichermedium",
            subtitle = ready?.info?.let { it.volumeLabel + if (it.path.isNotEmpty()) " / ${it.path}" else "" },
        ) {
            autoFound?.let {
                Banner(it, BannerTone.SUCCESS)
            }
            when (val s = state) {
                DriveState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())

                is DriveState.NoFolder -> {
                    val volume = s.newVolume
                    when {
                        volume != null -> {
                            Banner("Neues Speichermedium „${volume.label}“ erkannt. Wähle dort den Backup-Ordner für dieses Smartphone.", BannerTone.INFO)
                            Button(onClick = { pickTree.launch(volume.pickerStart) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Backup-Ordner auf ${volume.label} wählen")
                            }
                        }
                        s.hadFolder -> Banner(
                            "Das Speichermedium mit dem Backup-Ordner ist nicht verbunden. Bitte einstecken – die App findet ihn automatisch.",
                            BannerTone.WARNING,
                        )
                        else -> Text("Schließe einen USB-Stick oder eine SD-Karte an und wähle einen Ordner für die Sicherung dieses Smartphones.")
                    }
                    Text(
                        "Tipp: Lege auf dem Medium einen Ordner an (z. B. „Backup Handy“) und wähle ihn. Wählst du einen Ordner, " +
                            "der Backup-Ordner mehrerer Smartphones enthält, findet die App den passenden selbst.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (volume == null) OutlinedButton(onClick = { pickTree.launch(null) }) { Text("Ordner wählen") }
                }

                is DriveState.Error -> {
                    Banner("Fehler: ${s.message}", BannerTone.ERROR)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::refresh) { Text("Erneut versuchen") }
                        OutlinedButton(onClick = { pickTree.launch(null) }) { Text("Anderen Ordner wählen") }
                    }
                }

                is DriveState.Ready -> {
                    DriveDetails(s.info)
                    PhoneRow(s.record, onRename = vm::rename)
                    TextButton(onClick = { pickTree.launch(null) }) { Text("Anderen Backup-Ordner wählen") }
                }
            }
        }

        if (ready != null) {
            val folder = ready.info.folderUri.toString()
            BackupPanel(
                folderUri = folder,
                onBackupFinished = {
                    vm.refresh()
                    onLibraryChanged()
                },
            )
            FreeUpPanel(folderUri = folder, onFinished = onLibraryChanged)
            RestorePanel(folderUri = folder, onFinished = onLibraryChanged)
        }
    }

    pick?.let { PickDialogs(it, vm) }
}

@Composable
private fun DriveDetails(info: DriveInfo) {
    val space = info.space ?: run {
        Text("Freier Speicher: unbekannt", style = MaterialTheme.typography.bodyMedium)
        return
    }
    StackedBar(listOf(BarSegment(space.totalBytes - space.freeBytes, MaterialTheme.colorScheme.primary)), height = 8, total = space.totalBytes)
    Row {
        Text("${formatBytes(space.freeBytes)} frei", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            "von ${formatBytes(space.totalBytes)}" + (info.fsType?.let { " · ${fsLabel(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fsLabel(fs: String) = when (fs.lowercase()) {
    "vfat", "msdos", "fat", "fat32" -> "FAT32"
    "exfat", "sdfat" -> "exFAT"
    else -> fs
}

/** Name des Smartphones (aus den Metadaten, änderbar) und letzte Sicherung. */
@Composable
private fun PhoneRow(record: DeviceRecord, onRename: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_smartphone), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(record.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Letzte Sicherung: " + (record.lastBackupAt?.let(::formatInstant) ?: "noch keine"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Namen ändern") }
        }
    }
    if (editing) {
        NameDialog(
            title = "Name des Smartphones",
            text = "Unter diesem Namen erscheint das Smartphone in den Metadaten des Backup-Ordners.",
            initial = record.displayName,
            confirm = "Speichern",
            onDismiss = { editing = false },
            onConfirm = { editing = false; onRename(it) },
        )
    }
}

@Composable
private fun PickDialogs(dialog: PickDialog, vm: DriveViewModel) {
    when (dialog) {
        PickDialog.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Ordner wird geprüft …") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            confirmButton = {},
        )

        is PickDialog.Rejected -> AlertDialog(
            onDismissRequest = vm::dismissPick,
            title = { Text(dialog.title) },
            text = { Text(dialog.message) },
            confirmButton = { TextButton(onClick = vm::dismissPick) { Text("OK") } },
        )

        is PickDialog.FoundInside -> AlertDialog(
            onDismissRequest = vm::dismissPick,
            title = { Text("Backup-Ordner gefunden") },
            text = {
                Text(
                    "In „${dialog.parentName}“ liegt der Backup-Ordner „${dialog.folder.dir.name}“ dieses Smartphones " +
                        "(„${dialog.folder.record.displayName}“, letzte Sicherung ${dialog.folder.record.lastBackupAt?.let(::formatInstant) ?: "noch keine"}). Verwenden?",
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirm(dialog.folder) }) { Text("Verwenden") } },
            dismissButton = { TextButton(onClick = vm::dismissPick) { Text("Abbrechen") } },
        )

        is PickDialog.ConfirmSameModel -> AlertDialog(
            onDismissRequest = vm::dismissPick,
            title = { Text("Ist das dieses Smartphone?") },
            text = {
                val r = dialog.folder.record
                Text(
                    "Der Ordner „${dialog.folder.dir.name}“ gehört zu „${r.displayName}“ – einem Smartphone desselben Modells " +
                        "(${r.manufacturer} ${r.model}), zuletzt gesehen ${formatInstant(r.lastSeenAt)}. Die Kennung passt nicht " +
                        "(z. B. nach Neuinstallation der App). Nur übernehmen, wenn es wirklich dieses Smartphone ist.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirm(dialog.folder) }) { Text("Ja, übernehmen") } },
            dismissButton = { TextButton(onClick = vm::dismissPick) { Text("Abbrechen") } },
        )

        is PickDialog.Setup -> SetupDialog(dialog, vm)
    }
}

/** Einrichten eines neuen Backup-Ordners: direkt im gewählten Ordner oder als Unterordner. */
@Composable
private fun SetupDialog(d: PickDialog.Setup, vm: DriveViewModel) {
    // Direkt einrichten nur, wenn der Ordner keine Backup-Ordner anderer Smartphones enthält
    val canUseHere = d.otherDevices.isEmpty()
    var here by rememberSaveable { mutableStateOf(canUseHere && d.isEmpty) }
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(d.suggestion, TextRange(d.suggestion.length))) }
    val name = field.text.trim()
    val folderCheck = remember(name, d.existingNames) { validateFolderName(sanitizeFolderName(name), d.existingNames) }
    val valid = name.isNotEmpty() && name.length <= MAX_FOLDER_NAME_LENGTH && (here || folderCheck is FolderNameCheck.Ok)

    AlertDialog(
        onDismissRequest = vm::dismissPick,
        title = { Text("Backup-Ordner einrichten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.otherDevices.isNotEmpty()) {
                    Text(
                        "Dieser Ordner enthält Backup-Ordner anderer Smartphones: " + d.otherDevices.joinToString { "„${it.record.displayName}“" } + ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    label = { Text("Name des Smartphones") },
                    supportingText = { if (!here && folderCheck is FolderNameCheck.Invalid) Text((folderCheck as FolderNameCheck.Invalid).reason) },
                    isError = !here && folderCheck is FolderNameCheck.Invalid,
                )
                ChoiceRow(
                    selected = !here,
                    title = "Neuen Unterordner anlegen",
                    subtitle = "„${sanitizeFolderName(name)}“ in „${d.dir.name}“",
                    onClick = { here = false },
                )
                ChoiceRow(
                    selected = here,
                    enabled = canUseHere,
                    title = "Diesen Ordner verwenden",
                    subtitle = if (!canUseHere) "nicht möglich – enthält andere Smartphones"
                    else if (d.isEmpty) "„${d.dir.name}“ ist leer" else "„${d.dir.name}“ enthält bereits Dateien",
                    onClick = { here = true },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { if (here) vm.setupHere(d.dir, name) else vm.setupSubfolder(d.dir, sanitizeFolderName(name), name) },
            ) { Text("Einrichten") }
        },
        dismissButton = { TextButton(onClick = vm::dismissPick) { Text("Abbrechen") } },
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NameDialog(title: String, text: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val name = field.text.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = field, onValueChange = { field = it }, singleLine = true, label = { Text("Name") })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotEmpty() && name.length <= MAX_FOLDER_NAME_LENGTH) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * Ruft [onChange] auf, solange die Oberfläche besteht, wenn ein Datenträger eingehängt oder entfernt
 * wird. Die MEDIA_*-Broadcasts kommen nur für für Apps sichtbare Datenträger; Samsung hängt USB-
 * Datenträger unsichtbar ein – deren Zustand meldet nur der StorageVolumeCallback.
 */
@Composable
fun MediaChangeListener(onChange: () -> Unit) {
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

        val storage = context.getSystemService(StorageManager::class.java)
        val volumeCallback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                if (volume.isRemovable) currentOnChange()
            }
        }
        storage.registerStorageVolumeCallback(ContextCompat.getMainExecutor(context), volumeCallback)

        onDispose {
            context.unregisterReceiver(receiver)
            storage.unregisterStorageVolumeCallback(volumeCallback)
        }
    }
}

private fun formatInstant(iso: String): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(Instant.parse(iso)))
}.getOrDefault(iso)
