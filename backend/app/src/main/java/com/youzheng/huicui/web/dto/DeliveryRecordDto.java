package com.youzheng.huicui.web.dto;

/**
 * 送达记录 DTO（对齐契约 components.schemas.DeliveryRecord）。协调员「送达管理」列表。
 * 一条 = 一份送达凭证附件（case_attachment.delivery_type 非空）。
 *   id←case_attachment.id, caseId←case_id, ownerName←case.owner_name, room←case.room, projectName←project.name, batchNo←batch.no,
 *   deliveredAt←case_attachment.created_at(上传/录入时间, ISO date-time),
 *   deliveryType←case_attachment.delivery_type(DeliveryTypeEnum: LAWYER_LETTER/COLLECTION_NOTICE/COURT_DOC/OTHER),
 *   channel←session_token 派生(DeliveryChannelEnum: APP=扫码/手机上传, BACKEND=PC后台直传),
 *   filename←case_attachment.filename,
 *   evidenced←是否已发起 DELIVERY 存证(ref_ids 含本附件),
 *   evidenceId←关联存证 id(可空, 供下载证书), evidenceStatus←存证态(可空: ISSUING/ISSUED/FAILED)。
 */
public record DeliveryRecordDto(
        String id,
        String caseId,
        String ownerName,
        String room,
        String projectName,
        String batchNo,
        String deliveredAt,
        String deliveryType,
        String channel,
        String filename,
        boolean evidenced,
        String evidenceId,
        String evidenceStatus
) {}
