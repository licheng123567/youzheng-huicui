package com.youzheng.huicui.web.dto;

/**
 * 存证条目 DTO（对齐契约 components.schemas.EvidenceItem）。M6 evidence 组。
 * 基础字段：id/caseId/scene/status/certNo/certUrl/issuedAt。
 *   id←evidence.id, caseId←evidence.case_id, scene←evidence.scene(EvidenceSceneEnum),
 *   status←evidence.status(EvidenceStatusEnum: ISSUING/ISSUED/FAILED),
 *   certNo←evidence.cert_no(可空), certUrl←evidence.cert_url(可空),
 *   issuedAt←evidence.issued_at(date-time ISO，可空)。
 * 易保全对接补充字段（可选，additive；占位存证/未对接时为 null）：
 *   preservationId←保全备案号（多文件逗号分隔），chainTxHash←保全链交易 hash（ebqChainTransHash），
 *   gznetId←广州互联网法院证据 id，antId←杭州互联网法院证据 id。
 * 出证为异步：刚发起 status=ISSUING，备案类字段就绪前（约10min）均 null；轮询回填后置 ISSUED。
 */
public record EvidenceItemDto(
        String id,
        String caseId,
        String scene,
        String status,
        String certNo,
        String certUrl,
        String issuedAt,
        String preservationId,
        String chainTxHash,
        String gznetId,
        String antId
) {}
