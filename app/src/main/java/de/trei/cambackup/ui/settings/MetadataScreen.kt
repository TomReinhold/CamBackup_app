package de.trei.cambackup.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trei.cambackup.R
import de.trei.cambackup.index.BackupIndex
import de.trei.cambackup.index.BackupIndexFile
import de.trei.cambackup.index.EntryStatus
import de.trei.cambackup.storage.DeviceRecord
import de.trei.cambackup.storage.DeviceRegistry
import de.trei.cambackup.storage.DriveRepository
import de.trei.cambackup.storage.SafIo
import de.trei.cambackup.storage.metadataJson
import de.trei.cambackup.util.L10n
import de.trei.cambackup.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import de.trei.cambackup.util.toMsg

/** Inhalt des Ordners Metadata im Backup-Ordner, aufbereitet zur Anzeige. */
private data class MetadataInfo(
    val folder: String,
    val files: List<Pair<String, Long>>,
    val deviceJson: String?,
    val device: DeviceRecord?,
    val indexUpdatedAt: String?,
    val entries: Int,
    val verified: Int,
    val compressed: Int,
    val kept: Int,
    val originalBytes: Long,
    val journalLines: Int,
    val log: List<String>,
)

private sealed interface MetadataState {
    data object Loading : MetadataState
    data class Ready(val info: MetadataInfo) : MetadataState
    data class Unavailable(val message: String) : MetadataState
}

/** Technische Ansicht der Metadaten auf dem Speichermedium (nur lesend). */
@Composable
fun MetadataScreen() {
    val context = LocalContext.current
    val state by produceState<MetadataState>(MetadataState.Loading) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drives = DriveRepository(context)
                val dir = drives.openActive() ?: return@runCatching MetadataState.Unavailable(L10n.str(R.string.meta_not_connected))
                val metadata = dir.findFile(DeviceRegistry.METADATA_DIR)
                    ?: return@runCatching MetadataState.Unavailable(L10n.str(R.string.meta_no_folder))
                val io = SafIo(context.contentResolver)
                val state = BackupIndex.open(io, metadata).state
                val entries = state.entries
                val info = drives.info(dir.uri)
                val deviceJson = metadata.findFile(DeviceRegistry.DEVICE_FILE)?.let(io::readText)
                MetadataState.Ready(
                    MetadataInfo(
                        folder = info.volumeLabel + if (info.path.isNotEmpty()) " / ${info.path}" else "",
                        files = metadata.listFiles().map { (it.name ?: "?") to it.length() }.sortedBy { it.first },
                        deviceJson = deviceJson,
                        device = deviceJson?.let { runCatching { metadataJson.decodeFromString(DeviceRecord.serializer(), it) }.getOrNull() },
                        indexUpdatedAt = metadata.findFile(BackupIndex.INDEX_FILE)?.let { f ->
                            runCatching { metadataJson.decodeFromString(BackupIndexFile.serializer(), io.readText(f)).updatedAt }.getOrNull()
                        },
                        entries = entries.size,
                        verified = entries.count { it.status == EntryStatus.VERIFIED },
                        compressed = entries.count { it.status == EntryStatus.COMPRESSED },
                        kept = entries.count { it.keepOriginal },
                        originalBytes = entries.sumOf { it.originalSize },
                        journalLines = metadata.findFile(BackupIndex.JOURNAL_FILE)?.let { io.readText(it).lines().count(String::isNotBlank) } ?: 0,
                        log = metadata.findFile("backup_log.txt")?.let { io.readText(it).lines().filter(String::isNotBlank).takeLast(200).reversed() }.orEmpty(),
                    ),
                )
            }.getOrElse { MetadataState.Unavailable(it.toMsg().ui) }
        }
    }

    when (val s = state) {
        MetadataState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is MetadataState.Unavailable -> Hint(s.message)
        is MetadataState.Ready -> MetadataContent(s.info)
    }
}

@Composable
private fun MetadataContent(m: MetadataInfo) {
    SettingsSection(stringResource(R.string.meta_folder), m.folder) {
        m.files.forEach { (name, size) -> KeyValue(name, formatBytes(size)) }
    }

    SettingsSection(stringResource(R.string.meta_index), stringResource(R.string.meta_index_hint)) {
        KeyValue(stringResource(R.string.meta_entries), "${m.entries}")
        KeyValue(stringResource(R.string.meta_verified), "${m.verified}")
        KeyValue(stringResource(R.string.label_compressed), "${m.compressed}")
        KeyValue(stringResource(R.string.meta_kept), "${m.kept}")
        KeyValue(stringResource(R.string.meta_original_total), formatBytes(m.originalBytes))
        KeyValue(stringResource(R.string.meta_index_updated), m.indexUpdatedAt?.let(::formatTimestamp) ?: "–")
        KeyValue(
            stringResource(R.string.meta_journal),
            when (m.journalLines) {
                0 -> stringResource(R.string.meta_journal_none)
                1 -> stringResource(R.string.meta_journal_one)
                else -> stringResource(R.string.meta_journal_many, m.journalLines)
            },
        )
    }

    SettingsSection(stringResource(R.string.about_device), stringResource(R.string.meta_device_hint)) {
        val d = m.device
        if (d == null) {
            Hint(stringResource(if (m.deviceJson == null) R.string.meta_device_missing else R.string.meta_device_broken))
        } else {
            KeyValue(stringResource(R.string.drive_name_label), d.displayName)
            KeyValue(stringResource(R.string.about_device), "${d.manufacturer.replaceFirstChar { it.uppercase() }} ${d.model}")
            KeyValue(stringResource(R.string.meta_created), formatTimestamp(d.createdAt))
            KeyValue(stringResource(R.string.meta_last_seen), formatTimestamp(d.lastSeenAt))
            KeyValue(stringResource(R.string.meta_last_backup), d.lastBackupAt?.let(::formatTimestamp) ?: stringResource(R.string.drive_never))
            KeyValue(stringResource(R.string.meta_app_version), d.appVersion)
            KeyValue(stringResource(R.string.meta_device_id), d.deviceUuid, monospace = true)
            KeyValue("Android-ID", d.androidId ?: "–", monospace = true)
            if (d.previousUuids.isNotEmpty()) KeyValue(stringResource(R.string.meta_previous_ids), d.previousUuids.joinToString("\n"), monospace = true)
            KeyValue(stringResource(R.string.meta_format), "${d.formatVersion}")
        }
        if (m.deviceJson != null) {
            var raw by remember { mutableStateOf(false) }
            TextButton(onClick = { raw = !raw }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(if (raw) R.string.meta_raw_hide else R.string.meta_raw_show))
            }
            if (raw) Code(m.deviceJson)
        }
    }

    SettingsSection(stringResource(R.string.meta_log), stringResource(R.string.meta_log_hint)) {
        if (m.log.isEmpty()) Hint(stringResource(R.string.meta_log_empty)) else Code(m.log.joinToString("\n"))
    }
}

@Composable
private fun KeyValue(key: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.6f),
        )
    }
}

/** ISO-Zeitpunkt aus den Metadaten in Ortszeit, z. B. "24.10.2026, 18:13". */
private fun formatTimestamp(iso: String): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(Instant.parse(iso)))
}.getOrDefault(iso)

/** Text in Festbreitenschrift, waagerecht scrollbar (lange Zeilen bleiben lesbar). */
@Composable
private fun Code(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}
