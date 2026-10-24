package de.trei.cambackup.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class EntryStatus {
    /** Original liegt bitgenau (per SHA-256 geprüft) auf dem Speichermedium. */
    VERIFIED,
    /** Zusätzlich wurde die Datei auf dem Smartphone durch eine kleinere Version ersetzt. */
    COMPRESSED,
}

/** Ein gesichertes Original. Einträge entstehen erst nach erfolgreicher Prüfung. */
@Serializable
data class IndexEntry(
    /** Pfad auf dem Smartphone relativ zum Speicher, z. B. "DCIM/Camera/IMG_1.jpg". */
    val phonePath: String,
    val mimeType: String,
    val dateTakenMillis: Long,
    val originalSize: Long,
    val originalSha256: String,
    /** Pfad der Sicherung relativ zum Geräteordner, z. B. "2026/01/IMG_1.jpg". Eindeutig. */
    val backupPath: String,
    val backedUpAt: String,
    val status: EntryStatus = EntryStatus.VERIFIED,
    val compressedSize: Long? = null,
    val compressedSha256: String? = null,
    val compressedAt: String? = null,
    val compressionParams: String? = null,
)

/** Inhalt von `Metadata/backup_index.json`. */
@Serializable
data class BackupIndexFile(
    val formatVersion: Int = 1,
    val updatedAt: String,
    val entries: List<IndexEntry>,
)

/** Index im Speicher; Schlüssel ist [IndexEntry.backupPath]. */
class IndexState(entries: Collection<IndexEntry> = emptyList()) {
    private val byBackupPath = LinkedHashMap<String, IndexEntry>()
    private val byPhonePath = HashMap<String, MutableList<IndexEntry>>()

    init {
        entries.forEach(::put)
    }

    val entries: Collection<IndexEntry> get() = byBackupPath.values
    val size: Int get() = byBackupPath.size

    /** Fügt hinzu bzw. ersetzt den Eintrag mit gleichem backupPath. */
    fun put(entry: IndexEntry) {
        byBackupPath.put(entry.backupPath, entry)?.let { old -> byPhonePath[old.phonePath]?.remove(old) }
        byPhonePath.getOrPut(entry.phonePath) { mutableListOf() }.add(entry)
    }

    fun forPhonePath(phonePath: String): List<IndexEntry> = byPhonePath[phonePath].orEmpty()

    fun containsBackupPath(path: String): Boolean = path in byBackupPath
}

private val compactJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun encodeJournalLine(entry: IndexEntry): String = compactJson.encodeToString(IndexEntry.serializer(), entry) + "\n"

/**
 * Liest das Journal. Unlesbare Zeilen (z. B. eine halb geschriebene letzte Zeile nach einem Absturz)
 * werden übersprungen – der zugehörige Eintrag fehlt dann, und die Datei wird beim nächsten Lauf
 * anhand der Prüfsumme wiedererkannt.
 */
fun decodeJournal(text: String): List<IndexEntry> = text.lineSequence()
    .filter { it.isNotBlank() }
    .mapNotNull { runCatching { compactJson.decodeFromString(IndexEntry.serializer(), it) }.getOrNull() }
    .toList()
