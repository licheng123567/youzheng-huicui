package com.youzheng.huicui.app.data.delivery

import com.youzheng.huicui.app.api.models.Activity
import com.youzheng.huicui.app.api.models.CaseAttachment
import com.youzheng.huicui.app.api.models.EvidenceInput
import com.youzheng.huicui.app.api.models.EvidenceItem
import com.youzheng.huicui.app.api.models.FollowUpInput
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * 三步编排（上传 → 记跟进 → 可选存证）的失败阶梯与权限分叉。
 * 网络与后端全部造假，跑的是纯 JVM —— 真 multipart 形状由 DeliveryUploadIntegrationTest 打真后端验。
 */
class DeliverySubmitterTest {

    private class FakePort : DeliveryApiPort {
        val uploads = mutableListOf<Pair<String, String>>()          // caseId to deliveryType
        var followUp: FollowUpInput? = null
        var evidence: EvidenceInput? = null
        var evidenceKey: String? = null

        var failUploadAt: Int = -1        // 第 N 张（0 起）上传返回 500
        var uploadThrows = false
        var followUpFails = false
        var evidenceFails = false

        private var seq = 0

        override suspend fun uploadAttachment(caseId: String, file: File, deliveryType: String): Response<CaseAttachment> {
            if (uploadThrows) throw IOException("网络断了")
            val n = seq++
            if (n == failUploadAt) return Response.error(500, "boom".toResponseBody("text/plain".toMediaType()))
            uploads += caseId to deliveryType
            return Response.success(CaseAttachment(id = "a$n", name = file.name, url = "/v1/attachments/a$n"))
        }

        override suspend fun createFollowUp(caseId: String, input: FollowUpInput): Response<Activity> {
            if (followUpFails) return Response.error(500, "boom".toResponseBody("text/plain".toMediaType()))
            followUp = input
            return Response.success(Activity(id = "f1"))
        }

        override suspend fun createEvidence(caseId: String, input: EvidenceInput, idempotencyKey: String): Response<EvidenceItem> {
            if (evidenceFails) return Response.error(502, "boom".toResponseBody("text/plain".toMediaType()))
            evidence = input
            evidenceKey = idempotencyKey
            return Response.success(EvidenceItem(id = "e1"))
        }
    }

    private fun photo(n: Int): File =
        Files.createTempFile("p$n-", ".jpg").toFile().apply { writeBytes(ByteArray(64) { 1 }); deleteOnExit() }

    // ── 快乐路径 ──

    @Test
    fun `协调员勾了存证：三步全走，refIds=附件id，幂等键确定性`() = runBlocking {
        val port = FakePort()
        val r = DeliverySubmitter(port).submit("28", listOf(photo(1), photo(2)), DeliveryType.COLLECTION_NOTICE, "门贴", withEvidence = true)

        assertTrue(r is DeliverySubmitter.Result.Success)
        assertEquals(true, (r as DeliverySubmitter.Result.Success).evidenced)
        assertEquals(listOf("28" to "COLLECTION_NOTICE", "28" to "COLLECTION_NOTICE"), port.uploads)
        assertEquals(FollowUpInput.Method.VISIT, port.followUp?.method)          // 上门场景，不是 OTHER
        assertEquals(listOf("a0", "a1"), port.evidence?.refIds)
        assertEquals("delivery-28-a0-a1", port.evidenceKey)                       // 同一批照片重试 → 同一个键
    }

    @Test
    fun `催收员没有存证权限：withEvidence=false，evidence 端点一次都不能碰`() = runBlocking {
        val port = FakePort()
        val r = DeliverySubmitter(port).submit("28", listOf(photo(1)), DeliveryType.OTHER, null, withEvidence = false)

        assertTrue(r is DeliverySubmitter.Result.Success)
        assertEquals(false, (r as DeliverySubmitter.Result.Success).evidenced)
        assertNull("催收员无 evidence.create，编排层不该发这个请求让后端 403", port.evidence)
    }

    @Test
    fun `备注为空时跟进内容自动生成，带送达类型中文名`() = runBlocking {
        val port = FakePort()
        DeliverySubmitter(port).submit("28", listOf(photo(1)), DeliveryType.LAWYER_LETTER, "  ", withEvidence = false)
        assertTrue(port.followUp!!.content.startsWith("上门送达（律师函）："))
    }

    // ── 失败阶梯 ──

    @Test
    fun `第2张上传失败：整体失败，不记跟进不存证，已传的id报给调用方`() = runBlocking {
        val port = FakePort().apply { failUploadAt = 1 }
        val r = DeliverySubmitter(port).submit("28", listOf(photo(1), photo(2)), DeliveryType.OTHER, null, withEvidence = true)

        assertTrue(r is DeliverySubmitter.Result.Failed)
        r as DeliverySubmitter.Result.Failed
        assertEquals(DeliverySubmitter.Stage.UPLOAD, r.stage)
        assertEquals(listOf("a0"), r.uploadedIds)
        assertNull("上传没走完就记跟进，时间线会和送达管理对不上数", port.followUp)
        assertNull(port.evidence)
    }

    @Test
    fun `上传抛IO异常：同样整体失败`() = runBlocking {
        val port = FakePort().apply { uploadThrows = true }
        val r = DeliverySubmitter(port).submit("28", listOf(photo(1)), DeliveryType.OTHER, null, withEvidence = false)
        assertTrue(r is DeliverySubmitter.Result.Failed)
        assertEquals(DeliverySubmitter.Stage.UPLOAD, (r as DeliverySubmitter.Result.Failed).stage)
    }

    @Test
    fun `记跟进失败：报 FOLLOW_UP 阶段失败，不发存证`() = runBlocking {
        val port = FakePort().apply { followUpFails = true }
        val r = DeliverySubmitter(port).submit("28", listOf(photo(1)), DeliveryType.OTHER, null, withEvidence = true)

        assertTrue(r is DeliverySubmitter.Result.Failed)
        assertEquals(DeliverySubmitter.Stage.FOLLOW_UP, (r as DeliverySubmitter.Result.Failed).stage)
        assertNull(port.evidence)
    }

    @Test
    fun `存证失败不算整体失败：照片和跟进已成，单独报 EvidenceFailed`() = runBlocking {
        val port = FakePort().apply { evidenceFails = true }
        val r = DeliverySubmitter(port).submit("28", listOf(photo(1)), DeliveryType.COURT_DOC, null, withEvidence = true)

        assertTrue("存证可在网页端补发起，不该把前两步的成果报成失败", r is DeliverySubmitter.Result.EvidenceFailed)
        assertEquals(listOf("a0"), (r as DeliverySubmitter.Result.EvidenceFailed).attachmentIds)
    }

    @Test
    fun `空照片列表直接拒绝`() {
        val port = FakePort()
        try {
            runBlocking { DeliverySubmitter(port).submit("28", emptyList(), DeliveryType.OTHER, null, false) }
            throw AssertionError("应当抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
