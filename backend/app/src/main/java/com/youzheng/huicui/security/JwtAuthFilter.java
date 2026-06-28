package com.youzheng.huicui.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 鉴权过滤器：公共路径放行；受保护路径解析 Bearer JWT → 写入 SubjectContext；
 * 无/坏令牌 → 直接返回契约 Error 信封 401（统一，不依赖各 Controller）。
 *
 * M-a（must-change 后端强制）：JWT 解析成功后，若 account.must_change_password=TRUE，
 * 仅放行 POST /me/password（改密）与 GET /me（读取自身信息），其余一律 403。
 * 防止前端绕过改密拦截直接调 API。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // 在 TraceIdFilter 之后
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    public JwtAuthFilter(JwtService jwt, JdbcTemplate jdbc) {
        this.jwt = jwt;
        this.jdbc = jdbc;
    }

    private boolean isPublic(String path) {
        // 精确放行 public 端点：登录、业主账单、存证验真。
        // 不用 endsWith("/verify") 兜底整个命名空间——否则未来任何 /verify 结尾端点都被静默免鉴权(审计 H-1)。
        return path.startsWith("/auth/")
                || path.startsWith("/pay/")
                || path.matches("/evidence/[^/]+/verify");
    }

    /**
     * M-a：must_change_password=TRUE 时仅允许的端点白名单（改密 + 读自身）。
     * POST /me/password — 改密（ProfileSearchController）。
     * GET  /me          — 读取自身信息（MeController）。
     */
    private boolean isMustChangeAllowed(String method, String path) {
        return ("POST".equalsIgnoreCase(method) && "/me/password".equals(path))
                || ("GET".equalsIgnoreCase(method) && "/me".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getServletPath();   // 已去掉 context-path /v1
        if (isPublic(path)) { chain.doFilter(req, res); return; }

        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) { write401(res, "缺少 Bearer 令牌"); return; }
        try {
            CurrentSubject subject = jwt.parse(auth.substring(7));
            SubjectContext.set(subject);

            // M-a：must_change_password 后端强制拦截——仅前端告知不足，须后端也卡住。
            // 从 DB 读标志（JWT 不含此字段；DB 是唯一权威来源）。
            if (isMustChangeRequired(subject.accountId())
                    && !isMustChangeAllowed(req.getMethod(), path)) {
                write403(res, "首次登录须先修改密码，请调用 POST /me/password");
                return;
            }

            chain.doFilter(req, res);
        } catch (Exception e) {
            write401(res, "令牌无效或已过期");
        } finally {
            SubjectContext.clear();
        }
    }

    /**
     * 查 account.must_change_password。列不存在（迁移未跑）时降级 false，避免 5xx。
     */
    private boolean isMustChangeRequired(String accountId) {
        if (accountId == null || accountId.isBlank()) return false;
        try {
            long id = Long.parseLong(accountId);
            Boolean v = jdbc.query(
                    "SELECT must_change_password FROM account WHERE id = ?",
                    rs -> rs.next() ? rs.getBoolean("must_change_password") : null,
                    id);
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            // 列不存在或 DB 异常：降级放行，不因此拦截合法请求
            return false;
        }
    }

    private void write401(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        om.writeValue(res.getWriter(), Map.of(
                "code", "AUTH_401", "message", msg,
                "traceId", String.valueOf(MDC.get("traceId"))));
    }

    private void write403(HttpServletResponse res, String msg) throws IOException {
        res.setStatus(403);
        res.setContentType("application/json;charset=UTF-8");
        om.writeValue(res.getWriter(), Map.of(
                "code", "MUST_CHANGE_PASSWORD", "message", msg,
                "traceId", String.valueOf(MDC.get("traceId"))));
    }
}
