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

/** Stellt gelöschte Dateien wieder her bzw. löscht ihre Sicherungen – nach Entscheidung des Nutzers. */
class MissingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val store = MissingStore.get(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Wird gestartet …", null)

    private fun foregroundInfo(text: String, percent: Int?) = ForegroundInfo(
        Notifications.ID_PROGRESS,
        Notifications.simpleProgress(
            applicationContext, "Gelöschte Dateien bearbeiten", text, percent,
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        ),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        val decisions = store.decisions() ?: return Result.failure()
        runCatching { setForeground(foregroundInfo("Wird gestartet …", null)) }.onFailure { Log.w(TAG, "Vordergrunddienst nicht möglich", it) }
        var last = 0L
        val summary = try {
            BackupRunner.lock.withLock {
                MissingRunner(applicationContext).apply(decisions) { done, total, name ->
                    val now = SystemClock.elapsedRealtime()
                    if (!isStopped && now - last >= 500) {
                        last = now
                        setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_NAME to name))
                        notifications.notify(Notifications.ID_PROGRESS, foregroundInfo("${done + 1} von $total: $name", done * 100 / total.coerceAtLeast(1)).notification)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MissingSummary(Instant.now().toString(), 0, 0, emptyList(), e.message ?: e.javaClass.simpleName)
        }
        store.saveSummary(summary)
        notifications.cancel(Notifications.ID_PROGRESS)
        notifications.notify(
            Notifications.ID_RESULT,
            NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Gelöschte Dateien bearbeitet")
                .setContentText("${summary.restored} wiederhergestellt, ${summary.deleted} im Backup gelöscht" + (summary.abortReason?.let { " – $it" } ?: ""))
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
