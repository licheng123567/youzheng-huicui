package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.SettingsDtos.SettingsDto;
import com.youzheng.huicui.web.dto.SettingsDtos.SettingsInputDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * settings 组端点（契约 listSettings / updateSettings；基路径 /v1 由 context-path 提供）。
 * 类名 SettingsController（本批新建，避免碰 M1-M10/org-member/AI 已有控制器）。
 *
 * 横切落地：
 *   - GET /settings   listSettings：x-data-scope=platform，无 x-permission。
 *       非平台主体 → 403（platform scope 既有范式：!isPlatform → PERM_403，参照 getPermissionMatrix）。
 *   - PUT /settings   updateSettings：x-permission=settings.manage + x-data-scope=platform +
 *       @Transactional + audit_log（OrgSystemAuditService.write，敏感写必落留痕）。
 *
 * 版本/生效时间（BR-M3-19）：每次 PUT INSERT 新版本行（version=max+1），不 UPDATE 旧行→保留历史；
 *   变更只对新计时案件生效（effectiveAt 落库，由计时域消费，本端点只负责留版本/生效时间）。
 *
 * AI 域前向兼容：契约 SettingsDomainEnum 当前无 AI（V910 规划）。本批 domain 校验对齐
 *   DB chk_settings_domain 白名单（与 enum 同 5 值），未来 enum/约束放开时此处随白名单同步即可；
 *   本批不改 enum/约束。非法 domain → 422（绝不让其撞 CHECK 约束而 5xx）。
 *
 * 列名核对见类尾注释。
 */
