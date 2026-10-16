package com.mobile.utilities.calculator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@Keep
class SecureWipeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "CalculatorWipeWorker"
        private const val CHANNEL_ID = "calc_wipe"
        private const val CHANNEL_NAME = "Datenbereinigung"
        private const val NOTIFICATION_ID = 0x9B17
        private const val MAX_ATTEMPTS = 5
    }

    override suspend fun doWork(): Result {
        val ok = withContext(Dispatchers.IO) {
            SecureDelete.wipeFilesOnly(applicationContext)
        }
        if (ok) return Result.success()

        Log.w(TAG, "Shreddern unvollstaendig (Versuch ${runAttemptCount + 1} von $MAX_ATTEMPTS)")
        return if (runAttemptCount + 1 < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            Log.e(TAG, "Shreddern nach $MAX_ATTEMPTS Versuchen abgebrochen")
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        buildNotification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    )

    private fun buildNotification(): Notification {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Interne Bereinigung (kein Netzwerkzugriff)"
                    setShowBadge(false)
                }
            )
        }

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_calculator_monochrome)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("Daten werden bereinigt")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }
}

object SecureWipeWork {

    private const val TAG = "CalculatorWipeWork"

    private const val UNIQUE_WORK_NAME = "calc-duress-file-wipe"

    private const val WORK_TAG = "calc_duress"

    fun enqueue(context: Context): Boolean {
        val appContext = context.applicationContext
        return try {
            val request = OneTimeWorkRequestBuilder<SecureWipeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .build()

            WorkManager.getInstance(appContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            true
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager-Auftrag fehlgeschlagen: ${e.javaClass.simpleName}")
            false
        }
    }

    fun enqueueOrFallback(context: Context): Boolean {
        if (enqueue(context)) return true

        Log.w(TAG, "Notpfad: Shreddern im App-Prozess (WorkManager nicht verfuegbar)")
        val appContext = context.applicationContext
        Thread({
            if (!SecureDelete.wipeFilesOnly(appContext)) {
                Log.w(TAG, "Notpfad-Wipe unvollstaendig")
            }
        }, "calc-wipe-fallback").apply {
            isDaemon = true
            start()
        }
        return false
    }
}
