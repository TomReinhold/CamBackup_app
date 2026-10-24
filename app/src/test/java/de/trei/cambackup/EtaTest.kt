package de.trei.cambackup

import de.trei.cambackup.backup.EtaEstimator
import de.trei.cambackup.util.formatRemaining
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
        assertEquals("noch weniger als 1 Min.", formatRemaining(30))
        assertEquals("noch ca. 1 Min.", formatRemaining(60))
        assertEquals("noch ca. 2 Min.", formatRemaining(61))
        assertEquals("noch ca. 1 Std.", formatRemaining(3600))
        assertEquals("noch ca. 1 Std. 5 Min.", formatRemaining(3900))
    }
}
