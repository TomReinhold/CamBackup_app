package de.trei.cambackup

import de.trei.cambackup.backup.EtaEstimator
import de.trei.cambackup.util.remainingHoursMinutes
import org.junit.Assert.assertEquals
import org.junit.Test

class EtaTest {

    @Test
    fun `keine Schätzung in der Anlaufphase`() {
        val eta = EtaEstimator()
        assertEquals(-1, eta.update(0, 0, 1000))
        assertEquals(-1, eta.update(5_000, 100, 1000))
    }

    @Test
    fun `gleichmäßiger Durchsatz`() {
        val eta = EtaEstimator()
        // 10 MB/s, 1 GB gesamt
        val mb = 1_000_000L
        for (s in 0..20L) eta.update(s * 1000, s * 10 * mb, 1000 * mb)
        assertEquals(78, eta.update(22_000, 220 * mb, 1000 * mb))
    }

    @Test
    fun `passt sich an langsameren Durchsatz an`() {
        val eta = EtaEstimator(windowMillis = 10_000, warmupMillis = 1_000)
        val mb = 1_000_000L
        var bytes = 0L
        for (s in 0..30L) { eta.update(s * 1000, bytes, 10_000 * mb); bytes += 100 * mb }
        // ab jetzt nur noch 10 MB/s
        var t = 31_000L
        var last = 0L
        repeat(20) { bytes += 10 * mb; last = eta.update(t, bytes, 10_000 * mb); t += 1000 }
        val remaining = (10_000 * mb - bytes) / (10 * mb)
        assertEquals(remaining.toDouble(), last.toDouble(), remaining * 0.05)
    }

    @Test
    fun `Restdauer formatieren`() {
        assertEquals(null, remainingHoursMinutes(30))
        assertEquals(0L to 1L, remainingHoursMinutes(60))
        assertEquals(0L to 2L, remainingHoursMinutes(61))
        assertEquals(1L to 0L, remainingHoursMinutes(3600))
        assertEquals(1L to 5L, remainingHoursMinutes(3900))
    }
}
