package de.trei.cambackup.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.trei.cambackup.index.IndexCache
import de.trei.cambackup.index.IndexState
import de.trei.cambackup.media.MediaAccess
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.media.MediaPermissions
import de.trei.cambackup.media.MediaScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import de.trei.cambackup.settings.SettingsRepository
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryState {
    data object Loading : LibraryState
    data object NeedsPermission : LibraryState
    data object PartialAccess : LibraryState
    data class Loaded(val items: List<LibraryItem>, val stats: LibraryStats) : LibraryState
    data class Error(val message: String) : LibraryState
}

/** Kamera-Dateien samt Sicherungsstatus – gemeinsam für Übersicht und Fotoansicht. */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = MediaScanner(app.contentResolver)
    private val settings = SettingsRepository(app)
    private val scan = MutableStateFlow<ScanState>(ScanState.Loading)
    private var job: Job? = null

    private sealed interface ScanState {
        data object Loading : ScanState
        data object NeedsPermission : ScanState
        data object Partial : ScanState
        data class Done(val items: List<MediaItem>) : ScanState
        data class Failed(val message: String) : ScanState
    }

    val state: StateFlow<LibraryState> = combine(scan, IndexCache.get(app).entries) { s, entries ->
        when (s) {
            ScanState.Loading -> LibraryState.Loading
            ScanState.NeedsPermission -> LibraryState.NeedsPermission
            ScanState.Partial -> LibraryState.PartialAccess
            is ScanState.Failed -> LibraryState.Error(s.message)
            is ScanState.Done -> {
                val index = IndexState(entries)
                val items = s.items.map { classify(it, index) }
                LibraryState.Loaded(items, statsOf(items))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState.Loading)

    init {
        // Neu einlesen, sobald sich die Auswahl der Medienordner ändert
        viewModelScope.launch {
            settings.settings.map { it.mediaFolders }.distinctUntilChanged().drop(1).collect { refresh() }
        }
    }

    /** Berechtigung prüfen und Medienordner neu einlesen (z. B. nach Rückkehr in die App). */
    fun refresh() {
        when (MediaPermissions.currentAccess(getApplication())) {
            MediaAccess.NONE -> scan.value = ScanState.NeedsPermission
            MediaAccess.PARTIAL -> scan.value = ScanState.Partial
            MediaAccess.FULL -> {
                // Neueste Anfrage gewinnt – sonst bliebe nach einem Ersetzen ein veralteter Stand stehen
                job?.cancel()
                job = viewModelScope.launch {
                    scan.value = try {
                        ScanState.Done(scanner.scan(settings.current().mediaFolders))
                    } catch (e: Exception) {
                        ScanState.Failed(e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
    }
}
