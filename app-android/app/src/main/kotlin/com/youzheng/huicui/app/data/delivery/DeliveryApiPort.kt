package com.youzheng.huicui.app.data.delivery

import com.youzheng.huicui.app.api.apis.CollectionApi
import com.youzheng.huicui.app.api.apis.EvidenceApi
import com.youzheng.huicui.app.api.models.Activity
import com.youzheng.huicui.app.api.models.CaseAttachment
import com.youzheng.huicui.app.api.models.EvidenceInput
import com.youzheng.huicui.app.api.models.EvidenceItem
import com.youzheng.huicui.app.api.models.FollowUpInput
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File

/**
 * 送达存证的三个写端点，收进一个窄接口（与 [com.youzheng.huicui.app.data.case.CaseApiPort] 同一套理由：
 * 单测给假实现、生成物签名漂移只改这一处）。
 *
 * 三个端点的权限**不一样**，这是 UI 分叉的根据：
 *   · 上传附件 / 记跟进 → `case.follow`（催收员、物业协调员都有）
 *   · 发起存证          → `evidence.create`（**只有物业侧有**，催收员没有）
 */
interface DeliveryApiPort {
    suspend fun uploadAttachment(caseId: String, file: File, deliveryType: String): Response<CaseAttachment>
    suspend fun createFollowUp(caseId: String, input: FollowUpInput): Response<Activity>

    /**
     * [idempotencyKey] 必须由调用方给确定性值（同一批附件 → 同一个键）。
     * 存证按次计费（BR-M6-03），传 null 会让 AuthInterceptor 兜底生成随机键——
     * 那等于取消幂等，网络抖动重试就是重复扣物业的存证费。
     */
    suspend fun createEvidence(caseId: String, input: EvidenceInput, idempotencyKey: String): Response<EvidenceItem>
}

class RetrofitDeliveryApiPort(
    private val upload: DeliveryUploadApi,
    private val collection: CollectionApi,
    private val evidence: EvidenceApi,
) : DeliveryApiPort {

    override suspend fun uploadAttachment(caseId: String, file: File, deliveryType: String): Response<CaseAttachment> {
        val part = MultipartBody.Part.createFormData(
            "file",
            file.name,
            file.asRequestBody("image/jpeg".toMediaType()),
        )
        return upload.uploadAttachment(caseId, part, deliveryType.toRequestBody("text/plain".toMediaType()))
    }

    override suspend fun createFollowUp(caseId: String, input: FollowUpInput): Response<Activity> =
        collection.createFollowUp(caseId, input)

    override suspend fun createEvidence(caseId: String, input: EvidenceInput, idempotencyKey: String): Response<EvidenceItem> =
        evidence.createEvidence(caseId, input, idempotencyKey = idempotencyKey)
}
