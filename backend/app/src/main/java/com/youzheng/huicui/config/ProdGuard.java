package com.youzheng.huicui.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * prod profile 启动护栏：校验生产必填凭据，缺失则 IllegalStateException 阻止启动。
 * 宁可启动失败，不可退化到 dev 弱口径运行。
 *
 * <ul>
 *   <li>HUICUI_JWT_SECRET 未注入（空串）→ 拒绝启动</li>
 *   <li>HUICUI_JWT_SECRET 与 dev 内置串相同 → 拒绝启动（防意外泄漏 dev 串到生产）</li>
 *   <li>数据源仍是 dev 默认（localhost:5455 / 口令 test）→ 拒绝启动</li>
 * </ul>
 * 短信固定码 000000 的防线在 AuthController.smsCode：非 dev profile 且 sms 未启用 → 502 拒绝下发，
 * 不依赖本护栏（此前校验的 huicui.auth.dev-sms-code 属性早已无代码读取，属失效护栏，已移除）。
 */
@Profile("prod")
@Configuration
public class ProdGuard {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProdGuard.class);

    private static final String DEV_SECRET =
            "dev-only-secret-change-in-prod-至少32字节用于HS256签名0123456789";

    @Value("${huicui.jwt.secret:}")
    private String jwtSecret;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Value("${spring.datasource.password:}")
    private String datasourcePassword;

    @Value("${huicui.sms.dry-run:false}")
    private boolean smsDryRun;

    @Value("${huicui.sms.enabled:false}")
    private boolean smsEnabled;

    @PostConstruct
    public void validate() {
        // dry-run 是预发演练开关：不触网关、且把验证码写进日志。生产长期开着 = 短信永远发不出去。
        // 不硬失败（允许预发用 prod profile 演练），但必须刺眼。
        if (smsEnabled && smsDryRun) {
            log.warn("======================================================================");
            log.warn("[ProdGuard] 短信处于 DRY-RUN：不会真的发出任何短信，且验证码会被写入日志！");
            log.warn("[ProdGuard] 若这是正式生产环境，请立刻设置 HUICUI_SMS_DRY_RUN=false 并重启。");
            log.warn("======================================================================");
        }
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "[ProdGuard] HUICUI_JWT_SECRET 未配置：生产环境必须通过环境变量注入 JWT 密钥，拒绝启动。");
        }
        if (DEV_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "[ProdGuard] HUICUI_JWT_SECRET 使用了 dev 内置串：生产环境禁止使用开发密钥，拒绝启动。");
        }
        if (datasourceUrl != null && datasourceUrl.contains("localhost:5455")) {
            throw new IllegalStateException(
                    "[ProdGuard] 数据源仍指向 dev 默认库 localhost:5455：生产必须注入 SPRING_DATASOURCE_URL，拒绝启动。");
        }
        if ("test".equals(datasourcePassword)) {
            throw new IllegalStateException(
                    "[ProdGuard] 数据源使用 dev 默认口令：生产必须注入 SPRING_DATASOURCE_PASSWORD，拒绝启动。");
        }
    }
}
