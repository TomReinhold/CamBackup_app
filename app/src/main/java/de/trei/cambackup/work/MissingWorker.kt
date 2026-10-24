package de.trei.cambackup.work

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.trei.cambackup.R
import de.trei.cambackup.backup.BackupRunner
import de.trei.cambackup.backup.MissingDecisions
import de.trei.cambackup.backup.MissingRunner
import de.trei.cambackup.backup.MissingStore
import de.trei.cambackup.backup.MissingSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.toMsg
import de.trei.cambackup.util.L10n

/** Stellt gelöschte Dateien wieder her bzw. löscht ihre Sicherungen – nach Entscheidung des Nutzers. */
class MissingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val store = MissingStore.get(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(L10n.str(R.string.restore_starting), null)

    private fun foregroundInfo(text: String, percent: Int?) = ForegroundInfo(
        Notifications.ID_PROGRESS,
        Notifications.simpleProgress(
            applicationContext, L10n.str(R.string.notif_missing), text, percent,
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        ),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        JobControl.setPaused(JobControl.MISSING, false)
        val decisions = store.decisions() ?: return Result.failure()
        runCatching { setForeground(foregroundInfo(L10n.str(R.string.restore_starting), null)) }.onFailure { Log.w(TAG, "Vordergrunddienst nicht möglich", it) }
        var last = 0L
        val summary = try {
            BackupRunner.lock.withLock {
                MissingRunner(applicationContext).apply(decisions) { done, total, name ->
                    val now = SystemClock.elapsedRealtime()
                    if (!isStopped && now - last >= 500) {
                        last = now
                        setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_NAME to name))
                        notifications.notify(Notifications.ID_PROGRESS, foregroundInfo(L10n.str(R.string.notif_n_of_total, done + 1, total), done * 100 / total.coerceAtLeast(1)).notification)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MissingSummary(Instant.now().toString(), 0, 0, emptyList(), e.toMsg().ui)
        }
        store.saveSummary(summary)
        notifications.cancel(Notifications.ID_PROGRESS)
        notifications.notify(
            Notifications.ID_RESULT,
            NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(L10n.str(R.string.notif_missing_done))
                .setContentText(L10n.str(R.string.missing_result, fileCount(summary.restored), fileCount(summary.deleted)) + (summary.abortReason?.let { " – $it" } ?: ""))
                .setAutoCancel(true)
                .build(),
        )
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "missing"
        private const val TAG = "MissingWorker"
        private const val KEY_DONE = "done"
        private const val KEY_TOTAL = "total"
        private const val KEY_NAME = "name"

        fun start(context: Context, decisions: MissingDecisions) {
            MissingStore.get(context).saveDecisions(decisions)
            val request = OneTimeWorkRequestBuilder<MissingWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun progressOf(data: Data): Pair<Int, Int>? {
            val total = data.getInt(KEY_TOTAL, -1)
            return if (total < 0) null else data.getInt(KEY_DONE, 0) to total
        }
    }
}
