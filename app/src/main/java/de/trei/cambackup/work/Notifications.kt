package de.trei.cambackup.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.trei.cambackup.MainActivity
import de.trei.cambackup.R
import de.trei.cambackup.backup.BackupProgress
import de.trei.cambackup.backup.RunSummary
import de.trei.cambackup.freeup.FreeUpSummary
import de.trei.cambackup.util.L10n
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.formatBytes
import de.trei.cambackup.util.formatRemaining

object Notifications {
    const val CHANNEL_PROGRESS = "backup_progress"
    const val CHANNEL_EVENTS = "backup_events"

    const val ID_PROGRESS = 1001
    const val ID_RESULT = 1002
    const val ID_DRIVE = 1003

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, L10n.str(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, L10n.str(R.string.channel_events), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, p: BackupProgress?, cancelIntent: PendingIntent) =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(L10n.str(R.string.notif_backup_running))
            .setContentText(
                when {
                    p == null || p.fileCount == 0 -> L10n.str(R.string.notif_preparing)
                    p.moving -> L10n.str(R.string.backup_moving_progress, minOf(p.fileIndex + 1, p.fileCount), p.fileCount)
                    else -> L10n.str(R.string.notif_bytes_of, formatBytes(p.bytesDone / 2), formatBytes(p.bytesTotal / 2)) +
                        (if (p.remainingSeconds >= 0) " · ${formatRemaining(p.remainingSeconds)}" else "")
                },
            )
            .setProgress(
                1000,
                p?.let {
                    when {
                        it.moving -> if (it.fileCount > 0) it.fileIndex * 1000 / it.fileCount else 0
                        it.bytesTotal > 0 -> (it.bytesDone * 1000 / it.bytesTotal).toInt()
                        else -> 0
                    }
                } ?: 0,
                p == null,
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(0, L10n.str(R.string.action_cancel), cancelIntent)
            .build()

    fun result(context: Context, s: RunSummary) =
        NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(L10n.str(if (s.isComplete) R.string.notif_backup_done else R.string.notif_backup_incomplete))
            .setContentText(
                buildString {
                    when {
                        s.copied > 0 -> append(L10n.str(R.string.notif_backed_up, fileCount(s.copied), formatBytes(s.bytesCopied)))
                        s.moved > 0 -> append(L10n.str(R.string.notif_relocated))
                        else -> append(L10n.str(R.string.notif_nothing_new))
                    }
                    if (s.moved > 0) append(L10n.str(R.string.notif_moved_suffix, s.moved))
                    if (s.failures.isNotEmpty()) append(L10n.str(R.string.notif_errors_suffix, s.failures.size))
                    s.abortReason?.let { append(" – $it") }
                },
            )
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

    fun simpleProgress(context: Context, title: String, text: String, percent: Int?, cancelIntent: PendingIntent) =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(0, L10n.str(R.string.action_cancel), cancelIntent)
            .build()

    fun freeUpResult(context: Context, s: FreeUpSummary) =
        NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                L10n.str(
                    when {
                        s.abortReason != null -> R.string.notif_freeup_aborted
                        s.replacing -> R.string.notif_freeup_done
                        else -> R.string.notif_dry_run_done
                    },
                ),
            )
            .setContentText(
                buildString {
                    if (s.replacing) append(L10n.str(R.string.notif_freeup_text, fileCount(s.done), formatBytes(s.bytesSaved)))
                    else append(L10n.str(R.string.notif_dry_run_text, fileCount(s.done), formatBytes(s.bytesSaved)))
                    if (s.failures.isNotEmpty()) append(L10n.str(R.string.notif_errors_suffix, s.failures.size))
                    s.abortReason?.let { append(" – $it") }
                },
            )
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

    fun driveDetected(context: Context, text: String) =
        NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(L10n.str(R.string.notif_drive_detected))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
}
