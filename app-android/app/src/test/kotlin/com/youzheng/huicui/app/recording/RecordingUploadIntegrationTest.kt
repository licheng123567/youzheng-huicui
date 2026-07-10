package com.youzheng.huicui.app.recording

import com.youzheng.huicui.app.api.infrastructure.Serializer
import com.youzheng.huicui.app.data.db.UploadItemEntity
import com.youzheng.huicui.app.data.net.RecordingUploadApi
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.File
import java.nio.file.Files

/**
 * **真后端集成测试**：用生产代码里的 [RetrofitRecordingUploadPort] 打一个真实运行的后端，
 * 证明 multipart 形状、幂等键、409 判定都真的成立。
 *
 * 默认跳过（`Assume`），CI 不需要后端。本机这样跑：
 * ```
 * cd backend/app && mvn spring-boot:run          # 9091, dev profile
 * TOKEN=$(curl -s -X POST localhost:9091/v1/auth/login -H 'Content-Type: application/json' \
 *   -d '{"mode":"password","username":"jx_co1","password":"Admin@123"}' | jq -r .token)
 * cd app-android && HUICUI_E2E=1 HUICUI_TOKEN=$TOKEN HUICUI_CASE_ID=7 \
 *   ./gradlew :app:testDebugUnitTest --tests '*RecordingUploadIntegrationTest*'
 * ```
 *
 * Retrofit / OkHttp / kotlinx 都是纯 JVM，不需要模拟器 —— 这里跑的就是真机上会跑的那段代码。
 */
class RecordingUploadIntegrationTest {

    private val enabled = System.getenv("HUICUI_E2E") == "1"
    private val token = System.getenv("HUICUI_TOKEN").orEmpty()
    private val caseId = System.getenv("HUICUI_CASE_ID") ?: "7"
    private val baseUrl = System.getenv("HUICUI_BASE") ?: "http://localhost:9091/v1/"

    private fun port(): RecordingUploadPort {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
            })
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(
                Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()),
            )
            .build()
        return RetrofitRecordingUploadPort(retrofit.create(RecordingUploadApi::class.java))
    }

    private fun tempAudio(content: ByteArray): File {
        val f = Files.createTempFile("rec-", ".m4a").toFile()
        f.writeBytes(content)
        f.deleteOnExit()
        return f
    }

    private fun item(f: File, hash: String) = UploadItemEntity(
        fileHash = hash,
        callId = "c-int-test",
        caseId = caseId,
        filePath = f.absolutePath,
        fileName = f.name,
        sizeBytes = f.length(),
        recordedAtMillis = 1_783_564_200_000,   // 2026-07-09T10:30:00+08:00
        durationSec = 95,
        phone = "13900000099",
        source = "APP_AUTO",
        status = UploadStatus.PENDING.name,
        createdTs = 0,
    )

    @Test
    fun `真后端：首次上传 202 成功，同一哈希重传 409 视为成功`() {
        assumeTrue("未设 HUICUI_E2E=1，跳过真后端集成测试", enabled)
        assumeTrue("未设 HUICUI_TOKEN", token.isNotBlank())

        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val f = tempAudio(bytes)
        val hash = FileHash.sha256(f)          // 幂等键 = 文件内容哈希
        val p = port()

        runBlocking {
            val first = p.upload(item(f, hash))
            assertTrue("首传应成功，实际 $first", first is UploadOutcome.Success)
            first as UploadOutcome.Success
            assertEquals(false, first.idempotentReplay)
            assertTrue("首传应带回 recordingId", !first.recordingId.isNullOrBlank())

            // 同一文件（同一 hash）再传一次：服务端返 409「幂等键重放」
            val second = p.upload(item(f, hash))
            assertTrue("重传应被判为成功（幂等），实际 $second", second is UploadOutcome.Success)
            assertEquals(true, (second as UploadOutcome.Success).idempotentReplay)
        }
    }

    @Test
    fun `真后端：文件不存在判永久失败 不进重试循环`() {
        assumeTrue(enabled)
        assumeTrue(token.isNotBlank())
        val ghost = File("/definitely/not/here.m4a")
        runBlocking {
            val r = port().upload(item(ghost, "deadbeef"))
            assertTrue(r is UploadOutcome.Permanent)
        }
    }

    @Test
    fun `真后端：不存在的案件返回永久失败`() {
        assumeTrue(enabled)
        assumeTrue(token.isNotBlank())
        val f = tempAudio(ByteArray(512) { 7 })
        val hash = FileHash.sha256(f)
        runBlocking {
            val r = port().upload(item(f, hash).copy(caseId = "99999999"))
            assertTrue("不存在的案件应永久失败，实际 $r", r is UploadOutcome.Permanent)
        }
    }
}
