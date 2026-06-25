package com.youzheng.huicui.web;

import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.BillingUsageDto;
import com.youzheng.huicui.web.dto.RechargeLogM9Dto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M9-B 组：计费/充值（**只用量不金额** US-M10-02/BR-M9-06a/06b）。横切层范式 + scaffold；
 * JdbcTemplate 查真表；类名带 M9 后缀，与 M1-M10/org-member/AI controller 物理隔离，不碰共享件/其他组/pom。
 *
 * 端点（基路径 /v1 由 server.servlet.context-path 提供，注解写裸路径）：
 *   GET  /billing/usage        getBillingUsage | (无 x-permission) | scope=range | 200（空集 items:[]）
 *   GET  /billing/recharge-log listRechargeLog | (无 x-permission) | scope=range | 200（空集 items:[]）
 *   POST /billing/recharge     createRecharge  | perm=billing.recharge | scope=platform | @Transactional+audit | 201 / 403/404/422
 *
 * 【x-data-scope=range（读端点裸 org_id 列裁剪，不经 project）】
 *   平台(PLATFORM) → 全量；
 *   非平台(物业/服务商) → AND org_id = s.orgId（recharge_log/billing_usage 均有裸 org_id 列）。
 *   两表直挂组织，无 project/batch 关联，scope 直接落在表自身 org_id。
 *
 * 【createRecharge 平台专属（x-data-scope=platform）】
 *   PermissionInterceptor 先按 @RequirePermission("billing.recharge") 挡非授权（403）；
 *   service 层再兜底 s.isPlatform() 复核（非平台→403 PERM_403，即使误配权限亦不放行）。
 *   org×type 矩阵（BR-M9-07/08/10）：SMS 仅 PROPERTY 可充（服务商充 SMS→422）；STT 物业/服务商均可。
 *   余额 = 该 org×type 最新 balance（无→0）；INSERT recharge_log(delta=qty, balance=旧+qty)。
 *   敏感写（充值留痕 BR-M9-06a）必落 audit_log（actor/action='billing.recharge'/target_type='org'/after_snap）。
 *
 * 【只量不金额】delta/balance/qty 均为用量单位（分钟/条/次/件），DB NUMERIC→Double，绝不携带任何 *_cents 金额字段。
 * 幂等：写端点 Idempotency-Key 由 IdempotencyInterceptor 在 header 层兜底（同键重放→409），控制器无需声明参数。
 * 列名严格对齐 DDL：billing_usage(id/type/qty/unit/case_id/occurred_at/org_id) / recharge_log(id/type/delta/balance/ref/note/operated_by/tm/org_id) / org(id/type) / audit_log。
 */
@RestController
public class BillingM9Controller {

    private final JdbcTemplate jdbc;

