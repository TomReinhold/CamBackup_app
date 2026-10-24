package de.trei.cambackup.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class StreamResult(val sha256: String, val bytes: Long)

private const val BUFFER_SIZE = 1 shl 20

/** Kopiert [input] nach [output] und berechnet dabei die SHA-256 der Daten. */
suspend fun copyWithSha256(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit = {}): StreamResult =
    digestStream(input, output, onBytes)

/** Berechnet die SHA-256 von [input]. */
suspend fun sha256(input: InputStream, onBytes: (Long) -> Unit = {}): StreamResult =
    digestStream(input, null, onBytes)

private suspend fun digestStream(input: InputStream, output: OutputStream?, onBytes: (Long) -> Unit): StreamResult {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
        output?.write(buffer, 0, n)
        total += n
        onBytes(total)
    }
    return StreamResult(digest.digest().toHex(), total)
}

private fun ByteArray.toHex(): String {
    val chars = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(chars[v shr 4]).append(chars[v and 0x0F])
    }
    return sb.toString()
}
