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
import de.trei.cambackup.freeup.RestoreRunner
import de.trei.cambackup.freeup.RestoreStore
import de.trei.cambackup.freeup.RestoreSummary
import de.trei.cambackup.util.fileCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Holt die Originale verkleinerter Dateien zurück (nach Zustimmung per createWriteRequest). */
class RestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Wird gestartet …", null)

    private fun foregroundInfo(text: String, percent: Int?) = ForegroundInfo(
        Notifications.ID_PROGRESS,
        Notifications.simpleProgress(
            applicationContext, "Originale werden zurückgeholt", text, percent,
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        ),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        val folder = inputData.getString(KEY_FOLDER) ?: return Result.failure()
        val keep = inputData.getBoolean(KEY_KEEP, true)
        runCatching { setForeground(foregroundInfo("Wird gestartet …", null)) }.onFailure { Log.w(TAG, "Vordergrunddienst nicht möglich", it) }
        var last = 0L
        val summary = try {
            BackupRunner.lock.withLock {
                RestoreRunner(applicationContext).restoreAll(folder, keep, inputData.getLongArray(KEY_ONLY)?.toSet()) { done, total, name ->
                    val now = SystemClock.elapsedRealtime()
                    if (!isStopped && now - last >= 500) {
                        last = now
                        setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                        notifications.notify(Notifications.ID_PROGRESS, foregroundInfo("${done + 1} von $total: $name", done * 100 / total.coerceAtLeast(1)).notification)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RestoreSummary(Instant.now().toString(), 0, 0, emptyList(), e.message ?: e.javaClass.simpleName)
        }
        RestoreStore.get(applicationContext).save(summary)
        notifications.cancel(Notifications.ID_PROGRESS)
        notifications.notify(
            Notifications.ID_RESULT,
            NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Originale zurückgeholt")
                .setContentText("${fileCount(summary.restored)} wieder in voller Größe" + (summary.abortReason?.let { " – $it" } ?: ""))
                .setAutoCancel(true)
                .build(),
        )
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "restore"
        private const val TAG = "RestoreWorker"
        private const val KEY_FOLDER = "folder"
        private const val KEY_KEEP = "keep"
        private const val KEY_ONLY = "only"
        private const val KEY_DONE = "done"
        private const val KEY_TOTAL = "total"

        /**
         * Nur aufrufen, nachdem der Nutzer dem Schreiben per createWriteRequest zugestimmt hat – für
         * genau diese Dateien ([only] = MediaStore-IDs, null = alle verkleinerten).
         */
        fun start(context: Context, folderUri: String, keepOriginal: Boolean, only: LongArray? = null) {
            val request = OneTimeWorkRequestBuilder<RestoreWorker>()
                .setInputData(workDataOf(KEY_FOLDER to folderUri, KEY_KEEP to keepOriginal, KEY_ONLY to only))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)

        fun progressOf(data: Data): Pair<Int, Int>? {
            val total = data.getInt(KEY_TOTAL, -1)
            return if (total < 0) null else data.getInt(KEY_DONE, 0) to total
        }
    }
}
