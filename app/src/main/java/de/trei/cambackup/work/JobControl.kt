package de.trei.cambackup.work

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Pausieren laufender Vorgänge. Ein pausierter Lauf hält zwischen zwei Dateien an und läuft beim
 * Fortsetzen genau dort weiter – Vordergrunddienst und Fortschritt bleiben erhalten. Abbrechen
 * beendet auch einen pausierten Lauf sofort. Der Zustand gilt nur im laufenden Prozess.
 */
object JobControl {
    const val BACKUP = "backup"
    const val FREE_UP = "freeup"
    const val RESTORE = "restore"
    const val MISSING = "missing"
    const val TRIAL = "trial"

    private val flags = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    private fun flag(job: String) = flags.getOrPut(job) { MutableStateFlow(false) }

    fun paused(job: String): StateFlow<Boolean> = flag(job)

    fun setPaused(job: String, paused: Boolean) {
        flag(job).value = paused
    }

    /** Wartet, solange [job] pausiert ist – oder bis [stop] meldet, dass der Lauf enden soll. */
    suspend fun waitWhilePaused(job: String, stop: () -> Boolean = { false }) {
        while (flag(job).value && !stop()) delay(250)
    }
}
