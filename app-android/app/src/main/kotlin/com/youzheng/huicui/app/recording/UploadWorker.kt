package com.youzheng.huicui.app.recording

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.youzheng.huicui.app.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * 上传队列的执行者。WorkManager 负责「进程被杀/重启后继续」和网络约束；
 * 退避由**我们自己**按 [UploadPolicy] 算并写进 `nextAttemptAt`，
 * 不依赖 WorkManager 的退避——因为队列里可能同时有到点的和没到点的项。
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!ServiceLocator.isInitialized) return Result.retry()
        val repo = ServiceLocator.recordingRepository
        val due = repo.due()
        if (due.isEmpty()) {
            repo.purgeUploadedLocalFiles()
            return Result.success()
        }

        var sawRetryable = false
        for (item in due) {
            when (repo.uploadOnce(item)) {
                is UploadOutcome.Retryable -> sawRetryable = true
                else -> Unit
            }
        }
        repo.purgeUploadedLocalFiles()

        // 还有可重试的：让 WorkManager 按 nextAttemptAt 再叫一次。
        // 这里返回 retry 会走 WorkManager 自己的退避（最短 10s），
        // 但真正的节流是 dueForUpload 的 nextAttemptAt 过滤——不会真的每 10s 打一次服务端。
        return if (sawRetryable) Result.retry() else Result.success()
    }
}

object UploadScheduler {

    private const val UNIQUE_NOW = "upload_now"
    private const val UNIQUE_PERIODIC = "upload_periodic"

    /** 录音刚入队：尽快发。 */
    fun enqueueNow(context: Context) {
        val onlyWifi = ServiceLocator.takeIf { it.isInitialized }?.settings?.uploadOnWifiOnly ?: false
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(onlyWifi))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /**
     * 周期兜底（PRD §3.2 兜底层）：处理「FileObserver 被 ROM 杀死」「上传一直失败」等情况。
     * 30min 是 WorkManager 允许的最小周期。
     */
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<UploadWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints(onlyWifi = false))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun constraints(onlyWifi: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (onlyWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()
}
