package de.trei.cambackup.work

import de.trei.cambackup.util.formatRemaining
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.trei.cambackup.backup.BackupRunner
import de.trei.cambackup.freeup.FreeUpProgress
import de.trei.cambackup.freeup.FreeUpRunner
import de.trei.cambackup.freeup.FreeUpStore
import de.trei.cambackup.freeup.FreeUpSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import de.trei.cambackup.util.fileCount
import de.trei.cambackup.util.toMsg
import de.trei.cambackup.util.L10n
import de.trei.cambackup.R

/** Verkleinert und ersetzt die Dateien bzw. rechnet den Probelauf – im Vordergrunddienst, da Videos lange dauern. */
class FreeUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val runner = FreeUpRunner(context)
    private val store = FreeUpStore.get(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val replacing get() = inputData.getBoolean(KEY_REPLACE, false)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(p: FreeUpProgress?) = ForegroundInfo(
        Notifications.ID_PROGRESS,
        Notifications.simpleProgress(
            applicationContext,
            title = L10n.str(if (replacing) R.string.notif_compressing else R.string.notif_dry_running),
            text = p?.let {
                L10n.str(R.string.notif_n_of_total, it.done + 1, it.total) +
                    (if (it.remainingSeconds >= 0) " · ${formatRemaining(it.remainingSeconds)}" else "")
            } ?: L10n.str(R.string.restore_starting),
            percent = p?.let { if (it.total > 0) it.done * 100 / it.total else 0 },
            cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        ),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        JobControl.setPaused(JobControl.FREE_UP, false)
        runCatching { setForeground(foregroundInfo(null)) }.onFailure { Log.w(TAG, "Vordergrunddienst nicht möglich", it) }
        var lastPublish = 0L
        val publish = { p: FreeUpProgress ->
            val now = SystemClock.elapsedRealtime()
            if (!isStopped && now - lastPublish >= 500) {
                lastPublish = now
                setProgressAsync(workDataOf(KEY_DONE to p.done, KEY_TOTAL to p.total, KEY_NAME to p.currentName, KEY_REMAINING to p.remainingSeconds))
                notifications.notify(Notifications.ID_PROGRESS, foregroundInfo(p).notification)
            }
        }
        val summary = try {
            BackupRunner.lock.withLock {
                runner.run(inputData.getString(KEY_FOLDER)!!, replacing, publish)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FreeUpSummary(Instant.now().toString(), replacing, 0, 0, emptyMap(), emptyList(), e.toMsg().ui)
        }
        store.saveSummary(summary)
        notifications.cancel(Notifications.ID_PROGRESS)
        notifications.notify(Notifications.ID_RESULT, Notifications.freeUpResult(applicationContext, summary))
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "freeup"
        private const val TAG = "FreeUpWorker"
        private const val KEY_REPLACE = "replace"
        private const val KEY_FOLDER = "folder"
        private const val KEY_DONE = "done"
        private const val KEY_TOTAL = "total"
        private const val KEY_NAME = "name"
        private const val KEY_REMAINING = "remaining"

        /** Probelauf: nur berechnen, wie viel Platz frei würde. */
        fun dryRun(context: Context, folderUri: String, tag: String) = enqueue(context, workDataOf(KEY_FOLDER to folderUri), tag)

        /** Nur aufrufen, nachdem der Nutzer dem Schreiben per createWriteRequest zugestimmt hat. */
        fun compress(context: Context, folderUri: String, tag: String) =
            enqueue(context, workDataOf(KEY_FOLDER to folderUri, KEY_REPLACE to true), tag)

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        private fun enqueue(context: Context, data: Data, tag: String) {
            val request = OneTimeWorkRequestBuilder<FreeUpWorker>()
                .setInputData(data)
                .addTag(tag)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun progressOf(data: Data): FreeUpProgress? {
            val total = data.getInt(KEY_TOTAL, -1)
            if (total < 0) return null
            return FreeUpProgress(
                data.getInt(KEY_DONE, 0), total, data.getString(KEY_NAME).orEmpty(), replacing = false,
                remainingSeconds = data.getLong(KEY_REMAINING, -1),
            )
        }
    }
}
