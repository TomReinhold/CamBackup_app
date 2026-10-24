package de.trei.cambackup.storage

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Datei-Hilfen für das Storage Access Framework.
 *
 * Neue Dateien werden mit MIME-Typ application/octet-stream angelegt: nur so lässt der
 * Provider den Namen unverändert (bei anderen Typen hängt er ggf. eine Endung an).
 */
class SafIo(@PublishedApi internal val resolver: ContentResolver) {

    fun readText(file: DocumentFile): String =
        resolver.openInputStream(file.uri)?.use { it.readBytes().decodeToString() }
            ?: throw IOException("${file.name} nicht lesbar")

    fun createFile(dir: DocumentFile, name: String): DocumentFile =
        dir.createFile(OCTET_STREAM, name) ?: throw IOException("„$name“ konnte nicht angelegt werden")

    /**
     * Öffnet [file] zum Schreiben und führt [block] aus; danach werden die Daten per fsync
     * auf das Medium geschrieben, bevor die Datei als fertig gilt.
     */
    inline fun <T> writeSynced(file: DocumentFile, mode: String = "wt", block: (OutputStream) -> T): T =
        writeSynced(file.uri, file.name ?: "Datei", mode, block)

    /** Wie oben, für beliebige URIs (z. B. MediaStore-Einträge auf dem Smartphone). */
    inline fun <T> writeSynced(uri: Uri, name: String, mode: String = "wt", block: (OutputStream) -> T): T {
        val pfd = resolver.openFileDescriptor(uri, mode) ?: throw IOException("$name nicht beschreibbar")
        return pfd.use {
            FileOutputStream(it.fileDescriptor).use { out ->
                val result = block(out)
                out.flush()
                out.fd.sync()
                result
            }
        }
    }

    /** Hängt [text] an die Datei [name] in [dir] an (legt sie bei Bedarf an). */
    fun appendText(dir: DocumentFile, name: String, text: String) {
        val file = dir.findFile(name) ?: createFile(dir, name)
        writeSynced(file, "wa") { it.write(text.encodeToByteArray()) }
    }

    /**
     * Ersetzt [name] in [dir] möglichst atomar: erst "<name>.tmp" vollständig schreiben,
     * dann altes Original löschen und umbenennen. Bricht der Vorgang dazwischen ab, bleibt
     * die .tmp-Datei vollständig erhalten (siehe [readWithTmpFallback]).
     */
    fun replaceAtomically(dir: DocumentFile, name: String, text: String) {
        val tmpName = "$name.tmp"
        dir.findFile(tmpName)?.delete()
        val tmp = createFile(dir, tmpName)
        writeSynced(tmp) { it.write(text.encodeToByteArray()) }
        dir.findFile(name)?.let { if (!it.delete()) throw IOException("„$name“ konnte nicht ersetzt werden") }
        if (!tmp.renameTo(name)) throw IOException("„$tmpName“ konnte nicht umbenannt werden")
    }

    /** Liest [name]; fehlt die Datei nach einem Abbruch in [replaceAtomically], wird "<name>.tmp" gelesen. */
    fun readWithTmpFallback(dir: DocumentFile, name: String): String? {
        dir.findFile(name)?.let { return readText(it) }
        return dir.findFile("$name.tmp")?.let(::readText)
    }

    companion object {
        const val OCTET_STREAM = "application/octet-stream"
    }
}

/**
 * Zwischenspeicher für Unterordner und deren Dateilisten. DocumentFile.findFile listet bei
 * jedem Aufruf den ganzen Ordner – bei tausenden Fotos pro Monat wäre das zu langsam.
 */
class DirCache(private val base: DocumentFile) {
    private val dirs = HashMap<String, DocumentFile>()
    private val listings = HashMap<String, MutableMap<String, DocumentFile>>()

    /** Liefert (und erzeugt bei Bedarf) den Unterordner [subPath], z. B. "2026/01". */
    fun dir(subPath: String): DocumentFile = dirs.getOrPut(subPath) {
        subPath.split('/').filter { it.isNotEmpty() }.fold(base) { parent, segment ->
            val existing = parent.findFile(segment)
            when {
                existing == null -> parent.createDirectory(segment)
                    ?: throw IOException("Ordner „$segment“ konnte nicht angelegt werden")
                existing.isDirectory -> existing
                else -> throw IOException("„$segment“ ist eine Datei, kein Ordner")
            }
        }
    }

    /** Dateien in [subPath] nach Namen; wird von Aufrufern bei Änderungen aktualisiert. */
    fun files(subPath: String): MutableMap<String, DocumentFile> = listings.getOrPut(subPath) {
        dir(subPath).listFiles().filter { it.name != null }.associateByTo(HashMap()) { it.name!! }
    }
}
