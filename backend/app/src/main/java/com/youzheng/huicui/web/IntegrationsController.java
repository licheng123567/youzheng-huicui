package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.CryptoService;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.integration.IntegrationConfigService;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.IntegrationDtos.IntegrationDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三方通道配置（V936·v1.23.0）。用户诉求：「易保全、LLM、ASR 这些三方接口的 key，后台没有配置界面。」
 *
 * 【只做真接得通的两个通道】易保全(存证) + 智讯云(短信)——客户端真实存在，填了就真能用。
 *   LLM/ASR 的客户端还没写（Phase 3），此刻做输入框只会造出「配了但不生效」的空壳页——不做，
 *   前端在 AI 区域显式标注「待接入」。
 *
 * 【密钥永不出接口】读接口只回 last4 掩码（****abc）。明文只存在于：① 写请求体 ② 库里的密文
 *   ③ 客户端调网关的那一刻。审计里也只记「哪些 key 被改过」，绝不记值。
 *
 * 【解析优先级】DB → yml(环境变量) → 默认。已用环境变量部署的实例升级后行为不变。
 *
 * 【为什么启用前要校验填齐】点了「启用」但 key 是空的 → 发短信/出存证会静默走占位，
 *   运维会以为开了其实没开。故 enabled=true 时缺字段直接 422。
 */
