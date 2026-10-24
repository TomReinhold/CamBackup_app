package de.trei.cambackup.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import java.io.Closeable

/**
 * Bemerkt, wenn der Datenträger mit dem Backup-Ordner ausgeworfen wird („Sicher entfernen“ oder
 * Abziehen). Android meldet das Auswerfen, bevor es den Datenträger aushängt – so kann ein Lauf
 * die aktuelle Datei sauber verwerfen und Index und Protokoll noch schreiben, statt von vold
 * beendet zu werden.
 */
class EjectWatcher(private val context: Context, private val volumeId: String) : Closeable {

    @Volatile
    var ejecting = false
        private set

    // Samsung hängt USB-Datenträger unsichtbar ein und schickt dafür keine MEDIA_*-Broadcasts -
    // den Zustandswechsel (z. B. „Sicher entfernen“) meldet dann nur der StorageVolumeCallback
    private val storage = context.getSystemService(StorageManager::class.java)
    private val volumeCallback = object : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: StorageVolume) {
            if (volume.uuid.equals(volumeId, ignoreCase = true) && volume.state != Environment.MEDIA_MOUNTED) ejecting = true
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // intent.data ist der Einhängepunkt, z. B. file:///storage/1234-ABCD oder file:///mnt/media_rw/1234-ABCD
            val id = intent.data?.path?.trimEnd('/')?.substringAfterLast('/') ?: return
            if (id.equals(volumeId, ignoreCase = true)) ejecting = true
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        storage.registerStorageVolumeCallback(ContextCompat.getMainExecutor(context), volumeCallback)
    }

    override fun close() {
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { storage.unregisterStorageVolumeCallback(volumeCallback) }
    }
}
