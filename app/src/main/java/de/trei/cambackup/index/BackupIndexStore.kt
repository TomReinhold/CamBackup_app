package de.trei.cambackup.index

import androidx.documentfile.provider.DocumentFile
import de.trei.cambackup.storage.SafIo
import de.trei.cambackup.storage.metadataJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Index der gesicherten Dateien im Ordner `Metadata` des Geräteordners:
 * - `backup_index.json`: vollständiger Stand (wird am Ende eines Laufs neu geschrieben)
 * - `backup_journal.jsonl`: jede verifizierte Datei wird sofort als Zeile angehängt
 *
 * So ist der Fortschritt auch bei einem Abbruch (Stick abgezogen, App beendet) gesichert,
 * ohne nach jeder Datei den ganzen Index neu zu schreiben.
 */
class BackupIndex private constructor(
    private val io: SafIo,
    private val metadataDir: DocumentFile,
    val state: IndexState,
) {
    /** Speichert einen neuen bzw. geänderten Eintrag dauerhaft im Journal. */
    suspend fun record(entry: IndexEntry) = withContext(Dispatchers.IO) {
        io.appendText(metadataDir, JOURNAL_FILE, encodeJournalLine(entry))
        state.put(entry)
    }

    /** Entfernt einen Eintrag dauerhaft (Vermerk im Journal). */
    suspend fun remove(entry: IndexEntry) = record(entry.copy(deleted = true))

    /** Schreibt den vollständigen Index und leert das Journal. */
    suspend fun compact() = withContext(Dispatchers.IO) {
        val file = BackupIndexFile(updatedAt = Instant.now().toString(), entries = state.entries.toList())
        io.replaceAtomically(metadataDir, INDEX_FILE, metadataJson.encodeToString(BackupIndexFile.serializer(), file))
        // Erst nach erfolgreichem Schreiben des Index löschen. Ein Abbruch genau dazwischen ist
        // harmlos: das Journal wird beim nächsten Laden erneut eingespielt (gleiche backupPaths).
        metadataDir.findFile(JOURNAL_FILE)?.delete()
        // Nur zum Nachschlagen – ein Fehler hier darf den Index nicht gefährden
        runCatching { io.replaceAtomically(metadataDir, FILE_LIST, fileListCsv(state.entries)) }
    }

    companion object {
        const val INDEX_FILE = "backup_index.json"
        const val JOURNAL_FILE = "backup_journal.jsonl"

        suspend fun open(io: SafIo, metadataDir: DocumentFile): BackupIndex = withContext(Dispatchers.IO) {
            val snapshot = io.readWithTmpFallback(metadataDir, INDEX_FILE)
                ?.let { metadataJson.decodeFromString(BackupIndexFile.serializer(), it).entries }
                .orEmpty()
            val journal = metadataDir.findFile(JOURNAL_FILE)?.let { decodeJournal(io.readText(it)) }.orEmpty()
            BackupIndex(io, metadataDir, IndexState(snapshot + journal))
        }
    }
}
