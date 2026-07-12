package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.integration.SmsConfigService;
import com.youzheng.huicui.integration.SmsVars;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 短信通道按组织管理（v1.21.0·V934）。用户决策：**签名与模板由平台统一配置，物业不能编辑**；
 * 模板由平台代向运营商（智讯云）报备——本控制器只做状态流转与报备号回填（报备本身是线下动作）。
 *
 * 端点（/v1 由 context-path 提供）：
 *   GET    /sms/orgs                        listSmsOrgs           | (range)         | 组织列表（平台=全部物业/物业=仅自己）
 *   GET    /orgs/{id}/sms-config            getOrgSmsConfig       | (range)         | 配置+模板列表（读他人→404）
 *   PUT    /orgs/{id}/sms-config            updateOrgSmsConfig    | settings.manage | platform | upsert+审计
 *   POST   /orgs/{id}/sms-templates         createOrgSmsTemplate  | settings.manage | platform | 201·恒 DRAFT
 *   PUT    /sms-templates/{tplId}           updateOrgSmsTemplate  | settings.manage | platform | 仅 DRAFT/REJECTED 可改
 *   DELETE /sms-templates/{tplId}           deleteOrgSmsTemplate  | settings.manage | platform | 204·ACTIVE 不可删
 *   POST   /sms-templates/{tplId}/register  registerOrgSmsTemplate| settings.manage | platform | 报备结果回填
 *
 * 【防变量错位（最危险的失败模式）】运营商模板是位置变量 {0}{1}，顺序与报备不一致会把
 *   「张三，欠费 3600 元」发成「3600，欠费 张三 元」。故：写侧校验 varOrder ⊆ SmsVars 白名单；
 *   ACTIVE 化时校验 varOrder.size() == content 的 {n} 占位符个数。
 *
 * 【一个物业每种用途只有一个生效模板】register→ACTIVE 时同事务归档旧 ACTIVE；
 *   DB partial unique(uq_org_sms_tpl_active) 是并发兜底闸门。
 *
 * 【权限】复用 settings.manage（当前仅 SA 有；SE 无 → 平台运营在页面上自动只读，与其在额度页只读同构）。
 */
