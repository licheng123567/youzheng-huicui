package com.youzheng.huicui.web.dto;

/**
 * 时间线活动 DTO（对齐契约 components.schemas.Activity）。
 * actor=展示名（应用层 LEFT JOIN account.name 填充；系统触发 actor_id 为 null 则 actor=null）。
 * 列名映射：caseId←case_id, actorId←actor_id, refType←ref_type, refId←ref_id, createdAt←created_at。
 */
public record CaseActivityDto(
        String id,
        String caseId,
        String type,
        String actor,
        String actorId,
        String content,
        String refType,
        String refId,
        String createdAt
) {}
