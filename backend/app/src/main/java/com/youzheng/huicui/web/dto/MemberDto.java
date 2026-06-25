package com.youzheng.huicui.web.dto;

/**
 * 成员 DTO（对齐契约 components.schemas.Member）。
 * 列名映射：orgId←org_id, role←role_template, isOwner←is_owner。
 * manageable 非 DB 列，Java 派生（BR-M1-04a）：
 *   平台主体 → row.org_id == subject.orgId ? true : false（跨组织只读）；
 *   非平台主体 → 一律 true（仅能看到本组织成员）。
 */
public record MemberDto(
        String id,
        String orgId,
        String username,
        String name,
        String phone,
        String role,
        String status,
        boolean isOwner,
        boolean manageable
) {}