@RestController
public class IntegrationsController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CryptoService crypto;
    private final IntegrationConfigService cfg;
    private final OrgSystemAuditService audit;

    public IntegrationsController(JdbcTemplate jdbc, ObjectMapper json, CryptoService crypto,
                                  IntegrationConfigService cfg, OrgSystemAuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.crypto = crypto;
        this.cfg = cfg;
        this.audit = audit;
    }

    private static final Map<String, String> NAME = Map.of(
            IntegrationConfigService.EBAOQUAN, "易保全（区块链存证）",
            IntegrationConfigService.SMS, "智讯云（短信网关）",
            IntegrationConfigService.BAILIAN, "阿里百炼（录音转写 ASR）",
            IntegrationConfigService.DEEPSEEK, "DeepSeek（大模型 LLM）");

    private static final List<String> PROVIDERS = List.of(
            IntegrationConfigService.EBAOQUAN, IntegrationConfigService.SMS,
            IntegrationConfigService.BAILIAN, IntegrationConfigService.DEEPSEEK);

    // ── GET /integrations ─────────────────────────────────────────────────
    @GetMapping("/integrations")
    @RequirePermission("settings.manage")
    public List<IntegrationDto> listIntegrations() {
        requirePlatform();
        List<IntegrationDto> out = new ArrayList<>();
        for (String p : PROVIDERS) {
            Map<String, String> settings = new LinkedHashMap<>();
            for (String k : settingKeysOf(p)) settings.put(k, cfg.setting(p, k));
            // 时间戳一律走 Timestamps.iso（→ 2026-07-12T07:11:04Z）。自己 to_char 出的 '+00' 不是
            // 合法 RFC3339 date-time（要 +00:00 或 Z），schemathesis 会判响应违约——CI 逮到过。
            Map<String, Object> row = jdbc.query(
                    "SELECT i.updated_at, a.name AS by_name"
                            + " FROM integration_config i LEFT JOIN account a ON a.id = i.updated_by"
                            + " WHERE i.provider = ?",
                    rs -> {
                        if (!rs.next()) return null;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("ua", com.youzheng.huicui.common.Timestamps.iso(rs.getTimestamp("updated_at")));
                        m.put("by", rs.getString("by_name"));
                        return m;
                    }, p);
            out.add(new IntegrationDto(
                    p, NAME.get(p),
                    cfg.enabled(p), cfg.configured(p), cfg.source(p),
                    settings, cfg.maskedSecrets(p),
                    row == null ? null : (String) row.get("ua"),
                    row == null ? null : (String) row.get("by"),
                    crypto.isReady()));
        }
        return out;
    }

    // ── PUT /integrations/{provider} ──────────────────────────────────────
    // secrets 语义：**缺键=不改**（前端回显的是掩码，不能把掩码当明文写回去）；
    //   传空串=清除该密钥（回落 yml）；传非空=加密覆盖。
    @PutMapping("/integrations/{provider}")
    @RequirePermission("settings.manage")
    @Transactional
    @SuppressWarnings("unchecked")
    public IntegrationDto updateIntegration(@PathVariable String provider,
                                            @RequestBody(required = false) Map<String, Object> body) {
        requirePlatform();
        CurrentSubject s = SubjectContext.get();
        String p = provider == null ? "" : provider.trim().toUpperCase();
        if (!PROVIDERS.contains(p)) {
            throw new ApiException(BizError.NOT_FOUND_404, "未知的三方通道: " + provider);
        }
        Map<String, Object> b = body == null ? Map.of() : body;
        boolean enabled = Boolean.TRUE.equals(b.get("enabled"));

        // 现有 DB 行（增量合并：本次没传的字段保持原值）
        String curSettingsJson = jdbc.query("SELECT settings::text FROM integration_config WHERE provider = ?",
                rs -> rs.next() ? rs.getString(1) : null, p);
        String curSecretsJson = jdbc.query("SELECT secrets::text FROM integration_config WHERE provider = ?",
                rs -> rs.next() ? rs.getString(1) : null, p);
        Map<String, Object> settings = readMap(curSettingsJson);
        Map<String, Object> secrets = readMap(curSecretsJson);

        Object sIn = b.get("settings");
        if (sIn instanceof Map<?, ?> m) {
            for (String k : settingKeysOf(p)) {
                Object v = ((Map<String, Object>) m).get(k);
                if (v == null) continue;                           // 缺键=不改
                String t = String.valueOf(v).trim();
                if (t.isBlank()) settings.remove(k);               // 空串=清除（回落 yml）
                else settings.put(k, t);
            }
        }

        List<String> changedSecrets = new ArrayList<>();
        Object secIn = b.get("secrets");
        if (secIn instanceof Map<?, ?> m) {
            for (String k : IntegrationConfigService.secretKeysOf(p)) {
                Object v = ((Map<String, Object>) m).get(k);
                if (v == null) continue;                           // 缺键=不改（前端回显掩码，绝不写回）
                String t = String.valueOf(v).trim();
                if (t.isBlank()) {
                    secrets.remove(k);                             // 空串=清除
                    changedSecrets.add(k + "(清除)");
                } else {
                    secrets.put(k, Map.of("cipher", crypto.encrypt(t),  // 主密钥未配置 → 409
                            "last4", String.valueOf(CryptoService.mask(t))));
                    changedSecrets.add(k);
                }
            }
        }

        String before = "enabled=" + cfg.enabled(p) + "; source=" + cfg.source(p);

        jdbc.update("INSERT INTO integration_config(provider, enabled, settings, secrets, updated_by, updated_at)"
                        + " VALUES (?, ?, ?::jsonb, ?::jsonb, ?, now())"
                        + " ON CONFLICT (provider) DO UPDATE SET enabled = EXCLUDED.enabled,"
                        + " settings = EXCLUDED.settings, secrets = EXCLUDED.secrets,"
                        + " updated_by = EXCLUDED.updated_by, updated_at = now()",
                p, enabled, writeJson(settings), writeJson(secrets), Long.parseLong(s.accountId()));

        // 启用校验放在写之后、事务内：此时 cfg 读到的就是新值（含 yml 回落），漏字段直接回滚 422。
        // 「点了启用但 key 是空的」会让发短信/出存证静默走占位——运维以为开了其实没开，最坑。
        if (enabled && !cfg.configured(p)) {
            List<String> missing = new ArrayList<>();
            for (String k : IntegrationConfigService.secretKeysOf(p)) {
                String v = cfg.secret(p, k);
                if (v == null || v.isBlank()) missing.add(k);
            }
            for (String k : IntegrationConfigService.requiredSettingsOf(p)) {
                String v = cfg.setting(p, k);
                if (v == null || v.isBlank()) missing.add(k);
            }
            throw new ApiException(BizError.VALIDATION_422,
                    "启用该通道前必须填齐：" + String.join("、", missing));
        }
        // 沙箱地址出的存证证书没有法律效力——启用正式存证却指向 sandbox 是纯粹的自欺。
        if (enabled && IntegrationConfigService.EBAOQUAN.equals(p)) {
            String url = cfg.setting(p, "baseUrl");
            if (url != null && url.contains("sandbox")) {
                throw new ApiException(BizError.VALIDATION_422,
                        "接口地址仍指向 sandbox：沙箱出的证书没有法律效力，启用前请改为正式地址");
            }
        }

        // 审计：只记「哪些 key 被改过」，**绝不记值**。
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("enabled", enabled);
        after.put("source", cfg.source(p));
        after.put("secretsChanged", changedSecrets.isEmpty() ? "无" : String.join(",", changedSecrets));
        audit.write(s, "integration.update", "integration", p, "PLATFORM", null, null,
                Map.of("before", before), after);

        return listIntegrations().stream().filter(x -> x.provider().equals(p)).findFirst().orElseThrow();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void requirePlatform() {
        if (!SubjectContext.get().isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台可维护三方通道配置");
        }
    }

    /** 非密字段（可在后台看明文）。dry-run/public-base-url 不进来：前者是演练开关、后者是部署域名，都归环境变量。 */
    private static List<String> settingKeysOf(String provider) {
        return switch (provider) {
            case IntegrationConfigService.EBAOQUAN -> List.of("baseUrl");
            case IntegrationConfigService.SMS -> List.of("smsBaseUrl", "videoBaseUrl");
            // 百炼/DeepSeek：地址与模型都可覆盖（换区域/换模型不必改代码），留空走内置默认
            default -> List.of("baseUrl", "model");
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        try {
            return json.readValue(raw, LinkedHashMap.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }
}
