package com.youzheng.huicui.app.recording

/**
 * 上传结果分类。**纯函数，有单测**——这个判断错了，后果是两个极端：
 *   · 把「永久失败」当成「可重试」→ 队列里一条 404 的录音每 30 分钟重试一次，直到重试耗尽；
 *   · 把「可重试」当成「永久失败」→ 一次网络抖动就让一通录音永远丢失。
 */
sealed interface UploadOutcome {
    /** 服务端已接收（202），或幂等键重放（409 视为已上传，PRD §3.4）。 */
    data class Success(val recordingId: String?, val idempotentReplay: Boolean) : UploadOutcome

    /** 可重试：网络问题、服务端 5xx、限流。 */
    data class Retryable(val message: String) : UploadOutcome

    /** 永久失败：案件不存在/无权限/参数不合法。再重试一万次也一样。 */
    data class Permanent(val message: String) : UploadOutcome
}

object UploadOutcomeClassifier {

    fun classify(httpCode: Int, errorMessage: String?, recordingId: String?): UploadOutcome = when {
        httpCode == 202 || httpCode == 200 || httpCode == 201 ->
            UploadOutcome.Success(recordingId, idempotentReplay = false)

        // 幂等键重放：这条录音服务端早就收下了。视为成功，绝不重传（重传 = 重复扣 ASR 分钟）。
        httpCode == 409 ->
            UploadOutcome.Success(recordingId = null, idempotentReplay = true)

        // 401 交给 AuthInterceptor 去清 token 跳登录；对上传队列而言这条要留着，等重新登录后再传。
        httpCode == 401 -> UploadOutcome.Retryable(errorMessage ?: "登录已失效，重新登录后继续上传")

        httpCode == 429 -> UploadOutcome.Retryable(errorMessage ?: "服务端限流，稍后重试")

        httpCode in 500..599 -> UploadOutcome.Retryable(errorMessage ?: "服务端错误（$httpCode）")

        // 403 无权 / 404 案件不存在（被释放/结案了）/ 422 参数不合法 —— 重试没有意义
        httpCode in 400..499 -> UploadOutcome.Permanent(errorMessage ?: "上传被拒绝（$httpCode）")

        else -> UploadOutcome.Retryable("未知响应（$httpCode）")
    }

    /** 网络层异常一律可重试。 */
    fun network(message: String): UploadOutcome = UploadOutcome.Retryable(message)
}
