package androidx.work
import android.app.Notification
import android.content.Context
import java.util.concurrent.TimeUnit
class Data { class Builder { fun putString(k: String, v: String?): Builder = this; fun build() = Data() }; fun getString(k: String): String? = null }
class WorkerParameters
class ForegroundInfo(val id: Int, val n: Notification, val type: Int)
abstract class ListenableWorker(val applicationContext: Context, val params: WorkerParameters) {
    abstract class Result { companion object { fun success(): Result = TODO(); fun failure(): Result = TODO(); fun retry(): Result = TODO() } }
    val inputData: Data get() = TODO(); val runAttemptCount: Int get() = 0; val isStopped: Boolean get() = false
}
abstract class CoroutineWorker(ctx: Context, p: WorkerParameters) : ListenableWorker(ctx, p) { abstract suspend fun doWork(): Result; suspend fun setForeground(i: ForegroundInfo) {} }
enum class BackoffPolicy { EXPONENTIAL }
enum class NetworkType { UNMETERED, CONNECTED }
enum class ExistingWorkPolicy { KEEP }
class Constraints { class Builder { fun setRequiredNetworkType(t: NetworkType): Builder = this; fun build() = Constraints() } }
class OneTimeWorkRequest { class Builder { fun setInputData(d: Data) = this; fun setConstraints(c: Constraints) = this; fun setBackoffCriteria(p: BackoffPolicy, d: Long, u: TimeUnit) = this; fun addTag(t: String) = this; fun build() = OneTimeWorkRequest() } }
inline fun <reified W : ListenableWorker> OneTimeWorkRequestBuilder(): OneTimeWorkRequest.Builder = OneTimeWorkRequest.Builder()
class WorkManager { companion object { fun getInstance(c: Context) = WorkManager() }; fun enqueueUniqueWork(n: String, p: ExistingWorkPolicy, r: OneTimeWorkRequest) {}; fun cancelUniqueWork(n: String) {} }
