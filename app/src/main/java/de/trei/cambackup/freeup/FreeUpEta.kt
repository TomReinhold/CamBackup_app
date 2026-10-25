package de.trei.cambackup.freeup

/**
 * Restdauer beim Verkleinern. Anders als beim Backup zählen hier nicht die Bytes: ein Foto kostet
 * ungefähr gleich viel Zeit, egal wie groß es ist, ein Video dagegen je Sekunde Laufzeit (es wird neu
 * kodiert). Beide Raten werden getrennt aus den fertigen Dateien gelernt; bis dahin gelten vorsichtige
 * Annahmen, die mit jeder Datei weniger Gewicht haben. Übersprungene Dateien zählen mit – sie kommen
 * im Rest ja genauso vor.
 */
class FreeUpEta(
    /** Annahme je Foto, bis genug gemessen ist. */
    private val priorImageMillis: Long = 1_500,
    /** Annahme je Sekunde Video (etwa doppelte Echtzeit beim Hardware-Kodieren). */
    private val priorVideoMillisPerSecond: Long = 500,
) {
    private var imageMillis = 0L
    private var images = 0
    private var videoMillis = 0L
    private var videoSeconds = 0.0

    /** Eine Datei ist fertig (verkleinert oder übersprungen) und hat [tookMillis] gedauert. */
    fun record(isVideo: Boolean, durationMs: Long, tookMillis: Long) {
        if (isVideo) {
            videoMillis += tookMillis
            videoSeconds += durationMs / 1000.0
        } else {
            imageMillis += tookMillis
            images++
        }
    }

    /** Geschätzte Restdauer in Sekunden für die noch offenen Dateien, -1 solange nichts gemessen ist. */
    fun remainingSeconds(remainingImages: Int, remainingVideoSeconds: Double): Long {
        if (images == 0 && videoSeconds == 0.0 && videoMillis == 0L) return -1
        // Die Annahme zählt wie PRIOR_WEIGHT Fotos bzw. PRIOR_WEIGHT * 10 s Video
        val perImage = (imageMillis + priorImageMillis * PRIOR_WEIGHT) / (images + PRIOR_WEIGHT).toDouble()
        val priorVideo = PRIOR_WEIGHT * 10.0
        val perVideoSecond = (videoMillis + priorVideoMillisPerSecond * priorVideo) / (videoSeconds + priorVideo)
        return ((remainingImages * perImage + remainingVideoSeconds * perVideoSecond) / 1000).toLong()
    }

    private companion object {
        const val PRIOR_WEIGHT = 2
    }
}
