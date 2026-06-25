package com.youzheng.huicui.web.dto;

/**
 * 联系方式 DTO（对齐契约 components.schemas.Contact）。
 * 列名映射：caseId←case_id, isPrimary←is_primary。
 * redacted=true 时（BR-M8-09）phone 脱敏占位。
 */
public record CaseContactDto(
        String id,
        String caseId,
        String phone,
        String label,
        boolean isPrimary,
        boolean invalid
) {}
