package com.clearcap.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.clearcap.R
import com.clearcap.app
import com.clearcap.models.DeviceInfoReader
import com.clearcap.models.DownloadState
import com.clearcap.models.ModelCatalog
import com.clearcap.models.ModelException
import com.clearcap.models.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Entry points for user-started model downloads. WorkManager only schedules them; the transfer itself is in [com.clearcap.models.ModelManager]. */
object ModelDownloads {
    fun enqueue(ctx: Context, m: ModelInfo, wifiOnly: Boolean) {
        val net = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(Data.Builder().putString(KEY_ID, m.id).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("model-${m.id}")
            .build()
        ctx.app.models.setState(m, DownloadState(0f, if (wifiOnly) "Waiting for Wi-Fi" else "Waiting for network"))
        WorkManager.getInstance(ctx).enqueueUniqueWork("model-${m.id}", ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(ctx: Context, m: ModelInfo) {
        WorkManager.getInstance(ctx).cancelUniqueWork("model-${m.id}")
        ctx.app.models.setState(m, null)
    }

    const val KEY_ID = "model_id"
}

class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val m = ModelCatalog.byId(inputData.getString(ModelDownloads.KEY_ID)) ?: return Result.failure()
        runCatching { setForeground(foreground(applicationContext, "Downloading ${m.label}")) } // may be refused in some states; the download still runs
        return try {
            withContext(Dispatchers.IO) { app.models.downloadBlocking(m, DeviceInfoReader.read(app)) { isActive } }
            Result.success()
        } catch (e: ModelException) {
            if (e.kind == ModelException.Kind.NETWORK && runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            app.models.setState(m, DownloadState(error = e.message ?: e.javaClass.simpleName))
            Result.failure()
        }
    }
}

internal const val WORK_CHANNEL = "work"

/** Foreground notification for long background jobs (downloads, re-checking saved audio). */
internal fun foreground(ctx: Context, text: String, id: Int = 21): ForegroundInfo {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(WORK_CHANNEL, "Downloads and processing", NotificationManager.IMPORTANCE_LOW))
    val n: Notification = Notification.Builder(ctx, WORK_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Clearcap").setContentText(text).setOngoing(true).build()
    return ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}