@RestController
public class SettingsController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    /**
     * domain 合法值白名单 == DB chk_settings_domain CHECK 约束集合（V2__peripheral_and_audit.sql）。
     * 与契约 SettingsDomainEnum 当前一致。AI 域待 V910 放开 enum/约束后在此处同步追加（本批不改）。
     */
    private static final Set<String> DOMAIN_WHITELIST =
            Set.of("TIMERS", "ROTATION", "MARK_CODES", "CLOSE_REASONS", "SMS");

    private final JdbcTemplate jdbc;
    private final OrgSystemAuditService audit;
    private final ObjectMapper json;

    public SettingsController(JdbcTemplate jdbc, OrgSystemAuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    // ── [14] GET /settings ──────────────────────────────────────────────────────
    // x-data-scope=platform，无 x-permission。每域返最新版本一条（裸数组·不分页）。
    @GetMapping("/settings")
    public List<SettingsDto> listSettings(@RequestParam(required = false) String domain) {
        CurrentSubject s = SubjectContext.get();
        // platform：非平台主体一律 403（业务规则配置仅平台可见/可配）。
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台可查看业务规则配置");
        }
        // 非法 domain 过滤值 → 422（不放进 SQL；空白当未传）。
        // 'AI' 是 ai-config 的内部存储域(V910)，非契约 SettingsDomainEnum 的 5 个业务域——
        //   仅经 GET /ai-config 暴露，不得出现在 /settings(否则违反 enum)。故 GET 一律排除 AI、?domain=AI→422。
        String domainFilter = (domain == null || domain.isBlank()) ? null : domain;
        if (domainFilter != null && (!DOMAIN_WHITELIST.contains(domainFilter) || "AI".equals(domainFilter))) {
            throw new ApiException(BizError.VALIDATION_422, "domain 非法: " + domainFilter);
        }

        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT ON (domain) domain, version, effective_at,"
                        + " timers, rotation, mark_codes, close_reasons, sms FROM settings"
                        + " WHERE domain <> 'AI'");
        List<Object> args = new ArrayList<>();
        if (domainFilter != null) {
            sql.append(" AND domain = ?");
            args.add(domainFilter);
        }
        // DISTINCT ON(domain) 每域取最新版本；ORDER BY 必须 domain 首列以配合 DISTINCT ON。
        sql.append(" ORDER BY domain, version DESC");

        return jdbc.query(sql.toString(), SETTINGS_MAPPER, args.toArray());
    }

    // ── [15] PUT /settings ──────────────────────────────────────────────────────
    // x-permission=settings.manage（PermissionInterceptor）+ x-data-scope=platform +
    //   @Transactional + audit_log。版本递增 INSERT 新行（不 UPDATE 旧行）→保留历史 BR-M3-19。
    @PutMapping("/settings")
    @RequirePermission("settings.manage")
    @Transactional
    public SettingsDto updateSettings(@RequestBody(required = false) SettingsInputDto body) {
        CurrentSubject s = SubjectContext.get();
        // platform：非平台主体一律 403（先于 422，避免越权者探测入参）。
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台可维护业务规则配置");
        }
        if (body == null) {
            throw new ApiException(BizError.VALIDATION_422, "请求体必填");
        }
        String domain = body.domain();
        if (domain == null || domain.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "domain 必填");
        }
        // 非法 domain → 422（对齐 chk 白名单；AI 域前向兼容：放开 enum/约束后此处随白名单同步）。
        if (!DOMAIN_WHITELIST.contains(domain)) {
            throw new ApiException(BizError.VALIDATION_422, "domain 非法: " + domain);
        }

        // before 快照：当前最新版本（无→null）。用于审计留痕。
        SettingsDto before = jdbc.query(
                "SELECT DISTINCT ON (domain) domain, version, effective_at,"
                        + " timers, rotation, mark_codes, close_reasons, sms"
                        + " FROM settings WHERE domain = ? ORDER BY domain, version DESC",
                rs -> rs.next() ? SETTINGS_MAPPER.mapRow(rs, 1) : null, domain);

        // 版本递增（乐观锁 uq_settings_domain_ver；并发同 domain 抢同 version → 唯一冲突回滚，绝不覆盖）。
        Integer nextVersion = jdbc.queryForObject(
                "SELECT COALESCE(max(version), 0) + 1 FROM settings WHERE domain = ?",
                Integer.class, domain);
        long updatedBy = Long.parseLong(s.accountId());

        // INSERT 新版本行（不 UPDATE 旧行→保留历史）。effectiveAt 空→COALESCE 到 now()（立即生效）。
        // 各域 JSONB 列按域语义写入对应那一列（??::jsonb）；markCodes/closeReasons 数组域结构忠实透传，
        //   markCodes connected/effectiveFollowUp 结构与读一致（BR-M4-12）。
        Map<String, Object> inserted = jdbc.queryForMap(
                "INSERT INTO settings(domain, version, effective_at, timers, rotation, mark_codes,"
                        + " close_reasons, sms, updated_by)"
                        + " VALUES (?, ?, COALESCE(?::timestamptz, now()),"
                        + " ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?)"
                        + " RETURNING domain, version, effective_at, timers, rotation, mark_codes,"
                        + " close_reasons, sms",
                domain, nextVersion, body.effectiveAt(),
                toJson(body.timers()), toJson(body.rotation()), toJson(body.markCodes()),
                toJson(body.closeReasons()), toJson(body.sms()), updatedBy);

        SettingsDto after = mapSettings(inserted);

        // 审计（改设置敏感写必落留痕）：settings.update，target_id=domain，before/after=版本快照。
        audit.write(s, "settings.update", "settings", domain, "PLATFORM", null, null,
                snapshot(before), snapshot(after));

        return after;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** JSONB 列文本 → object（Jackson 树）；null/空→null。解析失败容错返 null（绝不抛 5xx）。 */
    private Object parseJson(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return json.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 入参 object → JSON 文本供 ?::jsonb 绑定；null→null。序列化失败容错返 null（绝不抛 5xx）。 */
    private String toJson(Object v) {
        if (v == null) return null;
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            return null;
        }
    }

    private final RowMapper<SettingsDto> SETTINGS_MAPPER = (rs, i) -> new SettingsDto(
            rs.getString("domain"),
            rs.getInt("version"),
            ts(rs.getTimestamp("effective_at")),
            parseJson(rs.getString("timers")),
            parseJson(rs.getString("rotation")),
            parseJson(rs.getString("mark_codes")),
            parseJson(rs.getString("close_reasons")),
            parseJson(rs.getString("sms")));

    /** RETURNING 行（queryForMap）→ SettingsDto。JSONB 列经 PG 驱动多为 PGobject，统一 toString 再解析。 */
    private SettingsDto mapSettings(Map<String, Object> row) {
        return new SettingsDto(
                (String) row.get("domain"),
                ((Number) row.get("version")).intValue(),
                tsObj(row.get("effective_at")),
                parseJson(asText(row.get("timers"))),
                parseJson(asText(row.get("rotation"))),
                parseJson(asText(row.get("mark_codes"))),
                parseJson(asText(row.get("close_reasons"))),
                parseJson(asText(row.get("sms"))));
    }

    /** 审计快照：把 SettingsDto 摊平成 Map（before 可 null→null）。 */
    private Map<String, Object> snapshot(SettingsDto s) {
        if (s == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", s.domain());
        m.put("version", s.version());
        m.put("effectiveAt", s.effectiveAt());
        m.put("timers", s.timers());
        m.put("rotation", s.rotation());
        m.put("markCodes", s.markCodes());
        m.put("closeReasons", s.closeReasons());
        m.put("sms", s.sms());
        return m;
    }

    private static String asText(Object o) {
        return o == null ? null : o.toString();
    }

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    private static String tsObj(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) return ts(t);
        if (o instanceof java.time.OffsetDateTime odt) return ISO.format(odt.toInstant());
        if (o instanceof java.time.Instant ins) return ISO.format(ins);
        return o.toString();
    }

    @SuppressWarnings("unused")
    private static String idOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : String.valueOf(v);
    }

    // ===== SQL 列名 ↔ 契约字段核对（DDL V2 settings）=====
    // settings.domain        -> domain        (chk_settings_domain 白名单 == SettingsDomainEnum)
    // settings.version       -> version       (int；max+1 递增，uq_settings_domain_ver 乐观锁)
    // settings.effective_at  -> effectiveAt   (TIMESTAMPTZ；入参空→COALESCE now() 立即生效)
    // settings.timers        -> timers        (JSONB→对象域)
    // settings.rotation      -> rotation      (JSONB→对象域)
    // settings.mark_codes    -> markCodes     (JSONB→数组域；connected/effectiveFollowUp 结构读写一致 BR-M4-12)
    // settings.close_reasons -> closeReasons  (JSONB→数组域)
    // settings.sms           -> sms           (JSONB→对象域)
    // settings.updated_by    <- s.accountId   (写库审计字段，FK account)
    // 读：DISTINCT ON(domain) ... ORDER BY domain, version DESC → 每域最新版本一条
}
