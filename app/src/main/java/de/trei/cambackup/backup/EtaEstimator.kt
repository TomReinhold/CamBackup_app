package de.trei.cambackup.backup

/**
 * Schätzt die Restdauer aus dem bisherigen Durchsatz. Gemessen wird über ein gleitendes Zeitfenster,
 * damit sich die Schätzung an wechselnde Geschwindigkeiten anpasst (viele kleine Fotos sind langsamer
 * als ein großes Video, ein heißer Stick drosselt), ohne bei jeder Datei zu springen.
 */
class EtaEstimator(
    private val windowMillis: Long = 60_000,
    /** Erst ab dieser Laufzeit wird geschätzt – vorher ist der Durchsatz zu unsicher. */
    private val warmupMillis: Long = 10_000,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>() // Zeitpunkt (ms) to bearbeitete Bytes
    private var startMillis = -1L

    /** Neuer Messpunkt; liefert die geschätzte Restdauer in Sekunden oder -1. */
    fun update(nowMillis: Long, bytesDone: Long, bytesTotal: Long): Long {
        if (startMillis < 0) startMillis = nowMillis
        samples.addLast(nowMillis to bytesDone)
        while (samples.size > 2 && nowMillis - samples.first().first > windowMillis) samples.removeFirst()

        if (nowMillis - startMillis < warmupMillis) return -1
        val (t0, b0) = samples.first()
        val elapsed = nowMillis - t0
        val bytes = bytesDone - b0
        if (elapsed <= 0 || bytes <= 0) return -1
        val remaining = (bytesTotal - bytesDone).coerceAtLeast(0)
        return remaining * elapsed / bytes / 1000
    }
}
