package com.youzheng.huicui.web.dto;

/**
 * 存证核验 DTO（对齐契约 components.schemas.EvidenceVerify）。M6 公开核验端点。
 * 字段与契约 1:1：valid/certNo/scene/issuedAt/hash。
 * public 不泄越权数据：仅返这五个字段，不含 org/owner/case 明细。
 *   valid  = (status='ISSUED' && cert_no IS NOT NULL)；
 *   certNo = evidence.cert_no；scene = evidence.scene；issuedAt = evidence.issued_at(date-time)；
 *   hash   = SHA-256(id|case_id|scene|cert_no|issued_at) hex（派生，DDL 无 hash 列）。
 */
public record EvidenceVerifyDto(
        boolean valid,
        String certNo,
        String scene,
        String issuedAt,
        String hash
) {}
