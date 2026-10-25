package de.trei.cambackup.freeup

import de.trei.cambackup.settings.CompressionSettings
import de.trei.cambackup.compress.shrinkSettingsKey
import de.trei.cambackup.compress.oldEnough
import de.trei.cambackup.compress.compressibleFormat
import de.trei.cambackup.backup.phonePath
import de.trei.cambackup.index.EntryStatus
import de.trei.cambackup.index.IndexEntry
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.media.MediaItem
import kotlinx.serialization.Serializable

/** Eine anstehende Ersetzung: verkleinerte Datei im Cache, Original geprüft gesichert. */
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

/** Ergebnis eines Laufs (Probelauf oder Verkleinern). */
@Serializable
data class FreeUpSummary(
    val finishedAt: String,
    val replacing: Boolean,
    /** Verkleinerbar (Probelauf) bzw. verkleinert und ersetzt. */
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
    /** Original bewusst zurückgeholt – wird nicht erneut verkleinert. */
    val keptOriginal: Int = 0,
    /** Lohnt sich mit den aktuellen Einstellungen nicht (zu wenig Ersparnis) – bleibt im Original. */
    val notWorth: Int = 0,
)

/** Eine verkleinerte Datei, deren Original vom Medium zurückgeholt werden kann. */
data class RestoreCandidate(val item: MediaItem, val entry: IndexEntry) {
    /** Zusätzlicher Platz, den das Original auf dem Smartphone braucht. */
    val extraBytes: Long get() = entry.originalSize - item.sizeBytes
}

/** Verkleinerte Dateien, deren Datei auf dem Smartphone noch genau die verkleinerte Version ist. */
fun restoreCandidates(items: List<MediaItem>, index: IndexState): List<RestoreCandidate> = items.mapNotNull { item ->
    index.forPhonePath(item.phonePath)
        .lastOrNull { it.status == EntryStatus.COMPRESSED && it.compressedSize == item.sizeBytes }
        ?.let { RestoreCandidate(item, it) }
}

/**
 * Kandidaten, die verkleinert werden: alt genug und in einem Format, das verkleinert wird – nur für diese
 * wird die Zustimmung eingeholt und nur sie zählt die Übersicht als „wird verkleinert“.
 */
fun compressTargets(candidates: List<Candidate>, minAgeDays: Int, nowMillis: Long): List<Candidate> {
    return candidates.filter { compressibleFormat(it.item) && oldEnough(it.item.dateTakenMillis, minAgeDays, nowMillis) }
}

/** Platzbedarf beim Zurückholen inkl. Reserve, damit das Smartphone nicht vollläuft. */
fun restoreSpaceNeeded(candidates: List<RestoreCandidate>): Long {
    val extra = candidates.sumOf { it.extraBytes }
    return if (extra <= 0) 0 else extra + RESTORE_RESERVE
}

/** Freier Platz, der auf dem Smartphone nach dem Zurückholen mindestens übrig bleiben soll. */
const val RESTORE_RESERVE = 500L * 1024 * 1024

/** Freier Platz, der beim Verkleinern (verkleinerte Kopie im Cache) mindestens übrig bleiben soll. */
const val PREPARE_RESERVE = 3L * 1024 * 1024 * 1024

/** Passt die verkleinerte Kopie noch in den Cache? Sie ist höchstens so groß wie das Original. */
fun prepareSpaceOk(freeBytes: Long, originalBytes: Long): Boolean = freeBytes >= originalBytes + PREPARE_RESERVE

/**
 * Teilt die Kamera-Dateien anhand des Index ein. Nur unveränderte, verifiziert gesicherte Originale
 * kommen als Kandidaten in Frage (Größe muss der gesicherten entsprechen; der Hash wird vor dem
 * Verkleinern zusätzlich geprüft).
 */
fun overviewFor(items: List<MediaItem>, index: IndexState, settings: CompressionSettings? = null): FreeUpOverview {
    val candidates = mutableListOf<Candidate>()
    var compressed = 0
    var compressedSaved = 0L
    var notBackedUp = 0
    var kept = 0
    var notWorth = 0
    for (item in items) {
        val entries = index.forPhonePath(item.phonePath)
        val asCompressed = entries.lastOrNull { it.status == EntryStatus.COMPRESSED && it.compressedSize == item.sizeBytes }
        val asOriginal = entries.lastOrNull { it.status == EntryStatus.VERIFIED && it.originalSize == item.sizeBytes }
        when {
            asCompressed != null -> {
                compressed++
                compressedSaved += asCompressed.originalSize - item.sizeBytes
            }
            asOriginal != null && asOriginal.keepOriginal -> kept++
            asOriginal != null && settings != null &&
                asOriginal.notWorthFor == shrinkSettingsKey(settings, item.isVideo) -> notWorth++
            asOriginal != null -> candidates += Candidate(item, asOriginal)
            else -> notBackedUp++
        }
    }
    return FreeUpOverview(candidates, compressed, compressedSaved, notBackedUp, kept, notWorth)
}

/**
 * Einträge, deren Markierung „nicht verkleinern“ sich ändert. Nur gesicherte Originale (noch nicht
 * verkleinert) lassen sich ausschließen; bereits gesetzte Markierungen bleiben unverändert.
 */
fun keepOriginalChanges(index: IndexState, backupPaths: Set<String>, keep: Boolean): List<IndexEntry> =
    index.entries
        .filter { it.backupPath in backupPaths && it.status == EntryStatus.VERIFIED && it.keepOriginal != keep }
        .map { it.copy(keepOriginal = keep) }

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
