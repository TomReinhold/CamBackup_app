package de.trei.cambackup.work

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.trei.cambackup.backup.LastRunStore
import de.trei.cambackup.storage.DetectResult
import de.trei.cambackup.storage.DriveDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Meldet sich, wenn ein Datenträger eingehängt wird, und sucht dort den Backup-Ordner dieses
 * Smartphones. Das Backup selbst startet bewusst erst nach Bestätigung in der App.
 */
class MediaMountReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Broadcasts dürfen nur kurz laufen – die Suche ist auf wenige Sekunden begrenzt
                val result = withTimeoutOrNull(8_000) { DriveDetector(context).detect() } ?: return@launch
                Notifications.createChannels(context)
                // Wurde das letzte Backup unterbrochen (z. B. Medium abgezogen), gleich das Fortsetzen anbieten
                val interrupted = LastRunStore(context).unseen()?.abortReason != null
                val resumeHint = if (interrupted) " Das letzte Backup wurde unterbrochen – tippen, um es fortzusetzen." else ""
                val notification = when (result) {
                    is DetectResult.Active -> Notifications.driveDetected(
                        context, "Backup-Ordner „${result.folder.record.displayName}“ ist verbunden.$resumeHint",
                    )
                    is DetectResult.Activated -> Notifications.driveDetected(
                        context, "Backup-Ordner „${result.folder.record.displayName}“ auf ${result.volumeLabel} gefunden.$resumeHint",
                    )
                    is DetectResult.NotFound -> result.newVolume?.let {
                        Notifications.driveDetected(context, "Neues Speichermedium „${it.label}“ – tippen, um den Backup-Ordner zu wählen.")
                    }
                } ?: return@launch
                context.getSystemService(NotificationManager::class.java).notify(Notifications.ID_DRIVE, notification)
            } finally {
                pending.finish()
            }
        }
    }
}
