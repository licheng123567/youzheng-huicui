package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.BillingUsageDto;
import com.youzheng.huicui.web.dto.ReportDataDto;
import com.youzheng.huicui.web.dto.ReportKpiDto;
import com.youzheng.huicui.web.dto.ReportRowDto;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M10 经营报表（横切层范式 + scaffold 共享助手）。tags=reports。类名带 M10 后缀，仅承载本模块端点。
 *
 * 端点（基路径 /v1 由 server.servlet.context-path 提供，方法注解写裸路径）：
 *   GET  /reports/operation  getOperationReport —— 经营报表聚合（无 x-permission，三角色靠 scope 各看本口径），ReportData。
 *   POST /reports/export     exportReport       —— 报表导出（x-permission=report.export，仅 SA/SE）+ 留痕 BR-M10-08，202 Accepted。
 *
 * x-data-scope=range（双线/范围聚合，复用 CasesM2Controller.appendRangeScope 三分支口径）：
 *   平台(PLATFORM)   → 全量；
 *   物业(PROPERTY)   → 本物业项目口径 p.org_id = 本组织；
 *   服务商(PROVIDER) → 本商案件口径 c.provider_id = 本组织（案件级承接，对齐案件可见性收口）。
 *
 * 设计铁律：
 *   - getOperationReport 纯聚合查询（无写），绝不 5xx；空结果返空数组合法；非法 dimension 兜底 batch。
 *   - capabilityUsage 只量不金额（BR-M10-01/US-M10-02）：FROM recharge_log GROUP BY type，绝不下钻金额。
 *   - 回款聚合复用 repay_line.reversed=false 过滤（同 M4 sumActiveRepay 口径）。
 *   - 金额列 *_cents 原样以「分」(Long) 返回，对齐契约 Money=integer 分，不转元；Rate 为 0-1 分数。
 *
 * 列名严格对齐 V1/V2 DDL：表名 "case" 双引号；project(org_id/name)、batch(provider_id/no)、
 *   repay_line(case_id/amount_cents/reversed)、recharge_log(org_id/type/delta/tm)、audit_log。
 */
@RestController
public class ReportsM10Controller {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final Set<String> DIMENSIONS = Set.of("project", "batch", "month");
    private static final Set<String> FORMATS = Set.of("xlsx", "csv");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReportsM10Controller(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ── [1] GET /reports/operation ───────────────────────────────────────────
    // 无 x-permission（三角色各看本口径，靠 range scope 裁剪可见数据）。
    @GetMapping("/reports/operation")
    public ReportDataDto getOperationReport(
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) String month) {
        CurrentSubject s = SubjectContext.get();

        // 非法 dimension 兜底 batch（缺省亦 batch）；不抛 422 以保证报表口永不报错。
        String dim = (dimension != null && DIMENSIONS.contains(dimension)) ? dimension : "batch";

        // dimKey/dimName 表达式按维度切换（列名对齐 DDL）。
        String dimKeyExpr;
        String dimNameExpr;
        switch (dim) {
            case "project" -> { dimKeyExpr = "p.id"; dimNameExpr = "p.name"; }
            case "month" -> {
                dimKeyExpr = "to_char(c.created_at, 'YYYY-MM')";
                dimNameExpr = "to_char(c.created_at, 'YYYY-MM')";
            }
            default -> { dimKeyExpr = "b.id"; dimNameExpr = "b.no"; }   // batch
        }

        // WHERE：range scope + 可选 month 过滤（按 case.created_at 月份）。
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendRangeScope(s, where, args);
        if (month != null && !month.isBlank()) {
            where.append(" AND to_char(c.created_at, 'YYYY-MM') = ?");
            args.add(month.trim());
        }

        // rows：分组聚合。LEFT JOIN repay_line(reversed=false) 防止无回款案件被过滤。
        String rowsSql = "SELECT " + dimKeyExpr + " AS dim_key, " + dimNameExpr + " AS dim_name,"
                + " COALESCE(SUM(c.due_cents), 0) AS due_cents,"
                + " COALESCE(SUM(r.amount_cents), 0) AS repay_cents,"
                + " COUNT(DISTINCT c.id) AS case_count"
                + " FROM \"case\" c"
                + " JOIN batch b ON b.id = c.batch_id"
                + " JOIN project p ON p.id = c.project_id"
                + " LEFT JOIN repay_line r ON r.case_id = c.id AND r.reversed = false"
                + where
                + " GROUP BY " + dimKeyExpr + ", " + dimNameExpr
                + " ORDER BY due_cents DESC";

        List<ReportRowDto> rows = jdbc.query(rowsSql, rowMapper(), args.toArray());

        // kpis：从 rows 汇总（避免二次扫库；DISTINCT case 计数在分组下天然不重复跨组）。
        long totalDue = 0L;
        long totalRepay = 0L;
        long totalCases = 0L;
        for (ReportRowDto row : rows) {
            totalDue += row.dueCents() == null ? 0 : row.dueCents();
            totalRepay += row.repayCents() == null ? 0 : row.repayCents();
            totalCases += row.caseCount() == null ? 0 : row.caseCount();
        }
        List<ReportKpiDto> kpis = new ArrayList<>();
        kpis.add(ReportKpiDto.money("应收总额", totalDue));
        kpis.add(ReportKpiDto.money("回款总额", totalRepay));
        kpis.add(ReportKpiDto.rate("回款率", rate(totalRepay, totalDue)));
        kpis.add(ReportKpiDto.count("案件数", totalCases));

        // capabilityUsage：能力用量只量不金额（BR-M10-01/US-M10-02）。
        List<BillingUsageDto> capabilityUsage = loadCapabilityUsage(s);

        return new ReportDataDto(scopeLabel(s), kpis, rows, capabilityUsage);
    }

