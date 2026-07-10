package com.youzheng.huicui.app.data.net

import com.youzheng.huicui.app.api.models.CallRecording
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * `POST /cases/{id}/recordings` 手写版。
 *
 * 生成物把 `recordedAt` 出成 `java.time.OffsetDateTime`。Retrofit 对 `@Part` 的非 RequestBody 值
 * 会走 converter：`ScalarsConverterFactory` 只认 String/基本类型，于是 OffsetDateTime 落到
 * kotlinx-json converter 手里，被序列化成**带引号的 JSON 字符串**（`"2026-…Z"`）——
 * 而 multipart 的文本字段应当是裸值。实测后端对两种都容错，但**不该依赖服务端的宽容**：
 * 这里把所有文本字段都显式做成 `RequestBody`（text/plain），语义没有任何歧义。
 *
 * 另外 `Idempotency-Key` 必须由调用方给（= 文件 SHA-256），不能让 AuthInterceptor 兜底生成随机值——
 * 随机 key 等于取消幂等，重传会重复扣 ASR 分钟。
 */
interface RecordingUploadApi {

    @Multipart
    @POST("cases/{id}/recordings")
    suspend fun uploadRecording(
        @Path("id") caseId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Part file: MultipartBody.Part,
        @Part("source") source: RequestBody,
        @Part("recordedAt") recordedAt: RequestBody?,
        @Part("durationSec") durationSec: RequestBody?,
        @Part("phone") phone: RequestBody?,
    ): Response<CallRecording>
}
