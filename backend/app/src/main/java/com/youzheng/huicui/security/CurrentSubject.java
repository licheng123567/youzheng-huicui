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
        Set<String> permissions,
        DataRange dataRange  // SE 三维数据范围（BR-M1-14）；非 SE 为 UNRESTRICTED
) {
    public boolean isPlatform() { return "PLATFORM".equals(orgType); }
    public boolean has(String permission) { return permissions.contains(permission); }

    /** 平台员工（PLATFORM/SE）：带三维数据范围裁剪；区别于超管 SA（全量不限）。 */
    public boolean isSE() { return isPlatform() && "SE".equals(role); }

    /** 物业协调员（PROPERTY/PC）：行级隔离，仅见协调的项目/批次案件。 */
    public boolean isPC() { return "PROPERTY".equals(orgType) && "PC".equals(role); }

    /** 数据范围（永不为 null；缺省 UNRESTRICTED）。 */
    public DataRange dataRange() { return dataRange == null ? DataRange.UNRESTRICTED : dataRange; }
}