@RestController
public class SmsChannelController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SmsConfigService cfg;

    public SmsChannelController(JdbcTemplate jdbc, ObjectMapper json, SmsConfigService cfg) {
        this.jdbc = jdbc;
        this.json = json;
        this.cfg = cfg;
    }

    private static final String ORG_PROPERTY = "PROPERTY";
    private static final String ST_DRAFT = "DRAFT";
    private static final String ST_ACTIVE = "ACTIVE";
    private static final String ST_REJECTED = "REJECTED";
    private static final String ST_ARCHIVED = "ARCHIVED";

    // ── [1] GET /sms/orgs ────────────────────────────────────────────────────
    // 组织列表（对齐 /billing/orgs 范式）：平台=全部物业组织；非平台=仅本组织（服务商无短信业务→空集）。
    @GetMapping("/sms/orgs")
    public Page<Map<String, Object>> listSmsOrgs(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE o.type = '" + ORG_PROPERTY + "'");
        List<Object> args = new ArrayList<>();
        if (!s.isPlatform()) {
            where.append(" AND o.id = ?");
            args.add(orgIdLong(s));                          // 服务商 org 不是 PROPERTY → 天然空集
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM org o" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<Map<String, Object>> items = jdbc.query(
                "SELECT o.id, o.name, o.type,"
                        + " c.sign_name, c.enabled, (c.org_id IS NOT NULL) AS configured,"
                        + " COALESCE(t.active_cnt, 0) AS active_cnt, COALESCE(t.draft_cnt, 0) AS draft_cnt,"
                        + " COALESCE(r.sent_cnt, 0) AS sent_cnt, COALESCE(r.failed_cnt, 0) AS failed_cnt,"
                        + " b.balance AS sms_balance"
                        + " FROM org o"
                        + " LEFT JOIN org_sms_config c ON c.org_id = o.id"
                        + " LEFT JOIN (SELECT org_id,"
                        + "     count(*) FILTER (WHERE status = 'ACTIVE') AS active_cnt,"
                        + "     count(*) FILTER (WHERE status = 'DRAFT')  AS draft_cnt"
                        + "   FROM org_sms_template GROUP BY org_id) t ON t.org_id = o.id"
                        // 本月发送/失败（sms_record）
                        + " LEFT JOIN (SELECT org_id,"
                        + "     count(*) FILTER (WHERE status IN ('SENT','DELIVERED')) AS sent_cnt,"
                        + "     count(*) FILTER (WHERE status = 'FAILED') AS failed_cnt"
                        + "   FROM sms_record WHERE sent_at >= date_trunc('month', now()) GROUP BY org_id) r ON r.org_id = o.id"
                        // 短信额度余额：与额度管理同源（org_balance）
                        + " LEFT JOIN org_balance b ON b.org_id = o.id AND b.type = 'SMS'"
                        + where
                        + " ORDER BY o.id LIMIT ? OFFSET ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orgId", String.valueOf(rs.getLong("id")));
                    m.put("orgName", rs.getString("name"));
                    m.put("orgType", rs.getString("type"));
                    m.put("signName", rs.getString("sign_name"));           // 未配置→null（前端显示"平台默认"）
                    m.put("configured", rs.getBoolean("configured"));
                    Object en = rs.getObject("enabled");
                    m.put("enabled", en == null || (Boolean) en);           // 缺配置行 → 默认可发
                    m.put("activeTemplates", rs.getInt("active_cnt"));
                    m.put("draftTemplates", rs.getInt("draft_cnt"));
                    m.put("sentThisMonth", rs.getInt("sent_cnt"));
                    m.put("failedThisMonth", rs.getInt("failed_cnt"));
                    Object bal = rs.getObject("sms_balance");
                    m.put("smsBalance", bal == null ? null : rs.getDouble("sms_balance"));
                    return m;
                }, pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [2] GET /orgs/{id}/sms-config ───────────────────────────────────────
    // 配置 + 模板列表。缺配置行 → 返回平台默认值并置 configured=false（该物业在吃平台默认）。
    @GetMapping("/orgs/{id}/sms-config")
    public Map<String, Object> getOrgSmsConfig(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long orgId = parseId(id);
        OrgRow org = loadOrg(orgId);                            // 不存在→404
        requireOrgVisible(s, orgId);                            // 物业读他人→404（不泄露存在性）
        return buildConfigDto(org);
    }

    // ── [3] PUT /orgs/{id}/sms-config ───────────────────────────────────────
    @PutMapping("/orgs/{id}/sms-config")
    @RequirePermission("settings.manage")
    @Transactional
    public Map<String, Object> updateOrgSmsConfig(@PathVariable("id") String id,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = requirePlatform();
        long orgId = parseId(id);
        OrgRow org = loadOrg(orgId);
        if (!ORG_PROPERTY.equals(org.type())) {
            throw new ApiException(BizError.VALIDATION_422, "短信通道配置仅适用于物业组织");
        }

        String signName = optString(body, "signName");
        Integer cooldownMin = optInt(body, "cooldownMinutes");
        Integer ttlDays = optInt(body, "payLinkTtlDays");
        Integer warn = optInt(body, "warnThreshold");
        Boolean enabled = optBool(body, "enabled");
        if (cooldownMin != null && cooldownMin < 0) throw new ApiException(BizError.VALIDATION_422, "冷却分钟数不得为负");
        if (ttlDays != null && ttlDays < 1) throw new ApiException(BizError.VALIDATION_422, "链接有效期至少 1 天");
        if (warn != null && warn < 0) throw new ApiException(BizError.VALIDATION_422, "预警阈值不得为负");

        Map<String, Object> before = buildConfigDto(org);

        // upsert（org_id 是 PK）：未传的字段保持原值（COALESCE with EXCLUDED 语义手写）。
        jdbc.update(
                "INSERT INTO org_sms_config(org_id, sign_name, cooldown_seconds, pay_link_ttl_seconds,"
                        + " warn_threshold, enabled, updated_by, updated_at)"
                        + " VALUES (?, COALESCE(?, ?), COALESCE(?, ?), COALESCE(?, ?), ?, COALESCE(?, TRUE), ?, now())"
                        + " ON CONFLICT (org_id) DO UPDATE SET"
                        + "   sign_name = COALESCE(EXCLUDED.sign_name, org_sms_config.sign_name),"
                        + "   cooldown_seconds = COALESCE(?, org_sms_config.cooldown_seconds),"
                        + "   pay_link_ttl_seconds = COALESCE(?, org_sms_config.pay_link_ttl_seconds),"
                        + "   warn_threshold = ?,"
                        + "   enabled = COALESCE(?, org_sms_config.enabled),"
                        + "   updated_by = ?, updated_at = now()",
                orgId,
                signName, cfg.resolveSignName(orgId),                             // 新建时缺签名→当前解析值
                cooldownMin == null ? null : cooldownMin * 60, cfg.cooldownSeconds(orgId),
                ttlDays == null ? null : ttlDays * 86400, cfg.payLinkTtlSeconds(orgId),
                warn, enabled, actorId(s),
                cooldownMin == null ? null : cooldownMin * 60,
                ttlDays == null ? null : ttlDays * 86400,
                warn, enabled, actorId(s));

        Map<String, Object> after = buildConfigDto(org);
        audit(s, "sms.config.update", "org", orgId, before, after, null);
        return after;
    }

    // ── [4] POST /orgs/{id}/sms-templates ───────────────────────────────────
    // 新建草稿（status 恒 DRAFT，不接受入参 status/gatewayTemplateId——报备是线下动作，只能经 register 回填）。
    @PostMapping("/orgs/{id}/sms-templates")
    @RequirePermission("settings.manage")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createOrgSmsTemplate(@PathVariable("id") String id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = requirePlatform();
        long orgId = parseId(id);
        loadOrg(orgId);

        String kind = requireKind(body);
        String name = requireString(body, "name");
        String content = requireString(body, "content");
        List<String> varOrder = parseVarOrder(body);            // 非白名单→422

        Long tplId = jdbc.queryForObject(
                "INSERT INTO org_sms_template(org_id, kind, name, content, var_order, status, updated_by)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, 'DRAFT', ?) RETURNING id",
                Long.class, orgId, kind, name, content, writeJson(varOrder), actorId(s));

        Map<String, Object> dto = loadTemplate(tplId);
        audit(s, "sms.template.create", "sms_template", tplId, null, dto, null);
        return dto;
    }

    // ── [5] PUT /sms-templates/{tplId} ──────────────────────────────────────
    // 仅 DRAFT/REJECTED 可改；ACTIVE 的内容改动 → 422（须新建草稿重新报备，否则发出去的与报备的不一致）。
    @PutMapping("/sms-templates/{tplId}")
    @RequirePermission("settings.manage")
    @Transactional
    public Map<String, Object> updateOrgSmsTemplate(@PathVariable("tplId") String tplId,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = requirePlatform();
        long id = parseId(tplId);
        TplRow row = lockTemplate(id);                          // 不存在→404
        if (ST_ACTIVE.equals(row.status()) || ST_ARCHIVED.equals(row.status())) {
            throw new ApiException(BizError.VALIDATION_422,
                    "生效/已归档模板不可编辑（内容变更须新建草稿并重新报备，否则发出内容与报备不符）");
        }
        Map<String, Object> before = loadTemplate(id);

        String kind = requireKind(body);
        String name = requireString(body, "name");
        String content = requireString(body, "content");
        List<String> varOrder = parseVarOrder(body);

        jdbc.update("UPDATE org_sms_template SET kind = ?, name = ?, content = ?, var_order = ?::jsonb,"
                        + " status = 'DRAFT', reject_reason = NULL, updated_by = ?, updated_at = now()"
                        + " WHERE id = ?",
                kind, name, content, writeJson(varOrder), actorId(s), id);

        Map<String, Object> after = loadTemplate(id);
        audit(s, "sms.template.update", "sms_template", id, before, after, null);
        return after;
    }

    // ── [6] DELETE /sms-templates/{tplId} ───────────────────────────────────
    @DeleteMapping("/sms-templates/{tplId}")
    @RequirePermission("settings.manage")
    @Transactional
    public ResponseEntity<Void> deleteOrgSmsTemplate(@PathVariable("tplId") String tplId) {
        CurrentSubject s = requirePlatform();
        long id = parseId(tplId);
        TplRow row = lockTemplate(id);
        if (ST_ACTIVE.equals(row.status())) {
            throw new ApiException(BizError.VALIDATION_422,
                    "生效模板不可删除（请先报备新版本替换，旧版会自动归档）");
        }
        Map<String, Object> before = loadTemplate(id);
        jdbc.update("DELETE FROM org_sms_template WHERE id = ?", id);
        audit(s, "sms.template.delete", "sms_template", id, before, null, null);
        return ResponseEntity.noContent().build();
    }

    // ── [7] POST /sms-templates/{tplId}/register ────────────────────────────
    // 回填运营商报备结果：ACTIVE（必带 gatewayTemplateId + 变量数校验）/ REJECTED（带原因）。
    @PostMapping("/sms-templates/{tplId}/register")
    @RequirePermission("settings.manage")
    @Transactional
    public Map<String, Object> registerOrgSmsTemplate(@PathVariable("tplId") String tplId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = requirePlatform();
        long id = parseId(tplId);
        TplRow row = lockTemplate(id);
        Map<String, Object> before = loadTemplate(id);

        String result = requireString(body, "result");
        if (!ST_ACTIVE.equals(result) && !ST_REJECTED.equals(result)) {
            throw new ApiException(BizError.VALIDATION_422, "result 非法（仅 ACTIVE/REJECTED）");
        }
        if (ST_ARCHIVED.equals(row.status())) {
            throw new ApiException(BizError.STATE_409, "已归档模板不可再报备");
        }

        if (ST_REJECTED.equals(result)) {
            String reason = optString(body, "reason");
            jdbc.update("UPDATE org_sms_template SET status = 'REJECTED', reject_reason = ?,"
                            + " updated_by = ?, updated_at = now() WHERE id = ?",
                    reason, actorId(s), id);
            Map<String, Object> after = loadTemplate(id);
            audit(s, "sms.template.register", "sms_template", id, before, after, reason);
            return after;
        }

        // ACTIVE：必带报备号；变量数须与正文占位符个数一致（防变量错位——见类注释）。
        String gwId = optString(body, "gatewayTemplateId");
        if (gwId == null || gwId.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "生效须回填运营商模板ID（gatewayTemplateId）");
        }
        int placeholders = countPlaceholders(row.content());
        if (row.varOrder().size() != placeholders) {
            throw new ApiException(BizError.VALIDATION_422,
                    "模板变量数与报备顺序不一致：正文占位符 " + placeholders
                            + " 个，varOrder " + row.varOrder().size() + " 个（顺序错位会发出错误内容）");
        }
        // 语义幂等：已是同一报备号的 ACTIVE → no-op 返回（重放不报错、不重复审计）。
        if (ST_ACTIVE.equals(row.status()) && gwId.equals(row.gatewayTemplateId())) {
            return loadTemplate(id);
        }

        // 同 org×kind 的旧 ACTIVE 归档（同事务；DB partial unique 是并发兜底）。
        jdbc.update("UPDATE org_sms_template SET status = 'ARCHIVED', updated_at = now()"
                        + " WHERE org_id = ? AND kind = ? AND status = 'ACTIVE' AND id <> ?",
                row.orgId(), row.kind(), id);
        jdbc.update("UPDATE org_sms_template SET status = 'ACTIVE', gateway_template_id = ?,"
                        + " reject_reason = NULL, updated_by = ?, updated_at = now() WHERE id = ?",
                gwId, actorId(s), id);

        Map<String, Object> after = loadTemplate(id);
        audit(s, "sms.template.register", "sms_template", id, before, after, "报备生效 gw=" + gwId);
        return after;
    }

    // ════════════════════════ helpers ═══════════════════════════════════════

    private record OrgRow(long id, String name, String type) {}

    private record TplRow(long id, long orgId, String kind, String status,
                          String content, String gatewayTemplateId, List<String> varOrder) {}

    private OrgRow loadOrg(long orgId) {
        List<OrgRow> rows = jdbc.query("SELECT id, name, type FROM org WHERE id = ?",
                (rs, i) -> new OrgRow(rs.getLong("id"), rs.getString("name"), rs.getString("type")), orgId);
        if (rows.isEmpty()) throw new ApiException(BizError.NOT_FOUND_404, "组织不存在");
        return rows.get(0);
    }

    /** 物业只能读自己的配置；读他人 → 404（不泄露存在性）。平台不限。 */
    private void requireOrgVisible(CurrentSubject s, long orgId) {
        if (s.isPlatform()) return;
        Long mine = orgIdLong(s);
        if (mine == null || mine != orgId) {
            throw new ApiException(BizError.NOT_FOUND_404, "组织不存在");
        }
    }

    /** 配置 DTO：缺配置行 → 平台默认值 + configured=false（该物业在吃平台默认，运营看得见）。 */
    private Map<String, Object> buildConfigDto(OrgRow org) {
        Boolean configured = jdbc.query("SELECT 1 FROM org_sms_config WHERE org_id = ?",
                rs -> rs.next() ? Boolean.TRUE : Boolean.FALSE, org.id());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orgId", String.valueOf(org.id()));
        m.put("orgName", org.name());
        m.put("configured", Boolean.TRUE.equals(configured));
        m.put("signName", cfg.resolveSignName(org.id()));
        m.put("cooldownMinutes", (int) (cfg.cooldownSeconds(org.id()) / 60));
        m.put("payLinkTtlDays", (int) (cfg.payLinkTtlSeconds(org.id()) / 86400));
        m.put("warnThreshold", cfg.warnThreshold(org.id()));
        m.put("enabled", cfg.smsEnabled(org.id()));
        m.put("templates", listTemplates(org.id()));
        return m;
    }

    private List<Map<String, Object>> listTemplates(long orgId) {
        return jdbc.query(
                "SELECT id, org_id, kind, name, content, var_order::text AS vo, gateway_template_id,"
                        + " status, reject_reason, updated_at"
                        + " FROM org_sms_template WHERE org_id = ?"
                        + " ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'DRAFT' THEN 1"
                        + "   WHEN 'REJECTED' THEN 2 ELSE 3 END, id DESC",
                (rs, i) -> tplDto(rs), orgId);
    }

    private Map<String, Object> loadTemplate(long id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, org_id, kind, name, content, var_order::text AS vo, gateway_template_id,"
                        + " status, reject_reason, updated_at FROM org_sms_template WHERE id = ?",
                (rs, i) -> tplDto(rs), id);
        if (rows.isEmpty()) throw new ApiException(BizError.NOT_FOUND_404, "模板不存在");
        return rows.get(0);
    }

    private Map<String, Object> tplDto(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(rs.getLong("id")));
        m.put("orgId", String.valueOf(rs.getLong("org_id")));
        m.put("kind", rs.getString("kind"));
        m.put("name", rs.getString("name"));
        m.put("content", rs.getString("content"));
        m.put("varOrder", readVarOrder(rs.getString("vo")));
        m.put("gatewayTemplateId", rs.getString("gateway_template_id"));
        m.put("status", rs.getString("status"));
        m.put("rejectReason", rs.getString("reject_reason"));
        java.sql.Timestamp t = rs.getTimestamp("updated_at");
        m.put("updatedAt", t == null ? null : java.time.format.DateTimeFormatter.ISO_INSTANT.format(t.toInstant()));
        return m;
    }

    private TplRow lockTemplate(long id) {
        List<TplRow> rows = jdbc.query(
                "SELECT id, org_id, kind, status, content, gateway_template_id, var_order::text AS vo"
                        + " FROM org_sms_template WHERE id = ? FOR UPDATE",
                (rs, i) -> new TplRow(rs.getLong("id"), rs.getLong("org_id"), rs.getString("kind"),
                        rs.getString("status"), rs.getString("content"), rs.getString("gateway_template_id"),
                        readVarOrder(rs.getString("vo"))),
                id);
        if (rows.isEmpty()) throw new ApiException(BizError.NOT_FOUND_404, "模板不存在");
        return rows.get(0);
    }

    /** 正文里 {0} {1} … 的占位符个数（不同下标去重计数）。 */
    private static int countPlaceholders(String content) {
        if (content == null) return 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\d+)\\}").matcher(content);
        while (m.find()) seen.add(m.group(1));
        return seen.size();
    }

    // ── 入参解析（非法→422 / id→404）──

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "资源不存在");
        }
    }

    private CurrentSubject requirePlatform() {
        CurrentSubject s = SubjectContext.get();
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "短信通道配置为平台统一管理（物业不可编辑）");
        }
        return s;
    }

    private static String requireKind(Map<String, Object> body) {
        String k = requireString(body, "kind");
        if (!SmsConfigService.KIND_PAY_LINK.equals(k) && !SmsConfigService.KIND_NOTIFY.equals(k)
                && !SmsConfigService.KIND_VIDEO_NOTIFY.equals(k)) {
            throw new ApiException(BizError.VALIDATION_422, "kind 非法（仅 PAY_LINK/NOTIFY/VIDEO_NOTIFY）");
        }
        return k;
    }

    /** varOrder 须 ⊆ 变量白名单（防报备错位）。缺省=空数组（纯文案模板）。 */
    private static List<String> parseVarOrder(Map<String, Object> body) {
        Object v = body == null ? null : body.get("varOrder");
        if (v == null) return List.of();
        if (!(v instanceof List<?> raw)) throw new ApiException(BizError.VALIDATION_422, "varOrder 须为数组");
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            String k = o == null ? null : String.valueOf(o).trim();
            if (k == null || !SmsVars.isAllowed(k)) {
                throw new ApiException(BizError.VALIDATION_422,
                        "varOrder 含非法变量: " + k + "（仅允许 " + SmsVars.ALLOWED + "）");
            }
            out.add(k);
        }
        return out;
    }

    private static String requireString(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        String s = v == null ? null : String.valueOf(v).trim();
        if (s == null || s.isBlank()) throw new ApiException(BizError.VALIDATION_422, "缺少 " + key);
        return s;
    }

    private static String optString(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isBlank() ? null : s;
    }

    private static Integer optInt(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        try {
            if (v instanceof Number n) return n.intValue();
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, key + " 非法（须为整数）");
        }
    }

    private static Boolean optBool(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.valueOf(String.valueOf(v).trim());
    }

    private Long orgIdLong(CurrentSubject s) {
        try {
            return s.orgId() == null ? null : Long.valueOf(s.orgId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Long actorId(CurrentSubject s) {
        try {
            return s.accountId() == null ? null : Long.valueOf(s.accountId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<String> readVarOrder(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            return json.readValue(jsonText, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void audit(CurrentSubject s, String action, String targetType, long targetId,
                       Object before, Object after, String reason) {
        jdbc.update(
                "INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope,"
                        + " before_snap, after_snap, reason, trace_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                actorId(s), s.name() == null ? "" : s.name(), action,
                targetType + "#" + targetId, targetType, String.valueOf(targetId), s.orgType(),
                before == null ? null : writeJson(before),
                after == null ? null : writeJson(after),
                reason, org.slf4j.MDC.get("traceId"));
    }
}
