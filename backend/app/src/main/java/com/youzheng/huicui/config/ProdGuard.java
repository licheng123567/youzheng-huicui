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
 *   <li>huicui.auth.dev-sms-code 非空 → 拒绝启动（prod profile 不应有 dev 短信码）</li>
 * </ul>
 */
@Profile("prod")
@Configuration
public class ProdGuard {

    private static final String DEV_SECRET =
            "dev-only-secret-change-in-prod-至少32字节用于HS256签名0123456789";

    @Value("${huicui.jwt.secret:}")
    private String jwtSecret;

    @Value("${huicui.auth.dev-sms-code:}")
    private String devSmsCode;

    @PostConstruct
    public void validate() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "[ProdGuard] HUICUI_JWT_SECRET 未配置：生产环境必须通过环境变量注入 JWT 密钥，拒绝启动。");
        }
        if (DEV_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "[ProdGuard] HUICUI_JWT_SECRET 使用了 dev 内置串：生产环境禁止使用开发密钥，拒绝启动。");
        }
        if (devSmsCode != null && !devSmsCode.isBlank()) {
            throw new IllegalStateException(
                    "[ProdGuard] huicui.auth.dev-sms-code 在 prod profile 下不得配置，拒绝启动。");
        }
    }
}
