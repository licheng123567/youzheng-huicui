package com.youzheng.huicui.web.dto;

/**
 * 法律文书 DTO（对齐契约 components.schemas.LegalDoc）。M6 legal 组。
 * 字段与契约 1:1：id/caseId/type/status/pdfUrl/deliveredAt/signedPhotoUrl/evidenceId/createdBy/createdAt。
 * 列名映射见 EvidenceM6Controller：
 *   id←legal_doc.id, caseId←legal_doc.case_id, type←legal_doc.type(LegalDocTypeEnum),
 *   status←legal_doc.status(LegalDocStatusEnum: GENERATING/GENERATED/DELIVERED/SIGNED/ARCHIVED),
 *   pdfUrl←legal_doc.pdf_url(可空), deliveredAt←legal_doc.delivered_at(date-time,可空),
 *   signedPhotoUrl←legal_doc.signed_photo_url(可空), evidenceId←legal_doc.evidence_id(可空),
 *   createdBy←legal_doc.created_by, createdAt←legal_doc.created_at(date-time)。
 * 生成 PDF 异步：刚申请时 status=GENERATING，pdfUrl=null。
 */
public record LegalDocDto(
        String id,
        String caseId,
        String type,
        String status,
        String pdfUrl,
        String deliveredAt,
        String signedPhotoUrl,
        String evidenceId,
        String createdBy,
        String createdAt
) {}
