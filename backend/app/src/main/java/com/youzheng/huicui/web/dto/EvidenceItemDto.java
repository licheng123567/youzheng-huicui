package com.youzheng.huicui.web.dto;

/**
 * 存证条目 DTO（对齐契约 components.schemas.EvidenceItem）。M6 evidence 组。
 * 字段与契约 1:1：id/caseId/scene/status/certNo/certUrl/issuedAt。
 * 列名映射见 EvidenceM6Controller：
 *   id←evidence.id, caseId←evidence.case_id, scene←evidence.scene(EvidenceSceneEnum),
 *   status←evidence.status(EvidenceStatusEnum: ISSUING/ISSUED/FAILED),
 *   certNo←evidence.cert_no(可空), certUrl←evidence.cert_url(可空),
 *   issuedAt←evidence.issued_at(date-time ISO，可空)。
 * 出证为异步（上链）：刚发起时 status=ISSUING，certNo/certUrl/issuedAt 均 null。
 */
public record EvidenceItemDto(
        String id,
        String caseId,
        String scene,
        String status,
        String certNo,
        String certUrl,
        String issuedAt
) {}
