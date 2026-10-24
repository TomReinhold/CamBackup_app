package de.trei.cambackup.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Dauerhafte Ablage der [CompressionSettings]; fehlende Werte nehmen den Standard an. */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsStore

    val settings: Flow<CompressionSettings> = store.data.map(::fromPreferences)

    suspend fun current(): CompressionSettings = settings.first()

    suspend fun update(transform: (CompressionSettings) -> CompressionSettings) {
        store.edit { prefs -> write(prefs, transform(fromPreferences(prefs))) }
    }

    suspend fun reset() {
        store.edit { it.clear() }
    }

    private fun fromPreferences(p: Preferences): CompressionSettings {
        val d = CompressionSettings()
        return CompressionSettings(
            imageMaxEdge = optionalInt(p[IMAGE_EDGE], d.imageMaxEdge),
            jpegQuality = p[QUALITY] ?: d.jpegQuality,
            keepGps = p[KEEP_GPS] ?: d.keepGps,
            videoMaxShortEdge = optionalInt(p[VIDEO_EDGE], d.videoMaxShortEdge),
            videoCodec = p[CODEC]?.let { runCatching { VideoCodec.valueOf(it) }.getOrNull() } ?: d.videoCodec,
            videoBitrateMbps = p[BITRATE] ?: d.videoBitrateMbps,
            videoLimit30Fps = p[FPS30] ?: d.videoLimit30Fps,
            minAgeDays = p[MIN_AGE] ?: d.minAgeDays,
            minSavingsPercent = p[MIN_SAVINGS] ?: d.minSavingsPercent,
            folderLayout = p[LAYOUT]?.let { runCatching { FolderLayout.valueOf(it) }.getOrNull() } ?: d.folderLayout,
            dryRun = p[DRY_RUN] ?: d.dryRun,
            mediaFolders = p[FOLDERS]?.takeIf { it.isNotEmpty() } ?: d.mediaFolders,
        )
    }

    /** Gespeichert wird 0 für "Original" (null); fehlt der Wert, gilt der Standard. */
    private fun optionalInt(stored: Int?, default: Int?): Int? = when (stored) {
        null -> default
        0 -> null
        else -> stored
    }

    private fun write(p: MutablePreferences, s: CompressionSettings) {
        p[IMAGE_EDGE] = s.imageMaxEdge ?: 0
        p[QUALITY] = s.jpegQuality
        p[KEEP_GPS] = s.keepGps
        p[VIDEO_EDGE] = s.videoMaxShortEdge ?: 0
        p[CODEC] = s.videoCodec.name
        p[BITRATE] = s.videoBitrateMbps
        p[FPS30] = s.videoLimit30Fps
        p[MIN_AGE] = s.minAgeDays
        p[MIN_SAVINGS] = s.minSavingsPercent
        p[LAYOUT] = s.folderLayout.name
        p[DRY_RUN] = s.dryRun
        p[FOLDERS] = s.mediaFolders
    }

    private companion object {
        val IMAGE_EDGE = intPreferencesKey("image_max_edge")
        val QUALITY = intPreferencesKey("jpeg_quality")
        val KEEP_GPS = booleanPreferencesKey("keep_gps")
        val VIDEO_EDGE = intPreferencesKey("video_max_short_edge")
        val CODEC = stringPreferencesKey("video_codec")
        val BITRATE = intPreferencesKey("video_bitrate_mbps")
        val FPS30 = booleanPreferencesKey("video_limit_30fps")
        val MIN_AGE = intPreferencesKey("min_age_days")
        val MIN_SAVINGS = intPreferencesKey("min_savings_percent")
        val LAYOUT = stringPreferencesKey("folder_layout")
        val DRY_RUN = booleanPreferencesKey("dry_run")
        val FOLDERS = stringSetPreferencesKey("media_folders")
    }
}
