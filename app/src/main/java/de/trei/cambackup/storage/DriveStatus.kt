package de.trei.cambackup.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ist der Backup-Ordner dieses Smartphones gerade erreichbar? Wird von der Erkennung
 * (DriveViewModel) gesetzt; null, solange das noch nicht geprüft wurde.
 */
object DriveStatus {
    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected.asStateFlow()

    fun set(connected: Boolean) {
        _connected.value = connected
    }
}
