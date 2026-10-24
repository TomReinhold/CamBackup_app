package de.trei.cambackup

import de.trei.cambackup.backup.copyWithSha256
import de.trei.cambackup.backup.sha256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HashingTest {

    @Test fun knownHash() = runBlocking {
        val r = sha256(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", r.sha256)
        assertEquals(3, r.bytes)
    }

    @Test fun copyProducesIdenticalBytesAndHash() = runBlocking {
        val data = ByteArray(3 * 1024 * 1024 + 7) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        val copied = copyWithSha256(ByteArrayInputStream(data), out)
        assertArrayEquals(data, out.toByteArray())
        assertEquals(sha256(ByteArrayInputStream(data)), copied)
    }
}
