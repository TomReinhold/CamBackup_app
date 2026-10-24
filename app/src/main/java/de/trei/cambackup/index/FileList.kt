package de.trei.cambackup.index

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Lesbare Liste aller Sicherungen in `Metadata` – zum Nachschlagen am PC, z. B. in Excel. */
const val FILE_LIST = "dateiliste.csv"

private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Eine Zeile je gesicherter Datei: wo sie auf dem Medium liegt und woher sie auf dem Smartphone kam.
 * Semikolon als Trenner und UTF-8 mit BOM, damit Excel (deutsch) Spalten und Umlaute richtig erkennt.
 */
fun fileListCsv(entries: Collection<IndexEntry>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
    append('﻿')
    appendRow(
        "Datei auf dem Medium", "Pfad auf dem Smartphone", "Aufnahmedatum", "Typ", "Größe Original (Bytes)",
        "Status", "Größe auf dem Smartphone (Bytes)", "Verkleinert am", "Nicht verkleinern", "SHA-256 Original",
    )
    for (e in entries.filter { !it.deleted }.sortedBy { it.backupPath }) {
        val compressed = e.status == EntryStatus.COMPRESSED
        appendRow(
            e.backupPath,
            e.phonePath,
            DATE.format(Instant.ofEpochMilli(e.dateTakenMillis).atZone(zone)),
            e.mimeType,
            e.originalSize.toString(),
            if (compressed) "Auf dem Smartphone verkleinert" else "Gesichert",
            if (compressed) e.compressedSize?.toString().orEmpty() else e.originalSize.toString(),
            e.compressedAt?.takeIf { compressed }?.let { runCatching { DATE.format(Instant.parse(it).atZone(zone)) }.getOrDefault(it) }.orEmpty(),
            if (e.keepOriginal) "ja" else "",
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
