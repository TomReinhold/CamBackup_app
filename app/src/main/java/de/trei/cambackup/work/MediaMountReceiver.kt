package de.trei.cambackup.work

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.trei.cambackup.storage.DriveRepository

/**
 * Meldet sich, wenn ein Datenträger eingehängt wird. Ist es das gewählte Backup-Medium, erscheint
 * eine Benachrichtigung. Das Backup selbst startet bewusst erst nach Bestätigung in der App.
 */
class MediaMountReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return
        if (!DriveRepository(context).isSelectedVolume(intent.data?.path)) return
        Notifications.createChannels(context)
        context.getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_DRIVE, Notifications.driveDetected(context))
    }
}
