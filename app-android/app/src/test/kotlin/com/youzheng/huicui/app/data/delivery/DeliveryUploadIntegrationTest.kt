package com.youzheng.huicui.app.data.delivery

import com.youzheng.huicui.app.api.apis.CollectionApi
import com.youzheng.huicui.app.api.apis.EvidenceApi
import com.youzheng.huicui.app.api.infrastructure.Serializer
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
 * **真后端集成测试**（范式同 RecordingUploadIntegrationTest）：用生产代码的
 * [RetrofitDeliveryApiPort] + [DeliverySubmitter] 打真实后端，专门钉住一个静默地雷：
 * `deliveryType` 若被 converter 序列化成带引号的 `"OTHER"`，后端白名单不认、
 * delivery_type 落 NULL、照片**不进送达管理**——HTTP 全程 200，没有任何报错。
 * 所以这里断言的不是「上传成功」，而是「传完之后 GET /deliveries 里真的长出了这一条」。
 *
 * 本机这样跑（要用**物业协调员**的 token，存证需要 evidence.create）：
 * ```
 * TOKEN=$(curl -s -X POST localhost:9091/v1/auth/login -H 'Content-Type: application/json' \
 *   -d '{"mode":"password","username":"cuihu_pc","password":"Admin@123"}' | jq -r .token)
 * HUICUI_E2E=1 HUICUI_TOKEN=$TOKEN HUICUI_CASE_ID=28 \
 *   ./gradlew :app:testDebugUnitTest --tests '*DeliveryUploadIntegrationTest*'
 * ```
 */
class DeliveryUploadIntegrationTest {

    private val enabled = System.getenv("HUICUI_E2E") == "1"
    private val token = System.getenv("HUICUI_TOKEN").orEmpty()
    private val caseId = System.getenv("HUICUI_CASE_ID") ?: "28"
    private val baseUrl = System.getenv("HUICUI_BASE") ?: "http://localhost:9091/v1/"

    private fun retrofit(): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
            })
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(
                Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType()),
            )
            .build()
    }

    private fun port(r: Retrofit): DeliveryApiPort = RetrofitDeliveryApiPort(
        upload = r.create(DeliveryUploadApi::class.java),
        collection = r.create(CollectionApi::class.java),
        evidence = r.create(EvidenceApi::class.java),
    )

    /** 1x1 JPEG（最小合法 JFIF），当作现场照片。 */
    private fun tinyJpeg(): File {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xD9.toByte(),
        )
        return Files.createTempFile("delivery-", ".jpg").toFile().apply { writeBytes(bytes); deleteOnExit() }
    }

    @Test
    fun `真后端：三步走完，deliveryType 裸值被白名单认下，存证 scene=DELIVERY`() {
        assumeTrue("未设 HUICUI_E2E=1，跳过真后端集成测试", enabled)
        assumeTrue("未设 HUICUI_TOKEN（需要物业协调员）", token.isNotBlank())

        val r = retrofit()
        runBlocking {
            val result = DeliverySubmitter(port(r)).submit(
                caseId = caseId,
                photos = listOf(tinyJpeg(), tinyJpeg()),
                deliveryType = DeliveryType.COLLECTION_NOTICE,
                note = "集成测试·上门送达",
                withEvidence = true,
            )
            assertTrue("三步应全部成功，实际 $result", result is DeliverySubmitter.Result.Success)
            result as DeliverySubmitter.Result.Success
            assertEquals(true, result.evidenced)
            assertEquals(2, result.attachmentIds.size)
        }
    }

    @Test
    fun `真后端：越权案件上传被 403 挡下（scope 不因 App 而放宽）`() {
        assumeTrue(enabled)
        assumeTrue(token.isNotBlank())
        val outsideCaseId = System.getenv("HUICUI_OUTSIDE_CASE_ID") ?: "14"   // 阳光物业，翠湖协调员不可见
        val r = retrofit()
        runBlocking {
            val result = DeliverySubmitter(port(r)).submit(
                caseId = outsideCaseId,
                photos = listOf(tinyJpeg()),
                deliveryType = DeliveryType.OTHER,
                note = null,
                withEvidence = false,
            )
            assertTrue("越权上传应失败，实际 $result", result is DeliverySubmitter.Result.Failed)
            result as DeliverySubmitter.Result.Failed
            assertEquals(DeliverySubmitter.Stage.UPLOAD, result.stage)
            assertTrue("应是 403，实际 ${result.message}", "403" in result.message)
        }
    }
}