    public BillingM9Controller(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private static final String TYPE_STT = "STT";
    private static final String TYPE_SMS = "SMS";
    private static final String TYPE_EVIDENCE = "EVIDENCE";
    private static final String TYPE_LEGAL = "LEGAL";

    private static final String ORG_PROPERTY = "PROPERTY";
    private static final String ORG_PROVIDER = "PROVIDER";

    // ── [11] getBillingUsage  GET /billing/usage ─────────────────────────────
    // x-data-scope=range（无 x-permission）：type?/month?(YYYY-MM) 过滤 + 分页。空集返 items:[]，无错误码。
    @GetMapping("/billing/usage")
    public Page<BillingUsageDto> getBillingUsage(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            validateBillingType(type);                       // 非法 type→422（优雅，非 5xx）
            where.append(" AND type = ?");
            args.add(type.trim());
        }
        if (month != null && !month.isBlank()) {
            where.append(" AND to_char(occurred_at, 'YYYY-MM') = ?");
            args.add(month.trim());
        }
        appendRangeScope(s, where, args);                    // 平台无；非平台 AND org_id=?

        String base = "FROM billing_usage" + where;
        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<BillingUsageDto> items = jdbc.query(
                "SELECT id, type, qty, unit, case_id, occurred_at " + base
                        + " ORDER BY occurred_at DESC LIMIT ? OFFSET ?",
                BillingM9Controller::mapUsage, pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [12] listRechargeLog  GET /billing/recharge-log ──────────────────────
    // x-data-scope=range（无 x-permission）：from?/to?(date·半开区间) 过滤 + 分页。空集返 items:[]，无错误码。
    @GetMapping("/billing/recharge-log")
    public Page<RechargeLogM9Dto> listRechargeLog(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (from != null && !from.isBlank()) {
            where.append(" AND tm >= ?::timestamptz");
            args.add(from.trim());
        }
        if (to != null && !to.isBlank()) {
            // 半开区间：tm < to + 1 天（含 to 当日全部记录）。
            where.append(" AND tm < (?::timestamptz + interval '1 day')");
            args.add(to.trim());
        }
        appendRangeScope(s, where, args);                    // 平台无；非平台 AND org_id=?

        String base = "FROM recharge_log" + where;
        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<RechargeLogM9Dto> items = jdbc.query(
                "SELECT id, type, delta, balance, ref, tm " + base
                        + " ORDER BY tm DESC LIMIT ? OFFSET ?",
                BillingM9Controller::mapRechargeLog, pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [13] createRecharge  POST /billing/recharge ──────────────────────────
    // perm=billing.recharge（PermissionInterceptor 挡）+ x-data-scope=platform（service 复核）。
    // 校验 org 存在(404)/org×type 矩阵(422)/qty>0(422) → 读旧余额 → INSERT recharge_log → audit_log → 201。
    @PostMapping("/billing/recharge")
    @RequirePermission("billing.recharge")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createRecharge(@RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        // x-data-scope=platform：仅平台可充值（即使 perm 通过亦兜底）。
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台可充值能力额度");
        }

        long orgId = parseRequiredOrgId(body);               // 缺/非数→422
        String type = parseRechargeType(body);               // 缺/非 STT|SMS→422
        BigDecimal qty = parsePositiveQty(body);             // 缺/非数/≤0→422
        String note = parseOptionalString(body, "note");

        // org 存在性 + org×type 矩阵校验。
        String orgType = loadOrgType(orgId);                 // 不存在→404
        assertOrgTypeMatrix(orgType, type);                  // 服务商充 SMS→422

        // 串行化本 org×type 余额读-改-写：advisory 事务锁防并发丢失更新(审计 H-1)。@Transactional 提交时自动释放。
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", (int) orgId, type.hashCode());
        // 读当前 org×type 最新 balance（无→0），新余额=旧+qty。
        BigDecimal oldBalance = latestBalance(orgId, type);
        BigDecimal newBalance = oldBalance.add(qty);

        long operatedBy = actorIdOrThrow(s);
        jdbc.update(
                "INSERT INTO recharge_log(org_id, type, delta, balance, ref, note, operated_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                orgId, type, qty, newBalance, null, note, operatedBy);

        // 平台充值留痕（BR-M9-06a）：after_snap={type,qty,balance}。
        auditRecharge(s, orgId, type, qty, newBalance, note);

        return Map.of("ok", true, "type", type, "balance", newBalance);
    }

    // ════════════════════════════ scope ══════════════════════════════════════

    /** x-data-scope=range（裸 org_id 列裁剪，不经 project）：平台不限；非平台 AND org_id=s.orgId。 */
    private void appendRangeScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        if (s.isPlatform()) return;                          // 平台全量
        where.append(" AND org_id = ?");
        args.add(orgIdLong(s));
    }

    // ════════════════════════════ recharge 校验 ══════════════════════════════

    /** org×type 矩阵（BR-M9-07/08/10）：SMS 仅 PROPERTY 可充；STT 物业/服务商均可。违反→422。 */
    private void assertOrgTypeMatrix(String orgType, String type) {
        if (TYPE_SMS.equals(type) && !ORG_PROPERTY.equals(orgType)) {
            throw new ApiException(BizError.VALIDATION_422, "SMS 短信额度仅物业可充值");
        }
        if (TYPE_STT.equals(type) && !ORG_PROPERTY.equals(orgType) && !ORG_PROVIDER.equals(orgType)) {
            // 平台自身不作为充值受体（无业务用量）；STT 仅物业/服务商。
            throw new ApiException(BizError.VALIDATION_422, "STT 分钟额度仅物业/服务商可充值");
        }
    }

    /** org 存在性 + 取 type 列。不存在→404。 */
    private String loadOrgType(long orgId) {
        try {
            return jdbc.queryForObject("SELECT type FROM org WHERE id = ?", String.class, orgId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "组织不存在: " + orgId);
        }
    }

    /** 该 org×type 最新 balance 快照（按 tm DESC, id DESC 取首条）；无记录→0。 */
    private BigDecimal latestBalance(long orgId, String type) {
        BigDecimal bal = jdbc.query(
                "SELECT balance FROM recharge_log WHERE org_id = ? AND type = ?"
                        + " ORDER BY tm DESC, id DESC LIMIT 1",
                rs -> rs.next() ? rs.getBigDecimal("balance") : null,
                orgId, type);
        return bal == null ? BigDecimal.ZERO : bal;
    }

    // ════════════════════════════ audit_log ══════════════════════════════════

    /** 写 audit_log（充值留痕 BR-M9-06a）：action='billing.recharge', target_type='org', after_snap={type,qty,balance}。 */
    private void auditRecharge(CurrentSubject s, long orgId, String type, BigDecimal qty,
                              BigDecimal balance, String note) {
        String afterSnap = "{\"type\":\"" + type + "\",\"qty\":" + qty.toPlainString()
                + ",\"balance\":" + balance.toPlainString() + "}";
        jdbc.update(
                "INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope, after_snap, reason, trace_id)"
                        + " VALUES (?, ?, 'billing.recharge', ?, 'org', ?, ?, ?::jsonb, ?, ?)",
                actorIdOrNull(s), nz(s.name()),
                "org#" + orgId + " recharge " + type + " +" + qty.toPlainString(),
                String.valueOf(orgId), s.orgType(), afterSnap, note,
                org.slf4j.MDC.get("traceId"));
    }

    // ════════════════════════════ row mappers ════════════════════════════════

    /** billing_usage 行 → BillingUsageDto。qty NUMERIC→Double（只量不金额），case_id 可空。 */
    private static BillingUsageDto mapUsage(ResultSet rs, int i) throws SQLException {
        return new BillingUsageDto(
                String.valueOf(rs.getLong("id")),
                rs.getString("type"),
                doubleOrNull(rs, "qty"),
                rs.getString("unit"),
                idOrNull(rs, "case_id"),
                ts(rs.getTimestamp("occurred_at")));
    }

    /** recharge_log 行 → RechargeLogM9Dto。delta/balance NUMERIC→Double（用量单位非金额）。 */
    private static RechargeLogM9Dto mapRechargeLog(ResultSet rs, int i) throws SQLException {
        return new RechargeLogM9Dto(
                String.valueOf(rs.getLong("id")),
                rs.getString("type"),
                doubleOrNull(rs, "delta"),
                doubleOrNull(rs, "balance"),
                rs.getString("ref"),
                ts(rs.getTimestamp("tm")));
    }

    // ════════════════════════════ 入参解析（非法→422 / org→404）═══════════════

    private void validateBillingType(String type) {
        String t = type.trim();
        if (!TYPE_STT.equals(t) && !TYPE_SMS.equals(t) && !TYPE_EVIDENCE.equals(t) && !TYPE_LEGAL.equals(t)) {
            throw new ApiException(BizError.VALIDATION_422, "type 非法（仅 STT/SMS/EVIDENCE/LEGAL）");
        }
    }

    private long parseRequiredOrgId(Map<String, Object> body) {
        Object v = body == null ? null : body.get("orgId");
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 orgId");
        }
        try {
            if (v instanceof Number n) return n.longValue();
            return Long.parseLong(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, "orgId 非法");
        }
    }

    /** RechargeTypeEnum：仅 STT/SMS（EVIDENCE/LEGAL 非预充，不在充值枚举 BR-M9-10）。缺/非法→422。 */
    private String parseRechargeType(Map<String, Object> body) {
        Object v = body == null ? null : body.get("type");
        String t = v == null ? null : String.valueOf(v).trim();
        if (t == null || t.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 type");
        }
        if (!TYPE_STT.equals(t) && !TYPE_SMS.equals(t)) {
            throw new ApiException(BizError.VALIDATION_422, "充值 type 仅支持 STT/SMS（EVIDENCE/LEGAL 非预充）");
        }
        return t;
    }

    /** qty 必填、为数、>0（用量单位）。缺/非数/≤0→422。 */
    private BigDecimal parsePositiveQty(Map<String, Object> body) {
        Object v = body == null ? null : body.get("qty");
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 qty");
        }
        BigDecimal qty;
        try {
            if (v instanceof Number n) qty = new BigDecimal(n.toString());
            else qty = new BigDecimal(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, "qty 非法");
        }
        if (qty.signum() <= 0) {
            throw new ApiException(BizError.VALIDATION_422, "qty 必须大于 0");
        }
        return qty;
    }

    private String parseOptionalString(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        String str = String.valueOf(v).trim();
        return str.isBlank() ? null : str;
    }

    // ════════════════════════════ 低级工具 ════════════════════════════════════

    private static String nz(String v) { return v == null ? "" : v; }

    private Long actorIdOrNull(CurrentSubject s) {
        try {
            return s.accountId() == null ? null : Long.valueOf(s.accountId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private long actorIdOrThrow(CurrentSubject s) {
        Long id = actorIdOrNull(s);
        if (id == null) throw new ApiException(BizError.AUTH_401, "无效主体账号");
        return id;
    }

    private Long orgIdLong(CurrentSubject s) {
        try {
            return s.orgId() == null ? null : Long.valueOf(s.orgId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    private static Double doubleOrNull(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return v == null ? null : v.doubleValue();
    }

    private static String idOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : String.valueOf(v);
    }
}
