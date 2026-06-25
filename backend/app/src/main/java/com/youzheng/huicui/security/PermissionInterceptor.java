package com.youzheng.huicui.security;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** x-permission 鉴权：@RequirePermission 标注的端点，主体无该权限点 → 403。 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;
        RequirePermission rp = hm.getMethodAnnotation(RequirePermission.class);
        if (rp == null) rp = hm.getBeanType().getAnnotation(RequirePermission.class);
        if (rp == null) return true;
        CurrentSubject s = SubjectContext.get();   // 无主体 → 抛 401（理论上已被 JwtAuthFilter 拦）
        if (!s.has(rp.value())) {
            throw new ApiException(BizError.PERM_403, "缺少权限点: " + rp.value());
        }
        return true;
    }
}
