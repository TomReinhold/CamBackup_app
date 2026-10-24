package de.trei.cambackup.settings

enum class VideoCodec(val label: String) {
    H264("H.264"),
    HEVC("HEVC"),
}

enum class FolderLayout {
    /** Unterordner nach Aufnahmedatum, z. B. "2026/01". */
    YEAR_MONTH,
    /** Alle Dateien direkt im Geräteordner. */
    FLAT,
}

/** Einstellungen für das Verkleinern auf dem Smartphone und die Ablage auf dem Medium. */
data class CompressionSettings(
    /** Längste Bildkante in Pixeln; null = Originalgröße beibehalten. */
    val imageMaxEdge: Int? = 3072,
    val jpegQuality: Int = 85,
    val keepGps: Boolean = true,
    /** Kürzere Videokante in Pixeln (720 = 720p); null = Originalauflösung. */
    val videoMaxShortEdge: Int? = 1080,
    val videoCodec: VideoCodec = VideoCodec.HEVC,
    val videoBitrateMbps: Int = 6,
    val videoLimit30Fps: Boolean = false,
    /** Nur Dateien verkleinern, die älter sind – aktuelle Fotos bleiben in Originalqualität. */
    val minAgeDays: Int = 30,
    /** Datei nur ersetzen, wenn sie dadurch mindestens so viel kleiner wird. */
    val minSavingsPercent: Int = 20,
    val folderLayout: FolderLayout = FolderLayout.YEAR_MONTH,
    /** Nur sichern, auf dem Smartphone nichts ersetzen. */
    val dryRun: Boolean = true,
    /** Zu sichernde Ordner (RELATIVE_PATH, z. B. "DCIM/Camera/"). Mindestens einer. */
    val mediaFolders: Set<String> = setOf(DEFAULT_FOLDER),
) {
    /** Bei mehreren Ordnern bekommt jeder auf dem Medium einen eigenen Unterordner. */
    val separateFolders: Boolean get() = mediaFolders.size > 1

    companion object {
        const val DEFAULT_FOLDER = "DCIM/Camera/"
        val IMAGE_EDGE_OPTIONS = listOf(2048, 3072, 4096, null)
        val VIDEO_EDGE_OPTIONS = listOf(720, 1080, null)
        val MIN_AGE_OPTIONS = listOf(0, 7, 30, 90, 365)
        val QUALITY_RANGE = 50..95
        val BITRATE_RANGE = 2..12
        val MIN_SAVINGS_RANGE = 5..50
        const val AUDIO_BITRATE_KBPS = 128
    }
}
