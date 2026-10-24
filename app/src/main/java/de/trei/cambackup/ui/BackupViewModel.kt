package de.trei.cambackup.ui

import android.app.Application
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.trei.cambackup.backup.BackupEngine
import de.trei.cambackup.backup.BackupPlan
import de.trei.cambackup.backup.BackupProgress
import de.trei.cambackup.backup.BackupResult
import de.trei.cambackup.backup.SpaceCheck
import de.trei.cambackup.backup.checkSpace
import de.trei.cambackup.backup.phonePath
import de.trei.cambackup.backup.planBackup
import de.trei.cambackup.index.BackupIndex
import de.trei.cambackup.media.MediaScanner
import de.trei.cambackup.storage.DeviceRegistry
import de.trei.cambackup.storage.DriveRepository
import de.trei.cambackup.storage.SafIo
import de.trei.cambackup.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

sealed interface BackupState {
    data object Preparing : BackupState
    data class Ready(val plan: BackupPlan, val space: SpaceCheck, val indexedCount: Int) : BackupState
    data class Running(val progress: BackupProgress?, val cancelling: Boolean) : BackupState
    data class Finished(val result: BackupResult) : BackupState
    data class Error(val message: String) : BackupState
}

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val drives = DriveRepository(app)
    private val io = SafIo(app.contentResolver)
    private val scanner = MediaScanner(app.contentResolver)
    private val engine = BackupEngine(app)
    private val registry = DeviceRegistry(app, app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?")

    private val _state = MutableStateFlow<BackupState>(BackupState.Preparing)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private class Prepared(val deviceDir: DocumentFile, val index: BackupIndex, val plan: BackupPlan)

    private var prepared: Prepared? = null
    private var job: Job? = null
    private val cancelRequested = AtomicBoolean(false)

    /** Automatisches Neuplanen (Bildschirmstart, Rückkehr in die App) – lässt eine Zusammenfassung stehen. */
    fun prepareIfIdle(deviceFolderName: String) {
        if (_state.value is BackupState.Finished) return
        prepare(deviceFolderName)
    }

    /** Lädt Index und Kamera-Dateien und berechnet, was zu sichern ist. */
    fun prepare(deviceFolderName: String) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = BackupState.Preparing
            _state.value = try {
                val root = drives.openRoot() ?: throw IOException("Speichermedium nicht verbunden")
                val deviceDir = root.findFile(deviceFolderName) ?: throw IOException("Geräteordner „$deviceFolderName“ fehlt")
                val metadata = deviceDir.findFile(DeviceRegistry.METADATA_DIR) ?: throw IOException("Ordner Metadata fehlt")
                val index = BackupIndex.open(io, metadata)
                engine.cleanupStaging(deviceDir)
                val info = drives.info(drives.selectedTree!!)
                val plan = planBackup(scanner.scanCameraFolder(), index.state, info.fsType)
                prepared = Prepared(deviceDir, index, plan)
                BackupState.Ready(plan, checkSpace(plan.bytesToCopy, info.space?.freeBytes), index.state.size)
            } catch (e: Exception) {
                BackupState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun start() {
        val p = prepared ?: return
        val ready = _state.value as? BackupState.Ready ?: return
        // Sicherheitsnetz: die UI bietet den Start bei zu wenig Platz gar nicht erst an
        if (ready.space is SpaceCheck.NotEnough || p.plan.toCopy.isEmpty()) return
        if (job?.isActive == true) return

        cancelRequested.set(false)
        job = viewModelScope.launch {
            _state.value = BackupState.Running(null, cancelling = false)
            val result = engine.run(p.deviceDir, p.plan, p.index, cancelRequested::get) { progress ->
                _state.value = BackupState.Running(progress, cancelRequested.get())
            }
            _state.value = try {
                finish(p, result)
                BackupState.Finished(result)
            } catch (e: Exception) {
                // Index-Journal ist trotzdem geschrieben – beim nächsten Lauf geht nichts verloren
                BackupState.Finished(result.copy(abortReason = result.abortReason ?: "Abschluss fehlgeschlagen: ${e.message}"))
            }
        }
    }

    fun cancel() {
        cancelRequested.set(true)
        (_state.value as? BackupState.Running)?.let { _state.value = it.copy(cancelling = true) }
    }

    private suspend fun finish(p: Prepared, result: BackupResult) = withContext(Dispatchers.IO) {
        if (!p.deviceDir.exists()) return@withContext
        p.index.compact()
        engine.cleanupStaging(p.deviceDir)
        val now = Instant.now()
        if (result.copied + result.adopted > 0) registry.markBackup(p.deviceDir, now)
        val metadata = p.deviceDir.findFile(DeviceRegistry.METADATA_DIR) ?: return@withContext
        val line = buildString {
            append("$now  Backup: ${result.copied} kopiert (${formatBytes(result.bytesCopied)})")
            if (result.adopted > 0) append(", ${result.adopted} übernommen")
            append(", ${result.failures.size} Fehler")
            result.abortReason?.let { append(" – abgebrochen: $it") }
            append("\n")
            result.failures.forEach { append("    Fehler: ${it.item.phonePath}: ${it.reason}\n") }
        }
        io.appendText(metadata, LOG_FILE, line)
    }

    private companion object {
        const val LOG_FILE = "backup_log.txt"
    }
}
