package com.youzheng.huicui.web.dto;

/**
 * 督导动作 DTO（对齐契约 components.schemas.SupervisionAction）。
 * 列名映射：memberId←s.member_id, memberName←m.name, operatorId←s.operator_id,
 *   operatorName←o.name, createdAt←s.created_at（ISO-8601）。
 */
public record SupervisionActionDto(
        String id,
        String memberId,
        String memberName,
        String action,
        String note,
        String operatorId,
        String operatorName,
        String createdAt
) {}
