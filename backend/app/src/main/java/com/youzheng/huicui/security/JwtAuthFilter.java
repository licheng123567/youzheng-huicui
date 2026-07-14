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
                || path.matches("/evidence/[^/]+/verify")
                // 扫码上传：手机凭会话 token 公开上传附件（token 未过期即授权，无需登录）。
                || path.matches("/upload-sessions/[^/]+/file")
                // 录音音频拉取（v1.24.0）：百炼「录音文件识别」是异步任务——阿里侧主动来拉我们给的 URL，
                // 它不可能带我们的 JWT。token 32 字节随机 + TTL 30min + 转写一结束就删，除音频字节外不暴露任何信息。
                || path.matches("/pub/recordings/[^/]+")
                // 运维探针：容器/编排器的存活与就绪检查必须免鉴权，否则 HEALTHCHECK 恒 401
                // → 容器永远 unhealthy、depends_on:service_healthy 卡死。
                // 只放行 health 与 info（prod 的 management.endpoints 也只暴露这两个，
                // 且 health 的 show-details:never，不泄露数据源等内部信息）；env/beans 一律不放行。
                //
                // info 为什么也公开：回退演练与事故排查时，第一个问题永远是「现在跑的到底是哪个构建」。
                // 那一刻运维手上未必有 docker 权限，但一定能打 HTTP（见 deploy/ROLLBACK.md 第 0 节）。
                // 代价是版本号与 commit sha 对外可见——本仓库 public、镜像也在公开的 GHCR 上，
                // 这两个值本就不是秘密。info 里只有 app.version / app.revision（application-prod.yml）。
                || path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/actuator/info");
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

            // 账号状态每请求复核（DB 是唯一权威来源）。
            //
            // 此前这里**只查 must_change_password，从不查 account.status** —— 而 JWT 默认 24h 有效。
            // 于是：停权 / 离职的员工，在最长 24 小时内**照样能继续拉业主的姓名、电话、住址、
            // 通话录音**。停权只在「下次登录」时才生效，可他根本不需要再登录一次。
            // 对催收系统来说这是实打实的数据泄露口子，不是"待优化"。
            //
            // 现在：状态与 must_change 一次查完（本来就有这一次查询，不增加开销），
            // 非 ACTIVE 立刻 401 —— 客户端拿到 401 会清 token 并回登录页，等于**当场踢下线**。
            //
            // 注意只卡 account.status，**不卡 org.status**：组织停用的口径是「停新单、不断存量」
            // （BR/v1.22.0）—— 成员照常登录、在催案件照常作业。把组织停用也做成踢人，会把
            // 一次商务纠纷升级成"整个物业的人立刻无法作业"，与产品口径相悖。
            AccountState st = loadAccountState(subject.accountId());
            if (st == null || !"ACTIVE".equals(st.status())) {
                write401(res, "账号已停用或不存在，请联系管理员");
                return;
            }
            if (st.mustChangePassword() && !isMustChangeAllowed(req.getMethod(), path)) {
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

    /** 账号在 DB 里的权威状态。 */
    record AccountState(String status, boolean mustChangePassword) {}

    /**
     * 一次查完账号状态与 must_change_password。
     *
     * <p><b>DB 异常时返回 null（= 拒绝）而不是放行</b>。老实现在 catch 里 {@code return false}
     * （降级放行），那对 must_change 尚可，但对「账号是否已被停权」绝不能这么办 ——
     * 一次数据库抖动就等于给所有已停权的令牌开了后门。鉴权失败必须 fail-closed。
     */
    private AccountState loadAccountState(String accountId) {
        if (accountId == null || accountId.isBlank()) return null;
        try {
            long id = Long.parseLong(accountId);
            return jdbc.query(
                    "SELECT status, must_change_password FROM account WHERE id = ?",
                    rs -> rs.next()
                            ? new AccountState(rs.getString("status"), rs.getBoolean("must_change_password"))
                            : null,
                    id);
        } catch (Exception e) {
            return null;   // fail-closed：查不到/查不动 → 当作不可用，拒绝请求
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
