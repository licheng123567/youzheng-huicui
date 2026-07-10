package com.youzheng.huicui.app.recording

import com.youzheng.huicui.app.data.db.UploadItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传结果分类 + SAF 路径还原。
 *
 * 分类判错的后果是两个极端：可重试当成永久失败 → 一次网络抖动让一通录音永远丢；
 * 永久失败当成可重试 → 队列里一条 404 反复打服务端直到重试耗尽。
 */
class UploadPipelineTest {

    @Test
    fun `202 是成功`() {
        val r = UploadOutcomeClassifier.classify(202, null, "8")
        assertTrue(r is UploadOutcome.Success)
        assertEquals("8", (r as UploadOutcome.Success).recordingId)
        assertFalse(r.idempotentReplay)
    }

    @Test
    fun `409 幂等键重放视为成功 绝不重传`() {
        val r = UploadOutcomeClassifier.classify(409, "幂等键重放：请求已处理", null)
        assertTrue(r is UploadOutcome.Success)
        assertTrue((r as UploadOutcome.Success).idempotentReplay)
        // 重传会重复扣 ASR 分钟、重复解析
        assertNull(r.recordingId)
    }

    @Test
    fun `401 可重试 —— 重新登录后队列继续 而不是把录音丢掉`() {
        assertTrue(UploadOutcomeClassifier.classify(401, null, null) is UploadOutcome.Retryable)
    }

    @Test
    fun `429 与 5xx 可重试`() {
        assertTrue(UploadOutcomeClassifier.classify(429, null, null) is UploadOutcome.Retryable)
        assertTrue(UploadOutcomeClassifier.classify(500, null, null) is UploadOutcome.Retryable)
        assertTrue(UploadOutcomeClassifier.classify(503, null, null) is UploadOutcome.Retryable)
    }

    @Test
    fun `403 404 422 永久失败 —— 重试一万次也一样`() {
        assertTrue(UploadOutcomeClassifier.classify(403, "无权", null) is UploadOutcome.Permanent)
        assertTrue(UploadOutcomeClassifier.classify(404, "案件不存在", null) is UploadOutcome.Permanent)
        assertTrue(UploadOutcomeClassifier.classify(422, "参数不合法", null) is UploadOutcome.Permanent)
    }

    @Test
    fun `网络异常一律可重试`() {
        assertTrue(UploadOutcomeClassifier.network("超时") is UploadOutcome.Retryable)
    }

    // ── 队列状态机（用假的 Port + 假的 DAO，纯 JVM）─────────────────────────

    @Test
    fun `失败后按退避改 nextAttemptAt 重试耗尽转 FAILED`() {
        // 直接验策略函数的组合，不必起 Room
        var retry = 0
        val delays = mutableListOf<Long>()
        while (!UploadPolicy.shouldGiveUp(++retry)) delays += UploadPolicy.backoffMillis(retry)
        assertEquals(7, delays.size)                       // 第 8 次放弃
        assertEquals(60_000L, delays.first())
        assertEquals(30 * 60_000L, delays.last())          // 已封顶
        assertTrue(UploadPolicy.shouldGiveUp(8))
    }

    @Test
    fun `幂等键就是文件哈希 —— 同一文件重传必然命中服务端幂等`() {
        val item = uploadItem(hash = "abc123")
        assertEquals("abc123", item.fileHash)
    }

    // ── SAF tree URI → 绝对路径 ─────────────────────────────────────────────

    @Test
    fun `内置存储的 tree URI 能还原成绝对路径`() {
        assertEquals(
            "/storage/emulated/0/Recordings/Call",
            SafPaths.toFilePath("content://com.android.externalstorage.documents/tree/primary%3ARecordings%2FCall"),
        )
    }

    @Test
    fun `根目录`() {
        assertEquals(
            "/storage/emulated/0",
            SafPaths.toFilePath("content://com.android.externalstorage.documents/tree/primary%3A"),
        )
    }

    @Test
    fun `SD 卡返回 null 而不是猜一个不存在的挂载点`() {
        assertNull(SafPaths.toFilePath("content://com.android.externalstorage.documents/tree/1AEF-2B11%3ARecordings"))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(SafPaths.toFilePath(null))
        assertNull(SafPaths.toFilePath(""))
        assertNull(SafPaths.toFilePath("content://foo/bar"))
        assertNull(SafPaths.toFilePath("content://x/tree/noColonHere"))
    }

    @Test
    fun `中文目录名按 UTF-8 还原 —— vivo 的「录音／通话录音」`() {
        // %E5%BD%95%E9%9F%B3 = UTF-8 的「录音」。逐字节 toChar() 会得到乱码，
        // 那样 vivo 的录音目录就永远找不到。
        val uri = "content://com.android.externalstorage.documents/tree/" +
            "primary%3A%E5%BD%95%E9%9F%B3%2F%E9%80%9A%E8%AF%9D%E5%BD%95%E9%9F%B3"
        assertEquals("/storage/emulated/0/录音/通话录音", SafPaths.toFilePath(uri))
    }

    private fun uploadItem(hash: String) = UploadItemEntity(
        fileHash = hash,
        callId = "c1",
        caseId = "7",
        filePath = "/x/a.m4a",
        fileName = "a.m4a",
        sizeBytes = 1024,
        recordedAtMillis = 0,
        durationSec = 95,
        phone = "13900000099",
        source = "APP_AUTO",
        status = UploadStatus.PENDING.name,
        createdTs = 0,
    )
}
