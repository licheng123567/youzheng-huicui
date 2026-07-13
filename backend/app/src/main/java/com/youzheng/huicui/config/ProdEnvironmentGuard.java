package com.youzheng.huicui.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * prod profile 启动硬护栏：**在任何 bean 创建之前**校验生产必填配置，配错就抛错终止启动。
 *
 * <p>为什么必须是 {@link EnvironmentPostProcessor} 而不是 {@code @PostConstruct}：
 * 实测发现，若把校验放在 {@code @Profile("prod")} 的 bean 的 {@code @PostConstruct} 里，
 * {@code JwtService} 的构造器会先跑，空密钥直接抛
 * {@code WeakKeyException: The specified key byte array is 0 bits...}。
 * 应用确实起不来（fail-closed 成立），但运维看到的是一句 JJWT 天书，
 * 而不是「HUICUI_JWT_SECRET 未配置」。护栏的价值在于**可操作的错误信息**，故必须抢在 bean 之前。
 *
 * <p>硬失败清单见各 check 方法。软告警（短信/存证未启用）留在 {@link ProdGuard}——
 * 那时日志系统已就绪，WARN 才打得出来。
 *
 * <p>注册见 {@code META-INF/spring.factories}。
 * Order 取 LOWEST_PRECEDENCE，确保跑在 ConfigDataEnvironmentPostProcessor 之后
 * （否则读不到 application-prod.yml 与已激活的 profile）。
 */
public class ProdEnvironmentGuard implements EnvironmentPostProcessor, Ordered {

    static final String DEV_SECRET =
            "dev-only-secret-change-in-prod-至少32字节用于HS256签名0123456789";
    /** application-dev.yml 里的内置主密钥；生产用它 = 密文等同明文。 */
    static final String DEV_CRYPTO_KEY = "dev-only-crypto-master-key-change-in-prod";
    /** HS256 要求密钥 ≥256 bit。 */
    static final int MIN_SECRET_BYTES = 32;

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;   // 必须晚于配置文件加载，才读得到 yml 与激活的 profile
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!Arrays.asList(env.getActiveProfiles()).contains("prod")) return;

        String dsUrl = env.getProperty("spring.datasource.url", "");
        String dsPwd = env.getProperty("spring.datasource.password", "");
        String jwt = env.getProperty("huicui.jwt.secret", "");

        // ── 数据源 ──
        if (isBlank(dsUrl)) {
            fail("SPRING_DATASOURCE_URL 未配置：生产必须注入数据源地址。");
        }
        if (dsUrl.contains("localhost:5455")) {
            fail("数据源仍指向 dev 默认库 localhost:5455：生产必须注入 SPRING_DATASOURCE_URL。");
        }
        if ("test".equals(dsPwd)) {
            fail("数据源使用 dev 默认口令：生产必须注入 SPRING_DATASOURCE_PASSWORD。");
        }

        // ── JWT 密钥（必须抢在 JwtService 构造器之前，否则只会看到 JJWT 的 WeakKeyException）──
        if (isBlank(jwt)) {
            fail("HUICUI_JWT_SECRET 未配置：生产必须通过环境变量注入 JWT 密钥（openssl rand -base64 48）。");
        }
        if (DEV_SECRET.equals(jwt)) {
            fail("HUICUI_JWT_SECRET 使用了 dev 内置串：生产禁止使用开发密钥。");
        }
        if (jwt.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            fail("HUICUI_JWT_SECRET 强度不足（HS256 要求 ≥" + MIN_SECRET_BYTES + " 字节，当前 "
                    + jwt.getBytes(StandardCharsets.UTF_8).length + " 字节）。");
        }

        // ── 加密主密钥：三方通道密钥（易保全/短信/百炼 ASR/DeepSeek）AES-256-GCM 落库要用它 ──
        //
        // 此前这一项**完全没进部署产物**（compose 不透传、.env.example 里也没有），
        // 而护栏又不校验它 —— 于是生产起得来，运维在后台点「保存 ASR 密钥」却恒 409
        // （CryptoService 写侧直接拒绝），并且会以为是 bug。ASR/LLM 的 key 只有落库这一条路
        // （不像易保全/短信还有 yml 兜底），所以没有主密钥＝真 AI 在生产上永远不可用。
        // 改成硬失败：宁可起不来，也不要起一个「AI 永远配不上」的实例。
        String cryptoKey = env.getProperty("huicui.crypto.master-key", "");
        if (isBlank(cryptoKey)) {
            fail("HUICUI_CRYPTO_KEY 未配置：三方通道密钥（存证/短信/ASR/LLM）无法加密落库，"
                    + "后台保存密钥会恒 409。生成：openssl rand -base64 32");
        }
        if (DEV_CRYPTO_KEY.equals(cryptoKey)) {
            // 措辞刻意与 JWT 的「dev 内置串」区分开：deploy-shape 的两条断言靠这句话分辨是哪一把钥匙配错了
            fail("HUICUI_CRYPTO_KEY 使用了 dev 内置主密钥：生产禁止使用开发主密钥（否则密文等同明文）。");
        }

        // ── 短信通道：启用则必须能真发出去 ──
        if (env.getProperty("huicui.sms.enabled", Boolean.class, false)) {
            String publicBase = env.getProperty("huicui.sms.public-base-url", "");
            if (isBlank(publicBase) || publicBase.contains("localhost") || publicBase.contains("127.0.0.1")) {
                fail("启用短信但 HUICUI_PUBLIC_BASE 仍是 localhost：业主会收到打不开的缴费链接。");
            }
            if (isBlank(env.getProperty("huicui.sms.secret-name"))
                    || isBlank(env.getProperty("huicui.sms.secret-key"))
                    || isBlank(env.getProperty("huicui.sms.sms-base-url"))) {
                fail("启用短信但缺 HUICUI_SMS_SECRET_NAME / HUICUI_SMS_SECRET_KEY / HUICUI_SMS_BASE。");
            }
        }

        // ── 存证通道：启用则必须是正式环境，沙箱出的证书没有法律效力 ──
        if (env.getProperty("huicui.ebaoquan.enabled", Boolean.class, false)) {
            if (isBlank(env.getProperty("huicui.ebaoquan.app-key"))
                    || isBlank(env.getProperty("huicui.ebaoquan.app-key-secret"))) {
                fail("启用存证但缺 HUICUI_EBQ_APPKEY / HUICUI_EBQ_SECRET。");
            }
            String ebqUrl = env.getProperty("huicui.ebaoquan.base-url", "");
            if (ebqUrl.contains("sandbox")) {
                fail("启用存证但 HUICUI_EBQ_URL 仍指向 sandbox：沙箱出的证书没有法律效力。");
            }
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static void fail(String msg) {
        String banner = "\n"
                + "==================== 生产启动自检失败 ====================\n"
                + "[ProdGuard] " + msg + "\n"
                + "配置样板见 deploy/.env.example；部署步骤见 deploy/README.md\n"
                + "（宁可起不来，也不要用 dev 口径跑生产）\n"
                + "=========================================================";
        System.err.println(banner);
        throw new IllegalStateException("[ProdGuard] " + msg);
    }
}
