package de.trei.cambackup

import de.trei.cambackup.freeup.FreeUpEta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeUpEtaTest {

    @Test
    fun `keine Schätzung vor der ersten Datei`() {
        assertEquals(-1, FreeUpEta().remainingSeconds(100, 600.0))
    }

    @Test
    fun `Fotos nach Anzahl, Videos nach Laufzeit`() {
        val eta = FreeUpEta(priorImageMillis = 1_000, priorVideoMillisPerSecond = 500)
        // viele Messungen: 1 s je Foto, 0,5 s je Video-Sekunde – die Annahmen fallen kaum noch ins Gewicht
        repeat(200) { eta.record(isVideo = false, durationMs = 0, tookMillis = 1_000) }
        repeat(50) { eta.record(isVideo = true, durationMs = 60_000, tookMillis = 30_000) }
        // 100 Fotos (100 s) + 10 Minuten Video (300 s)
        assertEquals(400, eta.remainingSeconds(100, 600.0))
    }

    @Test
    fun `lernt langsamere Videos`() {
        val eta = FreeUpEta(priorVideoMillisPerSecond = 500)
        repeat(20) { eta.record(isVideo = true, durationMs = 10_000, tookMillis = 20_000) }
        // gemessen 2 s je Video-Sekunde statt angenommener 0,5 s
        val s = eta.remainingSeconds(0, 100.0)
        assertTrue("$s", s in 180..200)
    }
}
