package de.trei.cambackup.work

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.trei.cambackup.backup.LastRunStore
import de.trei.cambackup.storage.DetectResult
import de.trei.cambackup.storage.DriveDetector
import de.trei.cambackup.storage.DriveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import de.trei.cambackup.util.L10n
import de.trei.cambackup.R

/**
 * Meldet sich, wenn ein Datenträger angeschlossen wird, und sucht dort den Backup-Ordner dieses
 * Smartphones. Das Backup selbst startet bewusst erst nach Bestätigung in der App.
 *
 * Zwei Auslöser: MEDIA_MOUNTED kommt nur für Datenträger, die Android für Apps sichtbar einhängt
 * (z. B. SD-Karten). Samsung hängt USB-Datenträger unsichtbar ein und schickt kein MEDIA_MOUNTED –
 * dann bleibt nur USB_DEVICE_ATTACHED; der Datenträger wird erst Sekunden später eingehängt, deshalb
 * wartet [DriveCheckWorker] darauf.
 */
class MediaMountReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> DriveCheckWorker.start(context)
            Intent.ACTION_MEDIA_MOUNTED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Broadcasts dürfen nur kurz laufen – die Suche ist auf wenige Sekunden begrenzt
                        withTimeoutOrNull(8_000) { DriveDetector(context).detect() }?.let { notifyResult(context, it) }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

/** Wartet nach dem Anschließen eines USB-Geräts, bis ein neuer Datenträger eingehängt ist, und sucht dann. */
class DriveCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val drives = DriveRepository(applicationContext)
        val before = drives.removableVolumes().map { it.id }.toSet()
        // Ein USB-Gerät ohne Speicher (Tastatur, Hub) hängt nichts ein – dann einfach wieder aufhören
        repeat(WAIT_SECONDS) {
            if (drives.removableVolumes().any { it.id !in before }) {
                notifyResult(applicationContext, DriveDetector(applicationContext).detect())
                return Result.success()
            }
            delay(1_000)
        }
        return Result.success()
    }

    companion object {
        private const val WAIT_SECONDS = 30

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<DriveCheckWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("drive_check", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

private fun notifyResult(context: Context, result: DetectResult) {
    Notifications.createChannels(context)
    // Wurde das letzte Backup unterbrochen (z. B. Medium abgezogen), gleich das Fortsetzen anbieten
    val interrupted = LastRunStore(context).unseen()?.abortReason != null
    val resumeHint = if (interrupted) " " + L10n.str(R.string.notif_resume_hint) else ""
    val notification = when (result) {
        is DetectResult.Active -> Notifications.driveDetected(
            context, L10n.str(R.string.notif_folder_connected, result.folder.record.displayName) + resumeHint,
        )
        is DetectResult.Activated -> Notifications.driveDetected(
            context, L10n.str(R.string.notif_folder_found, result.folder.record.displayName, result.volumeLabel) + resumeHint,
        )
        is DetectResult.NotFound -> result.newVolume?.let {
            Notifications.driveDetected(context, L10n.str(R.string.notif_new_volume, it.label))
        }
    } ?: return
    context.getSystemService(NotificationManager::class.java).notify(Notifications.ID_DRIVE, notification)
}
