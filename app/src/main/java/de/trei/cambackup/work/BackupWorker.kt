package de.trei.cambackup.work

import android.content.Context
import android.app.NotificationManager
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
import de.trei.cambackup.backup.BackupProgress
import de.trei.cambackup.backup.BackupRunner
import de.trei.cambackup.backup.LastRunStore
import de.trei.cambackup.backup.RunSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Führt das Backup als Vordergrunddienst aus, damit es auch bei ausgeschaltetem Bildschirm
 * bzw. im Hintergrund weiterläuft. Wird der Prozess beendet (z. B. weil das Medium abgezogen
 * wurde), startet WorkManager den Job beim nächsten Prozessstart erneut; der Lauf setzt dann
 * anhand des Index-Journals fort.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val runner = BackupRunner(context)
    private val lastRun = LastRunStore(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(p: BackupProgress?) = ForegroundInfo(
        Notifications.ID_PROGRESS,
        Notifications.progress(applicationContext, p, WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        val folder = inputData.getString(KEY_FOLDER) ?: return Result.failure()
        try {
            setForeground(foregroundInfo(null))
        } catch (e: Exception) {
            // Start aus dem Hintergrund nicht erlaubt (Android 12+) – Job läuft dann ohne Vordergrunddienst
            Log.w(TAG, "Vordergrunddienst nicht möglich", e)
        }

        val summary = try {
            BackupRunner.lock.withLock {
                val prepared = runner.prepare(folder)
                var lastPublish = 0L
                // execute() schließt auch bei Abbruch sauber ab und liefert eine Zusammenfassung
                runner.execute(prepared, userCancelled = { isStopped }) { p ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPublish >= PUBLISH_INTERVAL_MS) {
                        lastPublish = now
                        publish(p)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RunSummary(Instant.now().toString(), 0, 0, 0, emptyList(), e.message ?: e.javaClass.simpleName)
        }

        lastRun.save(summary)
        notifications.cancel(Notifications.ID_PROGRESS)
        notifications.notify(Notifications.ID_RESULT, Notifications.result(applicationContext, summary))
        return Result.success()
    }

    /** Meldet den Fortschritt an Oberfläche (WorkInfo) und Benachrichtigung. */
    private fun publish(p: BackupProgress) {
        if (isStopped) return
        setProgressAsync(
            workDataOf(
                KEY_FILE_INDEX to p.fileIndex,
                KEY_FILE_COUNT to p.fileCount,
                KEY_NAME to p.currentName,
                KEY_BYTES_DONE to p.bytesDone,
                KEY_BYTES_TOTAL to p.bytesTotal,
            ),
        )
        notifications.notify(
            Notifications.ID_PROGRESS,
            Notifications.progress(applicationContext, p, WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)),
        )
    }

    companion object {
        const val UNIQUE_NAME = "backup"
        private const val TAG = "BackupWorker"
        private const val PUBLISH_INTERVAL_MS = 500L

        private const val KEY_FOLDER = "folder"
        private const val KEY_FILE_INDEX = "fileIndex"
        private const val KEY_FILE_COUNT = "fileCount"
        private const val KEY_NAME = "name"
        private const val KEY_BYTES_DONE = "bytesDone"
        private const val KEY_BYTES_TOTAL = "bytesTotal"

        fun start(context: Context, deviceFolderName: String) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(workDataOf(KEY_FOLDER to deviceFolderName))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // KEEP: ein bereits laufendes Backup wird nicht ein zweites Mal gestartet
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        /** Liest den Fortschritt aus den Progress-Daten eines laufenden Jobs. */
        fun progressOf(data: Data): BackupProgress? {
            val total = data.getLong(KEY_BYTES_TOTAL, -1)
            if (total < 0) return null
            return BackupProgress(
                fileIndex = data.getInt(KEY_FILE_INDEX, 0),
                fileCount = data.getInt(KEY_FILE_COUNT, 0),
                currentName = data.getString(KEY_NAME).orEmpty(),
                bytesDone = data.getLong(KEY_BYTES_DONE, 0),
                bytesTotal = total,
            )
        }
    }
}
