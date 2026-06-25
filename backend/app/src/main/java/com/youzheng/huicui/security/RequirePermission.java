package com.youzheng.huicui.security;

import java.lang.annotation.*;

/** 标注端点所需权限点（对齐契约 x-permission）。PermissionInterceptor 校验 → 403。 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String value();
}
