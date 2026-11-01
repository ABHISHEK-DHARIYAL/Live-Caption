package com.clearcap.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.clearcap.app
import com.clearcap.session.Retranscriber
import com.clearcap.session.SessionBus

/** Transcribes a saved recording again in the background. Started only by the user (History, or the recovery banner). */
object Retranscribe {
    fun enqueue(ctx: Context, sessionId: String) {
        val req = OneTimeWorkRequestBuilder<RetranscribeWorker>().setInputData(Data.Builder().putString(KEY, sessionId).build()).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("retranscribe-$sessionId", ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(ctx: Context, sessionId: String) { WorkManager.getInstance(ctx).cancelUniqueWork("retranscribe-$sessionId") }

    const val KEY = "session_id"
}

class RetranscribeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val id = inputData.getString(Retranscribe.KEY) ?: return Result.failure()
        runCatching { setForeground(foreground(applicationContext, "Transcribing a saved recording", id = 22)) }
        SessionBus.update { it.copy(progress = 0f, progressLabel = "Starting", status = null, error = null) }
        val outcome = Retranscriber(app, app.repo, app.models).run(
            id, app.settings.current(),
            progress = { p, label -> SessionBus.update { it.copy(progress = p.coerceIn(0f, 1f), progressLabel = "$label: ${(p * 100).toInt()}%") } },
            cancelled = { isStopped },
        )
        return when (outcome) {
            is Retranscriber.Outcome.Done -> { SessionBus.update { it.copy(progress = null, savedId = id, status = "Finished transcribing the saved audio.") }; Result.success() }
            is Retranscriber.Outcome.Failed -> { SessionBus.update { it.copy(progress = null, error = outcome.reason) }; Result.failure() }
        }
    }
}
