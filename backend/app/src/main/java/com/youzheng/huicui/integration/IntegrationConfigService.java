package com.youzheng.huicui.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.CryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 三方通道配置解析（V936·v1.23.0）。EbaoquanClient / ZhixunyunSmsClient 读配置的**唯一入口**。
 *
 * 【解析优先级】integration_config(DB·后台可维护) → application.yml(环境变量) → 默认值。
 *   DB 无行或字段留空 → 自动回落 yml。于是**已用环境变量部署好的实例升级后行为完全不变**，
 *   不必先去后台补一遍配置。
 *
 * 【不缓存（有意）】与 SmsConfigService 同一权衡：一次 PK 命中的单行 SELECT（~0.1ms）相对一次网关
 *   HTTP（数百 ms）是噪声；而缓存会引入「后台改了 key，几分钟后才生效」的坑——那恰恰是本功能的验收点。
 *
 * 【明文不出接口】decryptSecret 只给客户端内部用；对外读接口一律走 maskedSecrets()。
 */
@Service
public class IntegrationConfigService {

    public static final String EBAOQUAN = "EBAOQUAN";
    public static final String SMS = "SMS";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CryptoService crypto;

    // yml 兜底（环境变量部署路径；DB 未配置时仍按老口径工作）
    private final boolean ymlEbqEnabled;
    private final String ymlEbqBaseUrl;
    private final String ymlEbqAppKey;
    private final String ymlEbqAppKeySecret;
    private final boolean ymlSmsEnabled;
    private final boolean ymlSmsDryRun;
    private final String ymlSmsSecretName;
    private final String ymlSmsSecretKey;
    private final String ymlSmsBaseUrl;
    private final String ymlSmsVideoBaseUrl;

    public IntegrationConfigService(
            JdbcTemplate jdbc, ObjectMapper json, CryptoService crypto,
            @Value("${huicui.ebaoquan.enabled:false}") boolean ymlEbqEnabled,
            @Value("${huicui.ebaoquan.base-url:https://bs.sandbox.ebaoquan.org}") String ymlEbqBaseUrl,
            @Value("${huicui.ebaoquan.app-key:}") String ymlEbqAppKey,
            @Value("${huicui.ebaoquan.app-key-secret:}") String ymlEbqAppKeySecret,
            @Value("${huicui.sms.enabled:false}") boolean ymlSmsEnabled,
            @Value("${huicui.sms.dry-run:false}") boolean ymlSmsDryRun,
            @Value("${huicui.sms.secret-name:}") String ymlSmsSecretName,
            @Value("${huicui.sms.secret-key:}") String ymlSmsSecretKey,
            @Value("${huicui.sms.sms-base-url:}") String ymlSmsBaseUrl,
            @Value("${huicui.sms.video-base-url:http://api.028lk.com}") String ymlSmsVideoBaseUrl) {
        this.jdbc = jdbc;
        this.json = json;
        this.crypto = crypto;
        this.ymlEbqEnabled = ymlEbqEnabled;
        this.ymlEbqBaseUrl = ymlEbqBaseUrl;
        this.ymlEbqAppKey = ymlEbqAppKey;
        this.ymlEbqAppKeySecret = ymlEbqAppKeySecret;
        this.ymlSmsEnabled = ymlSmsEnabled;
        this.ymlSmsDryRun = ymlSmsDryRun;
        this.ymlSmsSecretName = ymlSmsSecretName;
        this.ymlSmsSecretKey = ymlSmsSecretKey;
        this.ymlSmsBaseUrl = ymlSmsBaseUrl;
        this.ymlSmsVideoBaseUrl = ymlSmsVideoBaseUrl;
        this.fixedProvider = null;
        this.fixedEnabled = null;
        this.fixedSettings = java.util.Map.of();
        this.fixedSecrets = java.util.Map.of();
        this.fixedDryRun = false;
    }

    /**
     * 测试用：不连库、不读 yml 的固定配置。产线一律走 Spring 注入的那个实例（DB → yml → 默认）。
     * 放在这里而不是测试目录，是为了让「配置从哪来」这件事在产线代码里就显式可见。
     */
    static IntegrationConfigService fixed(String provider, boolean enabled,
                                          java.util.Map<String, String> settings,
                                          java.util.Map<String, String> secrets) {
        return fixed(provider, enabled, settings, secrets, false);
    }

    static IntegrationConfigService fixed(String provider, boolean enabled,
                                          java.util.Map<String, String> settings,
                                          java.util.Map<String, String> secrets, boolean dryRun) {
        return new IntegrationConfigService(provider, enabled, settings, secrets, dryRun);
    }

    private final String fixedProvider;
    private final Boolean fixedEnabled;
    private final java.util.Map<String, String> fixedSettings;
    private final java.util.Map<String, String> fixedSecrets;
    private final boolean fixedDryRun;

    private IntegrationConfigService(String provider, boolean enabled,
                                     java.util.Map<String, String> settings,
                                     java.util.Map<String, String> secrets, boolean dryRun) {
        this.jdbc = null;
        this.json = null;
        this.crypto = null;
        this.ymlEbqEnabled = false;
        this.ymlEbqBaseUrl = "";
        this.ymlEbqAppKey = "";
        this.ymlEbqAppKeySecret = "";
        this.ymlSmsEnabled = false;
        this.ymlSmsDryRun = false;
        this.ymlSmsSecretName = "";
        this.ymlSmsSecretKey = "";
        this.ymlSmsBaseUrl = "";
        this.ymlSmsVideoBaseUrl = "";
        this.fixedProvider = provider;
        this.fixedEnabled = enabled;
        this.fixedSettings = settings;
        this.fixedSecrets = secrets;
        this.fixedDryRun = dryRun;
    }

