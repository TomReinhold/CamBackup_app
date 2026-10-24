package de.trei.cambackup.freeup

import de.trei.cambackup.backup.phonePath
import de.trei.cambackup.index.EntryStatus
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.media.MediaItem
import kotlinx.serialization.Serializable

/** Eine vorbereitete Ersetzung: verkleinerte Datei im Cache, Original geprüft gesichert. */
@Serializable
data class PendingReplacement(
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val phonePath: String,
    val isVideo: Boolean,
    val backupPath: String,
    val originalSize: Long,
    val originalSha256: String,
    val newFile: String,
    val newSize: Long,
    val newSha256: String,
    val params: String,
)

@Serializable
data class PendingBatch(
    val deviceFolder: String,
    val createdAt: String,
    val items: List<PendingReplacement>,
) {
    val bytesSaved: Long get() = items.sumOf { it.originalSize - it.newSize }
}

/** Ergebnis von Vorbereitung bzw. Ersetzen. */
@Serializable
data class FreeUpSummary(
    val finishedAt: String,
    val replacing: Boolean,
    /** Vorbereitet (beim Vorbereiten) bzw. ersetzt (beim Ersetzen). */
    val done: Int,
    val bytesSaved: Long,
    /** Anzahl übersprungener Dateien je Grund (Klartext). */
    val skipped: Map<String, Int>,
    val failures: List<Failure>,
    val abortReason: String?,
) {
    @Serializable
    data class Failure(val name: String, val reason: String)
}

/** Gesichertes Original, dessen Datei auf dem Smartphone noch unverändert ist. */
data class Candidate(val item: MediaItem, val entry: IndexEntry)

data class FreeUpOverview(
    /** Gesichert und auf dem Smartphone noch im Original. */
    val candidates: List<Candidate>,
    /** Bereits durch eine verkleinerte Version ersetzt. */
    val alreadyCompressed: Int,
    val alreadyCompressedSaved: Long,
    /** Auf dem Smartphone, aber noch nicht gesichert. */
    val notBackedUp: Int,
)

/**
 * Teilt die Kamera-Dateien anhand des Index ein. Nur unveränderte, verifiziert gesicherte Originale
 * kommen als Kandidaten in Frage (Größe muss der gesicherten entsprechen; der Hash wird vor dem
 * Verkleinern zusätzlich geprüft).
 */
fun overviewFor(items: List<MediaItem>, index: IndexState): FreeUpOverview {
    val candidates = mutableListOf<Candidate>()
    var compressed = 0
    var compressedSaved = 0L
    var notBackedUp = 0
    for (item in items) {
        val entries = index.forPhonePath(item.phonePath)
        val asCompressed = entries.lastOrNull { it.status == EntryStatus.COMPRESSED && it.compressedSize == item.sizeBytes }
        val asOriginal = entries.lastOrNull { it.status == EntryStatus.VERIFIED && it.originalSize == item.sizeBytes }
        when {
            asCompressed != null -> {
                compressed++
                compressedSaved += asCompressed.originalSize - item.sizeBytes
            }
            asOriginal != null -> candidates += Candidate(item, asOriginal)
            else -> notBackedUp++
        }
    }
    return FreeUpOverview(candidates, compressed, compressedSaved, notBackedUp)
}

/** Index-Eintrag nach erfolgreichem Ersetzen. */
fun IndexEntry.asCompressed(p: PendingReplacement, at: String) = copy(
    status = EntryStatus.COMPRESSED,
    compressedSize = p.newSize,
    compressedSha256 = p.newSha256,
    compressedAt = at,
    compressionParams = p.params,
)

/** Index-Eintrag zurück auf „nur gesichert“ (Ersetzen fehlgeschlagen, Original wiederhergestellt). */
fun IndexEntry.asVerifiedOnly() = copy(
    status = EntryStatus.VERIFIED,
    compressedSize = null,
    compressedSha256 = null,
    compressedAt = null,
    compressionParams = null,
)
