package com.youzheng.huicui.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 鉴权过滤器：公共路径放行；受保护路径解析 Bearer JWT → 写入 SubjectContext；
 * 无/坏令牌 → 直接返回契约 Error 信封 401（统一，不依赖各 Controller）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // 在 TraceIdFilter 之后
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final ObjectMapper om = new ObjectMapper();

    public JwtAuthFilter(JwtService jwt) { this.jwt = jwt; }

    private boolean isPublic(String path) {
        // 精确放行 public 端点：登录、业主账单、存证验真。
        // 不用 endsWith("/verify") 兜底整个命名空间——否则未来任何 /verify 结尾端点都被静默免鉴权(审计 H-1)。
        return path.startsWith("/auth/")
                || path.startsWith("/pay/")
                || path.matches("/evidence/[^/]+/verify");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getServletPath();   // 已去掉 context-path /v1
        if (isPublic(path)) { chain.doFilter(req, res); return; }

        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) { write401(res, "缺少 Bearer 令牌"); return; }
        try {
            SubjectContext.set(jwt.parse(auth.substring(7)));
            chain.doFilter(req, res);
        } catch (Exception e) {
            write401(res, "令牌无效或已过期");
        } finally {
            SubjectContext.clear();
        }
    }

    private void write401(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        om.writeValue(res.getWriter(), Map.of(
                "code", "AUTH_401", "message", msg,
                "traceId", String.valueOf(MDC.get("traceId"))));
    }
}