    private boolean isFixed() {
        return fixedProvider != null;
    }

    /** DB 行（无行 → null）。 */
    private JsonNode row(String provider) {
        String raw = jdbc.query(
                "SELECT jsonb_build_object('enabled', enabled, 'settings', settings, 'secrets', secrets)::text AS j"
                        + " FROM integration_config WHERE provider = ?",
                rs -> rs.next() ? rs.getString("j") : null, provider);
        if (raw == null) return null;
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return null;                              // 脏数据不致 5xx，回落 yml
        }
    }

    /** 该通道是否启用：DB 有行以 DB 为准；无行回落 yml。 */
    public boolean enabled(String provider) {
        if (isFixed()) return Boolean.TRUE.equals(fixedEnabled);
        JsonNode r = row(provider);
        if (r != null && r.hasNonNull("enabled")) return r.get("enabled").asBoolean();
        return EBAOQUAN.equals(provider) ? ymlEbqEnabled : ymlSmsEnabled;
    }

    /** 非密字段：DB.settings.<key> → yml → 默认。 */
    public String setting(String provider, String key) {
        if (isFixed()) return fixedSettings.get(key);
        JsonNode r = row(provider);
        String v = (r != null && r.get("settings") != null && r.get("settings").hasNonNull(key))
                ? r.get("settings").get(key).asText() : null;
        if (v != null && !v.isBlank()) return v;
        return ymlSetting(provider, key);
    }

    /** 密钥明文：DB.secrets.<key>.cipher 解密 → yml。**仅客户端内部调用，绝不出接口**。 */
    public String secret(String provider, String key) {
        if (isFixed()) return fixedSecrets.get(key);
        JsonNode r = row(provider);
        if (r != null && r.get("secrets") != null && r.get("secrets").get(key) != null) {
            String cipher = r.get("secrets").get(key).path("cipher").asText(null);
            String plain = crypto.decrypt(cipher);
            if (plain != null && !plain.isBlank()) return plain;
            // 解不开（换过主密钥/密文损坏）→ 视作未配置，回落 yml，而不是把发短信打成 5xx
        }
        return ymlSecret(provider, key);
    }

    /** dry-run 只走 yml：它是演练开关，不该由后台随手拨（prod 开启会写正常流水但不真发）。 */
    public boolean smsDryRun() {
        return isFixed() ? fixedDryRun : ymlSmsDryRun;
    }

    // ── 读接口用（明文永不出接口）───────────────────────────────────────────

    /** 各密钥的掩码（已配置→"****abc"；未配置→null）。DB 优先，回落 yml。 */
    public Map<String, String> maskedSecrets(String provider) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : secretKeysOf(provider)) {
            out.put(k, CryptoService.mask(secret(provider, k)));
        }
        return out;
    }

    /** 配置来源：DB(后台维护) / ENV(环境变量) / 未配置——让运维一眼看出这台机器实际吃的是哪份配置。 */
    public String source(String provider) {
        JsonNode r = row(provider);
        boolean dbHasSecret = false;
        if (r != null && r.get("secrets") != null) {
            for (String k : secretKeysOf(provider)) {
                if (r.get("secrets").get(k) != null) { dbHasSecret = true; break; }
            }
        }
        if (dbHasSecret) return "DB";
        boolean ymlHasSecret = secretKeysOf(provider).stream()
                .map(k -> ymlSecret(provider, k))
                .anyMatch(v -> v != null && !v.isBlank());
        return ymlHasSecret ? "ENV" : "NONE";
    }

    /** 该通道「填齐了没有」——启用前必须全有值，否则点了启用也发不出去。 */
    public boolean configured(String provider) {
        for (String k : secretKeysOf(provider)) {
            String v = secret(provider, k);
            if (v == null || v.isBlank()) return false;
        }
        for (String k : requiredSettingsOf(provider)) {
            String v = setting(provider, k);
            if (v == null || v.isBlank()) return false;
        }
        return true;
    }

    public static java.util.List<String> secretKeysOf(String provider) {
        return EBAOQUAN.equals(provider)
                ? java.util.List.of("appKey", "appKeySecret")
                : java.util.List.of("secretName", "secretKey");
    }

    /** 启用该通道必须有值的非密字段。 */
    public static java.util.List<String> requiredSettingsOf(String provider) {
        return EBAOQUAN.equals(provider)
                ? java.util.List.of("baseUrl")
                : java.util.List.of("smsBaseUrl");
    }

    // ── yml 兜底 ───────────────────────────────────────────────────────────

    private String ymlSetting(String provider, String key) {
        if (EBAOQUAN.equals(provider)) {
            return "baseUrl".equals(key) ? ymlEbqBaseUrl : null;
        }
        return switch (key) {
            case "smsBaseUrl" -> ymlSmsBaseUrl;
            case "videoBaseUrl" -> ymlSmsVideoBaseUrl;
            default -> null;
        };
    }

    private String ymlSecret(String provider, String key) {
        if (EBAOQUAN.equals(provider)) {
            return "appKey".equals(key) ? ymlEbqAppKey : ymlEbqAppKeySecret;
        }
        return "secretName".equals(key) ? ymlSmsSecretName : ymlSmsSecretKey;
    }
}