    // ── [2] POST /reports/export ─────────────────────────────────────────────
    // x-permission=report.export（仅 SA/SE 平台；PL/PC/VL 无→403，由 PermissionInterceptor 兜底）。
    // 地基期占位：返回 taskId（UUID），downloadUrl=null（异步未就绪）；写 audit_log 留痕 BR-M10-08。
    @PostMapping("/reports/export")
    @RequirePermission("report.export")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> exportReport(@RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();

        String report = str(body, "report");
        String format = str(body, "format");
        // format 非 xlsx/csv→兜底 xlsx（避免 5xx；契约允许 422，此处优雅兜底）。
        String fmt = (format != null && FORMATS.contains(format)) ? format : "xlsx";

        String taskId = UUID.randomUUID().toString();
        // BR-M10-08 留痕：写 audit_log（action=REPORT_EXPORT, actor=accountId, detail={report,format,scope}）。
        audit(s, taskId, report, fmt);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", taskId);
        resp.put("downloadUrl", null);   // 异步未就绪
        return resp;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** x-data-scope=range 追加到 WHERE（含前导 AND）。平台不限；物业按 p.org_id；服务商按 c.provider_id（案件级承接归属）。 */
    private void appendRangeScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        com.youzheng.huicui.common.DataScope.appendRange(
                s, where, args, "c.provider_id", "p.org_id", "p.area", "c.project_id", "c.batch_id");
    }

    /**
     * 能力用量聚合：FROM recharge_log GROUP BY type，只量不金额。
     * 用量(qty) = 累计扣减量 = SUM(-delta) WHERE delta < 0（delta<0=扣减/消耗）。
     * range scope：物业/服务商按 org_id 归属（recharge_log.org_id）；平台全量。
     * caseId 恒 null（报表口径不下钻到案）；occurredAt 取该类型最近一次流水 tm。
     */
    private List<BillingUsageDto> loadCapabilityUsage(CurrentSubject s) {
        StringBuilder where = new StringBuilder(" WHERE delta < 0");
        List<Object> args = new ArrayList<>();
        if (!s.isPlatform()) {
            where.append(" AND org_id = ?");
            args.add(orgIdLong(s));
        }
        String sql = "SELECT type, COALESCE(SUM(-delta), 0) AS qty, MAX(tm) AS last_tm"
                + " FROM recharge_log" + where
                + " GROUP BY type ORDER BY type";
        return jdbc.query(sql, (rs, i) -> {
            String type = rs.getString("type");
            double qty = rs.getDouble("qty");
            Timestamp last = rs.getTimestamp("last_tm");
            return new BillingUsageDto(
                    type,                       // id：以聚合键 type 作稳定标识（无明细行 id）
                    type,
                    qty,
                    unitOf(type),
                    null,                       // caseId 恒 null：不下钻到案
                    last == null ? null : ISO.format(last.toInstant()));
        }, args.toArray());
    }

    /** BillingTypeEnum → 用量单位（BillingUsage.unit）。 */
    private static String unitOf(String type) {
        if (type == null) return "次";
        return switch (type) {
            case "STT" -> "分钟";
            case "SMS" -> "条";
            case "LEGAL" -> "件";
            default -> "次";                    // EVIDENCE 及未知
        };
    }

    private org.springframework.jdbc.core.RowMapper<ReportRowDto> rowMapper() {
        return (rs, i) -> {
            long due = rs.getLong("due_cents");
            long repay = rs.getLong("repay_cents");
            long cases = rs.getLong("case_count");
            return new ReportRowDto(
                    str(rs, "dim_key"),
                    str(rs, "dim_name"),
                    due,
                    repay,
                    rate(repay, due),
                    cases);
        };
    }

    /** 回款率：0 分母→0；否则 repay/due（0-1 分数）。 */
    private static double rate(long repay, long due) {
        if (due <= 0) return 0d;
        return (double) repay / (double) due;
    }

    /** scope 标记：平台 PLATFORM_ALL；服务商 PROVIDER:{orgId}；物业 PROPERTY:{orgId}。 */
    private static String scopeLabel(CurrentSubject s) {
        if (s.isPlatform()) return "PLATFORM_ALL";
        if ("PROVIDER".equals(s.orgType())) return "PROVIDER:" + s.orgId();
        return "PROPERTY:" + s.orgId();
    }

    /** BR-M10-08 导出留痕：写 audit_log（失败不阻断 202 受理；列名兜底）。 */
    private void audit(CurrentSubject s, String taskId, String report, String format) {
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("report", report);
            detail.put("format", format);
            detail.put("scope", scopeLabel(s));
            jdbc.update(
                    "INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope, after_snap, trace_id)"
                            + " VALUES (?, ?, 'REPORT_EXPORT', ?, 'report', ?, ?, ?::jsonb, ?)",
                    actorId(s), nz(s.name()),
                    "report=" + nz(report) + " format=" + format,
                    taskId, s.orgType(),
                    writeJson(detail), MDC.get("traceId"));
        } catch (Exception ignore) {
            /* 审计失败不阻断主流程（地基期占位导出）；列名兜底。 */
        }
    }

    // ── 低级转换工具 ──────────────────────────────────────────────────────────

    private static String str(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : String.valueOf(v);
    }

    private static String str(Map<String, Object> b, String k) {
        if (b == null) return null;
        Object v = b.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private Long actorId(CurrentSubject s) {
        try {
            return s.accountId() == null ? null : Long.valueOf(s.accountId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Long orgIdLong(CurrentSubject s) {
        try {
            return s.orgId() == null ? null : Long.valueOf(s.orgId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
