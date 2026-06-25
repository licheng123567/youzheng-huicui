package com.youzheng.huicui.security;

import java.util.Set;

/**
 * 当前主体（从 JWT 解析）——横切层的核心上下文。
 * scope/permissions/org 驱动 x-data-scope 过滤与 x-permission 鉴权。
 */
public record CurrentSubject(
        String accountId,
        String name,
        String orgId,
        String orgType,      // OrgTypeEnum: PLATFORM/PROPERTY/PROVIDER
        String orgName,
        String role,         // RoleTemplateEnum: SA/SE/PL/PC/VL/CO
        Set<String> permissions
) {
    public boolean isPlatform() { return "PLATFORM".equals(orgType); }
    public boolean has(String permission) { return permissions.contains(permission); }
}
