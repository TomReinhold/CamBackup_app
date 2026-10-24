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
import androidx.compose.ui.res.stringResource
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
            title = stringResource(R.string.drive_title),
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
                            Banner(stringResource(R.string.drive_new_volume, volume.label), BannerTone.INFO, title = stringResource(R.string.drive_new_volume_title))
                            Button(onClick = { pickTree.launch(volume.pickerStart) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.drive_pick_on, volume.label))
                            }
                        }
                        s.hadFolder -> Banner(
                            stringResource(R.string.drive_not_connected),
                            BannerTone.WARNING,
                        )
                        else -> Text(stringResource(R.string.drive_connect_hint))
                    }
                    Text(
                        stringResource(R.string.drive_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (volume == null) OutlinedButton(onClick = { pickTree.launch(null) }) { Text(stringResource(R.string.drive_pick)) }
                }

                is DriveState.Error -> {
                    Banner(stringResource(R.string.error_prefix, s.message), BannerTone.ERROR)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::refresh) { Text(stringResource(R.string.action_retry)) }
                        OutlinedButton(onClick = { pickTree.launch(null) }) { Text(stringResource(R.string.drive_pick_other)) }
                    }
                }

                is DriveState.Ready -> {
                    DriveDetails(s.info)
                    PhoneRow(s.record, onRename = vm::rename)
                    TextButton(onClick = { pickTree.launch(null) }) { Text(stringResource(R.string.drive_pick_other_backup)) }
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
        Text(stringResource(R.string.drive_free_unknown), style = MaterialTheme.typography.bodyMedium)
        return
    }
    StackedBar(listOf(BarSegment(space.totalBytes - space.freeBytes, MaterialTheme.colorScheme.primary)), height = 8, total = space.totalBytes)
    Row {
        Text(stringResource(R.string.drive_free, formatBytes(space.freeBytes)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.drive_of_total, formatBytes(space.totalBytes)) + (info.fsType?.let { " · ${fsLabel(it)}" } ?: ""),
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
                    stringResource(R.string.drive_last_backup, record.lastBackupAt?.let(::formatInstant) ?: stringResource(R.string.drive_never)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, stringResource(R.string.drive_rename)) }
        }
    }
    if (editing) {
        NameDialog(
            title = stringResource(R.string.drive_phone_name),
            text = stringResource(R.string.drive_phone_name_hint),
            initial = record.displayName,
            confirm = stringResource(R.string.action_save),
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
            title = { Text(stringResource(R.string.drive_checking)) },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            confirmButton = {},
        )

        is PickDialog.Rejected -> AlertDialog(
            onDismissRequest = vm::dismissPick,
            title = { Text(dialog.title) },
            text = { Text(dialog.message) },
            confirmButton = { TextButton(onClick = vm::dismissPick) { Text(stringResource(android.R.string.ok)) } },
        )

        is PickDialog.FoundInside -> AlertDialog(
            onDismissRequest = vm::dismissPick,
            title = { Text(stringResource(R.string.drive_found_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.drive_found_text,
                        dialog.parentName, dialog.folder.dir.name.orEmpty(), dialog.folder.record.displayName,
                        dialog.folder.record.lastBackupAt?.let(::formatInstant) ?: stringResource(R.string.drive_never),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirm(dialog.folder) }) { Text(stringResource(R.string.drive_use)) } },
            dismissButton = { TextButton(onClick = vm::dismissPick) { Text(stringResource(R.string.action_cancel)) } },
        )

        is PickDialog.ConfirmSameModel -> AlertDialog(
            onDismissRequest = vm::dismissPick,
            title = { Text(stringResource(R.string.drive_same_model_title)) },
            text = {
                val r = dialog.folder.record
                Text(
                    stringResource(
                        R.string.drive_same_model_text,
                        dialog.folder.dir.name.orEmpty(), r.displayName, "${r.manufacturer} ${r.model}", formatInstant(r.lastSeenAt),
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirm(dialog.folder) }) { Text(stringResource(R.string.drive_same_model_confirm)) } },
            dismissButton = { TextButton(onClick = vm::dismissPick) { Text(stringResource(R.string.action_cancel)) } },
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
        title = { Text(stringResource(R.string.drive_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.otherDevices.isNotEmpty()) {
                    Text(
                        stringResource(R.string.drive_setup_others, d.otherDevices.joinToString { it.record.displayName }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.drive_phone_name)) },
                    supportingText = { if (!here && folderCheck is FolderNameCheck.Invalid) (folderCheck as FolderNameCheck.Invalid).let { Text(stringResource(it.reason, *it.args.toTypedArray())) } },
                    isError = !here && folderCheck is FolderNameCheck.Invalid,
                )
                ChoiceRow(
                    selected = !here,
                    title = stringResource(R.string.drive_setup_new),
                    subtitle = stringResource(R.string.drive_setup_new_in, sanitizeFolderName(name), d.dir.name.orEmpty()),
                    onClick = { here = false },
                )
                ChoiceRow(
                    selected = here,
                    enabled = canUseHere,
                    title = stringResource(R.string.drive_setup_here),
                    subtitle = if (!canUseHere) stringResource(R.string.drive_setup_here_blocked)
                    else if (d.isEmpty) stringResource(R.string.drive_setup_here_empty, d.dir.name.orEmpty())
                    else stringResource(R.string.drive_setup_here_files, d.dir.name.orEmpty()),
                    onClick = { here = true },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { if (here) vm.setupHere(d.dir, name) else vm.setupSubfolder(d.dir, sanitizeFolderName(name), name) },
            ) { Text(stringResource(R.string.drive_setup_confirm)) }
        },
        dismissButton = { TextButton(onClick = vm::dismissPick) { Text(stringResource(R.string.action_cancel)) } },
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
                OutlinedTextField(value = field, onValueChange = { field = it }, singleLine = true, label = { Text(stringResource(R.string.drive_name_label)) })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotEmpty() && name.length <= MAX_FOLDER_NAME_LENGTH) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
