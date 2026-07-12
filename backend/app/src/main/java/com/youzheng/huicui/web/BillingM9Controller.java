package com.youzheng.huicui.web;

import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.BillingUsageDto;
import com.youzheng.huicui.web.dto.OrgQuotaDto;
import com.youzheng.huicui.web.dto.UsageSummaryRowDto;
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

    private final com.youzheng.huicui.common.BalanceService balance;

    public BillingM9Controller(JdbcTemplate jdbc, com.youzheng.huicui.common.BalanceService balance) {
        this.jdbc = jdbc;
        this.balance = balance;
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
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        // v1.19.0 组织下钻：平台可按组织过滤；非平台传别人 orgId 与 range scope 叠加互斥 → 空集（零泄漏）。
        Long orgFilter = parseOptionalLong(orgId);
        if (orgFilter != null) {
            where.append(" AND bu.org_id = ?");
            args.add(orgFilter);
        }
        if (type != null && !type.isBlank()) {
            validateBillingType(type);                       // 非法 type→422（优雅，非 5xx）
            where.append(" AND bu.type = ?");
            args.add(type.trim());
        }
        if (month != null && !month.isBlank()) {
            where.append(" AND to_char(bu.occurred_at, 'YYYY-MM') = ?");
            args.add(month.trim());
        }
        appendRangeScope(s, where, args, "bu.org_id");       // 平台无；非平台 AND bu.org_id=?（join 后限定别名）

        // 计费明细穿透列（业主/房号/项目/批次）由 case_id LEFT JOIN 补齐（v1.11.0）。
        // 用量行不一定挂案（case_id 可空，如批量短信）→ LEFT JOIN，缺则列为 null。
        String base = "FROM billing_usage bu"
                + " JOIN org o ON o.id = bu.org_id"
                + " LEFT JOIN \"case\" c ON c.id = bu.case_id"
                + " LEFT JOIN project p ON p.id = c.project_id"
                + " LEFT JOIN batch b ON b.id = c.batch_id"
                + where;
        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<BillingUsageDto> items = jdbc.query(
                "SELECT bu.id, bu.type, bu.qty, bu.unit, bu.case_id, bu.occurred_at,"
                        + " c.owner_name, c.room, p.name AS project_name, b.no AS batch_no,"
                        + " bu.org_id, o.name AS org_name " + base
                        + " ORDER BY bu.occurred_at DESC LIMIT ? OFFSET ?",
                BillingM9Controller::mapUsage, pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [12] listRechargeLog  GET /billing/recharge-log ──────────────────────
    // x-data-scope=range（无 x-permission）：from?/to?(date·半开区间) 过滤 + 分页。空集返 items:[]，无错误码。
    @GetMapping("/billing/recharge-log")
    public Page<RechargeLogM9Dto> listRechargeLog(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        Long orgFilter = parseOptionalLong(orgId);           // v1.19.0 组织过滤（非平台叠 scope 互斥→空集）
        if (orgFilter != null) {
            where.append(" AND rl.org_id = ?");
            args.add(orgFilter);
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND rl.tm >= ?::timestamptz");
            args.add(from.trim());
        }
        if (to != null && !to.isBlank()) {
            // 半开区间：tm < to + 1 天（含 to 当日全部记录）。
            where.append(" AND rl.tm < (?::timestamptz + interval '1 day')");
            args.add(to.trim());
        }
        appendRangeScope(s, where, args, "rl.org_id");       // 平台无；非平台 AND rl.org_id=?

        String base = "FROM recharge_log rl"
                + " JOIN org o ON o.id = rl.org_id"
                + " LEFT JOIN account a ON a.id = rl.operated_by"
                + where;
        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<RechargeLogM9Dto> items = jdbc.query(
                "SELECT rl.id, rl.type, rl.delta, rl.balance, COALESCE(rl.ref, '') AS ref, rl.tm,"
                        + " rl.org_id, o.name AS org_name, rl.note, a.name AS operated_by_name " + base
                        + " ORDER BY rl.tm DESC, rl.id DESC LIMIT ? OFFSET ?",
                BillingM9Controller::mapRechargeLog, pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [13] createRecharge  POST /billing/recharge ──────────────────────────
    // ── [11b] listOrgQuotas  GET /billing/orgs ───────────────────────────────
    // 组织额度总览（v1.19.0「额度管理」核心）：**一行=一个组织×一个额度类型**（扁平，前端表格直吃）。
    // x-data-scope=range：平台=全组织（排除 PLATFORM 自身——不作充值受体）；非平台=仅本组织（4 行）。
    // balance 可为负（EVIDENCE/LEGAL 后付=欠用记账）；rechargeable 由 BillingUnits 矩阵下发，前端不复刻。
    @GetMapping("/billing/orgs")
    public Page<OrgQuotaDto> listOrgQuotas(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder orgWhere = new StringBuilder(" WHERE o.type <> 'PLATFORM'");
        List<Object> orgArgs = new ArrayList<>();
        if (!s.isPlatform()) {
            orgWhere.append(" AND o.id = ?");
            orgArgs.add(orgIdLong(s));
        }

        // 分页在「组织」维度（每组织恒 4 行类型）：先取本页组织，再 CROSS JOIN 四类型。
        Long orgTotal = jdbc.queryForObject(
                "SELECT count(*) FROM org o" + orgWhere, Long.class, orgArgs.toArray());
        List<Object> pageArgs = new ArrayList<>(orgArgs);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);

        String sql = "WITH o AS (SELECT o.id, o.name, o.type FROM org o" + orgWhere
                + " ORDER BY o.id LIMIT ? OFFSET ?),"
                // v1.20.0：LEGAL 停用（法律文书不计费）→ 额度总览只出 STT/SMS/EVIDENCE 三类
                + " t(type) AS (VALUES ('STT'),('SMS'),('EVIDENCE')),"
                // 本月/上月用量（FILTER 聚合一次带出）
                + " u AS (SELECT org_id, type,"
                + "   COALESCE(SUM(qty) FILTER (WHERE occurred_at >= date_trunc('month', now())), 0) AS m0,"
                + "   COALESCE(SUM(qty) FILTER (WHERE occurred_at >= date_trunc('month', now()) - interval '1 month'"
                + "                              AND occurred_at <  date_trunc('month', now())), 0) AS m1"
                + "   FROM billing_usage"
                + "   WHERE occurred_at >= date_trunc('month', now()) - interval '1 month'"
                + "   GROUP BY org_id, type)"
                + " SELECT o.id, o.name, o.type AS org_type, t.type AS btype,"
                + "   COALESCE(b.balance, 0) AS balance, COALESCE(u.m0, 0) AS m0, COALESCE(u.m1, 0) AS m1"
                + " FROM o CROSS JOIN t"
                + " LEFT JOIN org_balance b ON b.org_id = o.id AND b.type = t.type"
                + " LEFT JOIN u ON u.org_id = o.id AND u.type = t.type"
                + " ORDER BY o.id, t.type";

        List<OrgQuotaDto> items = jdbc.query(sql, (rs, i) -> {
            String orgType = rs.getString("org_type");
            String btype = rs.getString("btype");
            return new OrgQuotaDto(
                    String.valueOf(rs.getLong("id")), rs.getString("name"), orgType,
                    btype, com.youzheng.huicui.common.BillingUnits.of(btype),
                    doubleOrNull(rs, "balance"), doubleOrNull(rs, "m0"), doubleOrNull(rs, "m1"),
                    com.youzheng.huicui.common.BillingUnits.rechargeable(orgType, btype));
        }, pageArgs.toArray());

        // total 以「行」计（组织数 × 3 类型；v1.20.0 LEGAL 停用），与 items 口径一致。
        long total = (orgTotal == null ? 0 : orgTotal) * 3;
        return Page.of(items, pg, total);
    }

    // ── [11c] getUsageSummary  GET /billing/usage/summary ────────────────────
    // 按 组织×类型×时间桶 聚合用量（v1.19.0）：groupBy=month|day（非法→422，fmt 走白名单不拼串）。
    // x-data-scope=range：非平台传别人 orgId 与 scope 叠加互斥 → 空集（零泄漏，不抛 403 制造 fuzz 噪声）。
    @GetMapping("/billing/usage/summary")
    public Page<UsageSummaryRowDto> getUsageSummary(
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String orgId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        // 白名单选定 to_char 格式（绝不把入参拼进 SQL）。缺省 month；非法 → 422。
        String g = (groupBy == null || groupBy.isBlank()) ? "month" : groupBy.trim();
        String fmt;
        if ("month".equals(g)) fmt = "YYYY-MM";
        else if ("day".equals(g)) fmt = "YYYY-MM-DD";
        else throw new ApiException(BizError.VALIDATION_422, "groupBy 非法（仅 month/day）");

        // args 只含 WHERE 的占位符参数；fmt 由两条 SQL 各自按自己的占位符位置拼入
        // （list：to_char 在 SELECT 里排第 1；count：to_char 只出现在 GROUP BY，排最后）。
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        Long orgFilter = parseOptionalLong(orgId);
        if (orgFilter != null) {
            where.append(" AND bu.org_id = ?");
            args.add(orgFilter);
        }
        if (type != null && !type.isBlank()) {
            validateBillingType(type);
            where.append(" AND bu.type = ?");
            args.add(type.trim());
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND bu.occurred_at >= ?::timestamptz");
            args.add(from.trim());
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND bu.occurred_at < (?::timestamptz + interval '1 day')");
            args.add(to.trim());
        }
        appendRangeScope(s, where, args, "bu.org_id");

        String base = "FROM billing_usage bu JOIN org o ON o.id = bu.org_id" + where;
        // count：WHERE 占位符在前，GROUP BY 的 to_char(fmt) 在后。
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT 1 " + base
                        + " GROUP BY to_char(bu.occurred_at, ?), bu.org_id, o.name, bu.type) t",
                Long.class, appendArg(args, fmt));

        // list：SELECT 的 to_char(fmt) 在最前，其后是 WHERE 占位符，最后分页。
        List<Object> pageArgs = new ArrayList<>();
        pageArgs.add(fmt);
        pageArgs.addAll(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<UsageSummaryRowDto> items = jdbc.query(
                "SELECT to_char(bu.occurred_at, ?) AS bucket, bu.org_id, o.name AS org_name, bu.type,"
                        + " SUM(bu.qty) AS qty, COUNT(*) AS cnt " + base
                        + " GROUP BY 1, bu.org_id, o.name, bu.type"
                        + " ORDER BY 1 DESC, bu.org_id, bu.type LIMIT ? OFFSET ?",
                (rs, i) -> {
                    String btype = rs.getString("type");
                    return new UsageSummaryRowDto(
                            rs.getString("bucket"),
                            String.valueOf(rs.getLong("org_id")),
                            rs.getString("org_name"),
                            btype,
                            com.youzheng.huicui.common.BillingUnits.of(btype),
                            doubleOrNull(rs, "qty"),
                            rs.getInt("cnt"));
                }, pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

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

        // v1.19.0：余额权威源=org_balance；BalanceService.credit 内部行锁(upsert-lock)串行化本 org×type
        // 读-改-写（取代 pg_advisory_xact_lock 的 (int)orgId 截断+hashCode 碰撞），并同步写 recharge_log 流水。
        long operatedBy = actorIdOrThrow(s);
        // ref 落真值（修既有违约：契约 RechargeLog.ref 非空，而此前恒写 NULL）。
        String ref = "RC-" + java.time.LocalDate.now().toString().replace("-", "") + "-" + orgId;
        BigDecimal newBalance = balance.credit(orgId, type, qty, ref, note, operatedBy);

        // 平台充值留痕（BR-M9-06a）：after_snap={type,qty,balance}。
        auditRecharge(s, orgId, type, qty, newBalance, note);

        return Map.of("ok", true, "type", type, "balance", newBalance);
    }

    // ════════════════════════════ scope ══════════════════════════════════════

    /** x-data-scope=range（裸 org_id 列裁剪，不经 project）：平台不限；非平台 AND org_id=s.orgId。 */
    private void appendRangeScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        appendRangeScope(s, where, args, "org_id");
    }

    /** join 后 org_id 列可能歧义(project 也有 org_id)——传入限定列名(如 bu.org_id)。 */
    private void appendRangeScope(CurrentSubject s, StringBuilder where, List<Object> args, String orgCol) {
        if (s.isPlatform()) return;                          // 平台全量
        where.append(" AND ").append(orgCol).append(" = ?");
        args.add(orgIdLong(s));
    }

    // ════════════════════════════ recharge 校验 ══════════════════════════════

    /** org×type 矩阵（BR-M9-07/08/10）：委托 BillingUnits.rechargeable（v1.19.0 唯一真源，同源下发前端）。违反→422。 */
    private void assertOrgTypeMatrix(String orgType, String type) {
        if (!com.youzheng.huicui.common.BillingUnits.rechargeable(orgType, type)) {
            throw new ApiException(BizError.VALIDATION_422,
                    TYPE_SMS.equals(type) ? "SMS 短信额度仅物业可充值" : "STT 分钟额度仅物业/服务商可充值");
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
                rs.getString("owner_name"),
                rs.getString("room"),
                rs.getString("project_name"),
                rs.getString("batch_no"),
                ts(rs.getTimestamp("occurred_at")),
                idOrNull(rs, "org_id"),
                rs.getString("org_name"));
    }

    /** recharge_log 行 → RechargeLogM9Dto。delta/balance NUMERIC→Double（用量单位非金额）。 */
    private static RechargeLogM9Dto mapRechargeLog(ResultSet rs, int i) throws SQLException {
        return new RechargeLogM9Dto(
                String.valueOf(rs.getLong("id")),
                rs.getString("type"),
                doubleOrNull(rs, "delta"),
                doubleOrNull(rs, "balance"),
                rs.getString("ref"),
                ts(rs.getTimestamp("tm")),
                idOrNull(rs, "org_id"),
                rs.getString("org_name"),
                rs.getString("note"),
                rs.getString("operated_by_name"));
    }

    // ════════════════════════════ 入参解析（非法→422 / org→404）═══════════════

    /** 可选 long 入参：空→null；非数→422（不 5xx）。 */
    private static Long parseOptionalLong(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(BizError.VALIDATION_422, "orgId 非法");
        }
    }

    /** count 查询的参数序列：where 的 args + GROUP BY 里再用一次 fmt（顺序须与 SQL 占位符一致）。 */
    private static Object[] appendArg(List<Object> args, Object extra) {
        List<Object> a = new ArrayList<>(args);
        a.add(extra);
        return a.toArray();
    }

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
