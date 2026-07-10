package com.youzheng.huicui.app.recording

import kotlin.math.min
import kotlin.math.pow

/** 上传重试退避与队列策略（PRD §3.4）。纯函数。 */
object UploadPolicy {

    const val MAX_RETRIES = 8
    private const val BASE_DELAY_MS = 60_000L        // 1min
    private const val MAX_DELAY_MS = 30 * 60_000L    // 封顶 30min

    /**
     * 指数退避 1/2/4/8…min，封顶 30min。
     * @param retryCount 已经失败过几次（首次失败后调用时传 1）
     */
    fun backoffMillis(retryCount: Int): Long {
        require(retryCount >= 1) { "retryCount 从 1 起算" }
        val exp = 2.0.pow((retryCount - 1).coerceAtMost(20))
        val delay = (BASE_DELAY_MS * exp).toLong().coerceAtLeast(BASE_DELAY_MS)
        return min(delay, MAX_DELAY_MS)
    }

    fun shouldGiveUp(retryCount: Int): Boolean = retryCount >= MAX_RETRIES

    /**
     * 文件是否「写完了」。ROM 常常先写临时文件再重命名，
     * FileObserver 的 CLOSE_WRITE 也可能在缓冲刷盘前就到 —— 半截文件传上去 ASR 只会失败。
     *
     * 判据：连续 [requiredStableSamples] 次采样，大小不变且 > 0。
     * @param sizeSamples 按时间顺序采到的文件大小
     */
    fun isFileStable(sizeSamples: List<Long>, requiredStableSamples: Int = 3): Boolean {
        if (sizeSamples.size < requiredStableSamples) return false
        val tail = sizeSamples.takeLast(requiredStableSamples)
        return tail.first() > 0 && tail.all { it == tail.first() }
    }

    /** 上传成功后本地录音的保留期（PRD §3.4 默认 7 天）。 */
    const val LOCAL_RETENTION_DAYS = 7L

    fun shouldDeleteLocal(uploadedAtMillis: Long, now: Long, retentionDays: Long = LOCAL_RETENTION_DAYS): Boolean =
        now - uploadedAtMillis >= retentionDays * 24 * 3600 * 1000
}

/** 上传队列项的状态（BR-APP-07：队列对用户可见可管）。 */
enum class UploadStatus {
    /** 已匹配、等待上传 */
    PENDING,

    /** 正在上传 */
    UPLOADING,

    /** 上传失败，等待下一次退避重试 */
    RETRYING,

    /** 重试耗尽，需用户手动处理 */
    FAILED,

    /** 服务端已接收（202 或幂等 409） */
    UPLOADED,

    /** 归属不明，等用户二选一（PRD §3.3） */
    NEEDS_CONFIRMATION,
}
