package de.trei.cambackup

import de.trei.cambackup.util.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun bytes() = assertEquals("512 B", formatBytes(512))
    @Test fun kilobytes() = assertEquals("1,5 KB", formatBytes(1536))
    @Test fun megabytes() = assertEquals("3,0 MB", formatBytes(3L * 1024 * 1024))
    @Test fun gigabytes() = assertEquals("2,5 GB", formatBytes(2_684_354_560))
    @Test fun terabytesIsLargestUnit() = assertEquals("2048,0 TB", formatBytes(2048L shl 40))
}
