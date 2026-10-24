package de.trei.cambackup.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.trei.cambackup.media.MediaAccess
import de.trei.cambackup.media.MediaItem
import de.trei.cambackup.media.MediaPermissions
import de.trei.cambackup.media.MediaScanner
import de.trei.cambackup.media.MediaSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeState {
    data object NeedsPermission : HomeState
    data object PartialAccess : HomeState
    data object Loading : HomeState
    data class Loaded(val summary: MediaSummary, val items: List<MediaItem>) : HomeState
    data class Error(val message: String) : HomeState
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = MediaScanner(app.contentResolver)

    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /** Prüft die Berechtigung und scannt bei Vollzugriff den Kameraordner. */
    fun refresh() {
        when (MediaPermissions.currentAccess(getApplication())) {
            MediaAccess.NONE -> _state.value = HomeState.NeedsPermission
            MediaAccess.PARTIAL -> _state.value = HomeState.PartialAccess
            MediaAccess.FULL -> scan()
        }
    }

    private fun scan() {
        _state.value = HomeState.Loading
        viewModelScope.launch {
            _state.value = try {
                val items = scanner.scanCameraFolder()
                HomeState.Loaded(MediaSummary.of(items), items)
            } catch (e: Exception) {
                HomeState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
