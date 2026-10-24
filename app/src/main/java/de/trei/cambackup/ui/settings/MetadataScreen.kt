package de.trei.cambackup.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trei.cambackup.index.BackupIndex
import de.trei.cambackup.index.BackupIndexFile
import de.trei.cambackup.index.EntryStatus
import de.trei.cambackup.storage.DeviceRegistry
import de.trei.cambackup.storage.DriveRepository
import de.trei.cambackup.storage.SafIo
import de.trei.cambackup.storage.metadataJson
import de.trei.cambackup.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Inhalt des Ordners Metadata im Backup-Ordner, aufbereitet zur Anzeige. */
private data class MetadataInfo(
    val folder: String,
    val files: List<Pair<String, Long>>,
    val deviceJson: String?,
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
                val dir = drives.openActive() ?: return@runCatching MetadataState.Unavailable("Speichermedium nicht verbunden.")
                val metadata = dir.findFile(DeviceRegistry.METADATA_DIR)
                    ?: return@runCatching MetadataState.Unavailable("Kein Ordner Metadata im Backup-Ordner.")
                val io = SafIo(context.contentResolver)
                val state = BackupIndex.open(io, metadata).state
                val entries = state.entries
                val info = drives.info(dir.uri)
                MetadataState.Ready(
                    MetadataInfo(
                        folder = info.volumeLabel + if (info.path.isNotEmpty()) " / ${info.path}" else "",
                        files = metadata.listFiles().map { (it.name ?: "?") to it.length() }.sortedBy { it.first },
                        deviceJson = metadata.findFile(DeviceRegistry.DEVICE_FILE)?.let(io::readText),
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
            }.getOrElse { MetadataState.Unavailable(it.message ?: it.javaClass.simpleName) }
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
    SettingsSection("Ordner Metadata", m.folder) {
        m.files.forEach { (name, size) -> KeyValue(name, formatBytes(size)) }
    }

    SettingsSection("Index", "backup_index.json und Journal zusammen") {
        KeyValue("Einträge", "${m.entries}")
        KeyValue("Nur gesichert", "${m.verified}")
        KeyValue("Verkleinert", "${m.compressed}")
        KeyValue("Original behalten", "${m.kept}")
        KeyValue("Originale gesamt", formatBytes(m.originalBytes))
        KeyValue("Stand des Index", m.indexUpdatedAt ?: "–")
        KeyValue(
            "Offene Journal-Einträge",
            when (m.journalLines) {
                0 -> "keine"
                1 -> "1 Eintrag"
                else -> "${m.journalLines} Einträge"
            },
        )
    }

    SettingsSection("device.json") {
        Code(m.deviceJson ?: "fehlt")
    }

    SettingsSection("Protokoll", "backup_log.txt – neueste Einträge zuerst") {
        if (m.log.isEmpty()) Hint("Noch keine Einträge.") else Code(m.log.joinToString("\n"))
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Text in Festbreitenschrift, waagerecht scrollbar (lange Zeilen bleiben lesbar). */
@Composable
private fun Code(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}
