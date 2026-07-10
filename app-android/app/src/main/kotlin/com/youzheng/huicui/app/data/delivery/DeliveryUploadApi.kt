package com.youzheng.huicui.app.data.delivery

import com.youzheng.huicui.app.api.models.CaseAttachment
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * `POST /cases/{id}/attachments` 手写版，理由与 [com.youzheng.huicui.app.data.net.RecordingUploadApi]
 * 完全相同：生成物把 `deliveryType` 出成 `@Part kotlin.String?`，Retrofit 会把它丢给
 * kotlinx-json converter 序列化成**带引号的** `"COLLECTION_NOTICE"` —— 后端白名单
 * （AttachmentController.DELIVERY_TYPES）匹配不上，delivery_type 落 NULL，
 * 照片就成了普通跟进附件，**不进协调员的「送达管理」列表**。这是静默数据丢失，不是报错。
 * 所以文本字段显式做成 RequestBody(text/plain)，裸值无歧义。
 */
interface DeliveryUploadApi {

    @Multipart
    @POST("cases/{id}/attachments")
    suspend fun uploadAttachment(
        @Path("id") caseId: String,
        @Part file: MultipartBody.Part,
        @Part("deliveryType") deliveryType: RequestBody,
    ): Response<CaseAttachment>
}
