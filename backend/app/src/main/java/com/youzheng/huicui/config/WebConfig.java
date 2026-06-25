package com.youzheng.huicui.config;

import com.youzheng.huicui.idempotency.IdempotencyInterceptor;
import com.youzheng.huicui.security.PermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册横切层拦截器（幂等 → 权限）。鉴权/traceId 由 Filter 完成（更早）。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final IdempotencyInterceptor idempotency;
    private final PermissionInterceptor permission;

    public WebConfig(IdempotencyInterceptor idempotency, PermissionInterceptor permission) {
        this.idempotency = idempotency;
        this.permission = permission;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotency);
        registry.addInterceptor(permission);
    }
}
