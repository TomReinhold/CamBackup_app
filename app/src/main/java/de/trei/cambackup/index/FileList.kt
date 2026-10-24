package de.trei.cambackup.index

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Lesbare Liste aller Sicherungen in `Metadata` – zum Nachschlagen am PC, z. B. in Excel. */
const val FILE_LIST = "file_list.csv"

/** Frühere, deutsch benannte Liste – wird beim nächsten Schreiben entfernt. */
const val LEGACY_FILE_LIST = "dateiliste.csv"

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Byte Order Mark: Excel erkennt die Datei damit als UTF-8. */
private val BOM = Char(0xFEFF)

/**
 * Eine Zeile je gesicherter Datei: wo sie auf dem Medium liegt und woher sie auf dem Smartphone kam.
 * Immer englisch (wie das Protokoll). Semikolon als Trenner und UTF-8 mit BOM, damit Excel (auch deutsch)
 * Spalten und Sonderzeichen richtig erkennt.
 */
fun fileListCsv(entries: Collection<IndexEntry>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
    append(BOM)
    appendRow(
        "File on the storage device", "Path on the phone", "Date taken", "Type", "Original size (bytes)",
        "Status", "Size on the phone (bytes)", "Compressed at", "Don't compress", "SHA-256 original",
    )
    for (e in entries.filter { !it.deleted }.sortedBy { it.backupPath }) {
        val compressed = e.status == EntryStatus.COMPRESSED
        appendRow(
            e.backupPath,
            e.phonePath,
            DATE.format(Instant.ofEpochMilli(e.dateTakenMillis).atZone(zone)),
            e.mimeType,
            e.originalSize.toString(),
            if (compressed) "Compressed on the phone" else "Backed up",
            if (compressed) e.compressedSize?.toString().orEmpty() else e.originalSize.toString(),
            e.compressedAt?.takeIf { compressed }?.let { runCatching { DATE.format(Instant.parse(it).atZone(zone)) }.getOrDefault(it) }.orEmpty(),
            if (e.keepOriginal) "yes" else "",
            e.originalSha256,
        )
    }
}

private fun StringBuilder.appendRow(vararg fields: String) {
    fields.joinTo(this, ";") { f ->
        if (f.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + f.replace("\"", "\"\"") + "\"" else f
    }
    append("\r\n")
}
