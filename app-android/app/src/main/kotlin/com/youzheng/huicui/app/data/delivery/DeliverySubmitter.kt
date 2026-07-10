package com.youzheng.huicui.app.data.delivery

import com.youzheng.huicui.app.api.models.EvidenceInput
import com.youzheng.huicui.app.api.models.EvidenceSceneEnum
import com.youzheng.huicui.app.api.models.FollowUpInput
import com.youzheng.huicui.app.api.models.FollowUpInputAttachmentsInner
import java.io.File
import java.io.IOException

/** 送达类型白名单（AttachmentController.DELIVERY_TYPES 的镜像）。白名单外的值后端落 NULL=普通附件，不进送达管理。 */
enum class DeliveryType(val wire: String, val label: String) {
    LAWYER_LETTER("LAWYER_LETTER", "律师函"),
    COLLECTION_NOTICE("COLLECTION_NOTICE", "催收单"),
    COURT_DOC("COURT_DOC", "诉讼文书"),
    OTHER("OTHER", "其他"),
}

/**
 * 上门送达拍照存证的三步编排，与网页端 `CaseThreeColumn.submitUpload` **同构**：
 *
 *   1. 逐张上传照片 → `POST /cases/{id}/attachments`（带 deliveryType，进「送达管理」）
 *   2. 记跟进       → `POST /cases/{id}/follow-ups`（method=VISIT——这是上门场景，时间线留痕）
 *   3. 可选存证     → `POST /cases/{id}/evidence`（scene=DELIVERY, refIds=第 1 步的附件 id）
 *
 * 失败语义刻意做成**阶梯式**，因为三步的代价不同：
 *   · 第 1 步失败 → 整体失败，什么都没发生，可整体重试；
 *   · 第 2 步失败 → 照片已在服务端（附件不会丢），报错并允许重试跟进；
 *   · 第 3 步失败 → 照片和时间线都成了，只有存证没成 —— 这**不是失败**，网页端也能补发起，
 *     所以单独一个 [Result.EvidenceFailed]，UI 提示「已上传并记跟进，存证稍后可在网页端补」。
 */
class DeliverySubmitter(private val port: DeliveryApiPort) {

    sealed interface Result {
        /** 全部成功。[evidenced] 区分「传完了」和「传完并存证了」。 */
        data class Success(val attachmentIds: List<String>, val evidenced: Boolean) : Result

        /** 照片、跟进都成了，仅存证一步失败。不算整体失败。 */
        data class EvidenceFailed(val attachmentIds: List<String>, val message: String) : Result

        /** 上传或记跟进阶段失败。[uploadedIds] 非空表示部分照片已在服务端。 */
        data class Failed(val stage: Stage, val message: String, val uploadedIds: List<String>) : Result
    }

    enum class Stage { UPLOAD, FOLLOW_UP }

    suspend fun submit(
        caseId: String,
        photos: List<File>,
        deliveryType: DeliveryType,
        note: String?,
        withEvidence: Boolean,
    ): Result {
        require(photos.isNotEmpty()) { "至少一张照片" }

        // 1. 逐张上传。一张失败就停：半批照片记成一条跟进会让时间线和送达管理对不上数。
        val uploaded = mutableListOf<Pair<String, String>>()   // id to name
        for (f in photos) {
            val r = try {
                port.uploadAttachment(caseId, f, deliveryType.wire)
            } catch (e: IOException) {
                return Result.Failed(Stage.UPLOAD, e.message ?: "网络错误", uploaded.map { it.first })
            }
            val body = r.body()
            if (!r.isSuccessful || body?.id == null) {
                return Result.Failed(Stage.UPLOAD, "上传失败 HTTP ${r.code()}", uploaded.map { it.first })
            }
            uploaded += body.id to (body.name)
        }
        val ids = uploaded.map { it.first }

        // 2. 记跟进（时间线留痕）。上门场景 method=VISIT，不是网页端桌面上传的 OTHER。
        val names = uploaded.joinToString("、") { it.second }
        val followUp = FollowUpInput(
            content = note?.takeIf { it.isNotBlank() } ?: "上门送达（${deliveryType.label}）：$names",
            method = FollowUpInput.Method.VISIT,
            attachments = uploaded.map { (id, name) ->
                FollowUpInputAttachmentsInner(name = name, url = "/v1/attachments/$id")
            },
        )
        val fr = try {
            port.createFollowUp(caseId, followUp)
        } catch (e: IOException) {
            return Result.Failed(Stage.FOLLOW_UP, e.message ?: "网络错误", ids)
        }
        if (!fr.isSuccessful) {
            return Result.Failed(Stage.FOLLOW_UP, "记跟进失败 HTTP ${fr.code()}", ids)
        }

        // 3. 可选存证。幂等键从附件 id 集合确定性推导：同一批照片重试不会重复扣存证费。
        if (!withEvidence) return Result.Success(ids, evidenced = false)
        val er = try {
            port.createEvidence(
                caseId,
                EvidenceInput(scene = EvidenceSceneEnum.DELIVERY, refIds = ids, note = note?.takeIf { it.isNotBlank() }),
                idempotencyKey = "delivery-$caseId-" + ids.sorted().joinToString("-"),
            )
        } catch (e: IOException) {
            return Result.EvidenceFailed(ids, e.message ?: "网络错误")
        }
        return if (er.isSuccessful) {
            Result.Success(ids, evidenced = true)
        } else {
            Result.EvidenceFailed(ids, "存证失败 HTTP ${er.code()}")
        }
    }
}
