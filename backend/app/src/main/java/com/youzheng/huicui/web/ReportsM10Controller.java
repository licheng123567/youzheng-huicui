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
    // v1.25.0 +property：平台要「按物业公司聚合 → 向下穿透」。此前平台只有一张批次平表，
    // 看不出「哪家物业贡献多少应收/回款」，也无法从某家物业钻到它的项目/批次。
    private static final Set<String> DIMENSIONS = Set.of("project", "batch", "month", "collector", "provider", "property");
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
            @RequestParam(required = false) String month,
            // v1.25.0 穿透过滤：物业/服务商/项目三把筛子，可与任意 dimension 组合。
            // 典型链路：property 维 → 点某物业(propertyId) → project 维 → 点某项目(projectId) → batch 维。
            @RequestParam(required = false) String propertyId,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) String projectId) {
        CurrentSubject s = SubjectContext.get();

        // 非法 dimension 兜底 batch（缺省亦 batch）；不抛 422 以保证报表口永不报错。
        String dim = (dimension != null && DIMENSIONS.contains(dimension)) ? dimension : "batch";

        // dimKey/dimName 表达式按维度切换（列名对齐 DDL）。
        String dimKeyExpr;
        String dimNameExpr;
        String extraJoin = "";
        boolean collectorDim = false;
        switch (dim) {
            case "project" -> { dimKeyExpr = "p.id"; dimNameExpr = "p.name"; }
            case "property" -> {
                // 按物业公司（项目的所属组织）聚合。org 必 JOIN，否则只有 id 没有名字。
                dimKeyExpr = "p.org_id"; dimNameExpr = "po.name";
                extraJoin = " LEFT JOIN org po ON po.id = p.org_id";
            }
            case "month" -> {
                dimKeyExpr = "to_char(c.created_at, 'YYYY-MM')";
                dimNameExpr = "to_char(c.created_at, 'YYYY-MM')";
            }
            case "collector" -> {
                // 按持有催收员聚合（服务商经营报表「催收员产能」）：holder 私海口径。
                dimKeyExpr = "c.holder_id"; dimNameExpr = "ha.name";
                extraJoin = " LEFT JOIN account ha ON ha.id = c.holder_id";
                collectorDim = true;
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
        if (collectorDim) {
            where.append(" AND c.holder_id IS NOT NULL");   // 仅统计已被持有(私海)的案件
        }
        // v1.25.0 穿透过滤（与 range scope 叠加，不放宽：非平台传别人家的 id 只会得到空集）。
        Long propOrg = parseIdOrNull(propertyId);
        if (propOrg != null) {
            where.append(" AND p.org_id = ?");
            args.add(propOrg);
        }
        Long proj = parseIdOrNull(projectId);
        if (proj != null) {
            where.append(" AND c.project_id = ?");
            args.add(proj);
        }
        // providerId 过滤**不进公共 where**：服务商的口径天生是双侧的——
        //   在催盘子(应收/案件数)认**当前归属** c.provider_id；催回的钱认 **V914 到账快照** provider_id_at_repay。
        // 若把 c.provider_id=? 一把塞进公共 where，那些「已被结项、当前归属已清空」的案件会被整条滤掉，
        // 连带它们当年被这家催回的钱也一起消失 → 点开某服务商(已收 19,700)钻下去只剩 10,100，对不上账。
        // 这是实测发现的：捷信被结项过。故 providerId 存在时走 dualSideReport（与 provider 维同一套 FULL OUTER JOIN）。
        Long prov = parseIdOrNull(providerId);

        // provider 维（v1.17.0·服务商考核）：案件侧按当前归属 c.provider_id、回款侧按到账快照
        //   rl.provider_id_at_repay（V914·结项/再派不漂移）分键聚合，FULL OUTER JOIN 合并——
        //   与其他维「案件+回款同键」结构不同，单独分支。dim_key NULL 行=未派单/无归属。
        if ("provider".equals(dim)) {
            return providerDimReport(s, where.toString(), args);
        }
        // 按某个服务商穿透（provider 维点开后的下一层）：任意 dimension 都走双侧口径，
        // 保证「聚合行的数」与「钻进去的合计」严丝合缝对得上。
        if (prov != null) {
            return dualSideReport(s, dimKeyExpr, dimNameExpr, extraJoin, where.toString(), args, prov);
        }

        // rows：分组聚合。LEFT JOIN repay_line(reversed=false) 防止无回款案件被过滤。
        String rowsSql = "SELECT " + dimKeyExpr + " AS dim_key, " + dimNameExpr + " AS dim_name,"
                + " COALESCE(SUM(c.due_cents), 0) AS due_cents,"
                + " COALESCE(SUM(r.amount_cents), 0) AS repay_cents,"
                + " COUNT(DISTINCT c.id) AS case_count"
                + " FROM \"case\" c"
                + " JOIN batch b ON b.id = c.batch_id"
                + " JOIN project p ON p.id = c.project_id"
                + extraJoin
                + repayJoin(null)
                + where
                + " GROUP BY " + dimKeyExpr + ", " + dimNameExpr
                + " ORDER BY due_cents DESC";

        List<ReportRowDto> rows = jdbc.query(rowsSql, rowMapper(), args.toArray());
        rows = mergeCommission(rows, commissionByDim(dimKeyExpr, extraJoin, where.toString(), args, null));
        return assemble(s, rows);   // KPI 从 rows 汇总；capabilityUsage 只量不金额（BR-M10-01/US-M10-02）
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
     * 回款子查询。**providerId 过滤时按 V914 到账快照 provider_id_at_repay 裁剪**，而不是按案件当前归属——
     * 否则「批次结项换了服务商」后，前一家催回的钱会被算到后一家头上（案件的 provider_id 已经改了）。
     * 与 provider 维的口径保持一致：钱认快照，在催盘子认当前。
     */
    private static String repayJoin(Long providerId) {
        String inner = "SELECT case_id, SUM(amount_cents) AS amount_cents FROM repay_line"
                + " WHERE reversed = false" + (providerId != null ? " AND provider_id_at_repay = ?" : "")
                + " GROUP BY case_id";
        return " LEFT JOIN (" + inner + ") r ON r.case_id = c.id";
    }

    /** id 解析：非数字/空 → null（不抛 422，报表口永不报错——非法筛子等于不筛）。 */
    private static Long parseIdOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 服务商穿透（v1.25.0）：**盘子认当前、钱认快照**——与 provider 维完全同一套口径，只是分组键换成
     * 调用方要的维度（批次/项目/月…）。这样「provider 维那一行的数」与「点进去之后的合计」必然对得上。
     *
     * <p>dim_key 只在一侧出现是合法的：某批次已被结项(当前无案件归属) → 只在回款侧出现，
     * 应收/案件数为 0 而已收 &gt; 0。那正是「这家催回了钱但盘子已经收走」的真实写照，不是脏数据。
     */
    private ReportDataDto dualSideReport(CurrentSubject s, String dimKeyExpr, String dimNameExpr, String extraJoin,
                                         String where, List<Object> args, long providerId) {
        String caseSide = "SELECT " + dimKeyExpr + " AS dim_key, " + dimNameExpr + " AS dim_name,"
                + " COALESCE(SUM(c.due_cents),0) AS due_cents, COUNT(DISTINCT c.id) AS case_count"
                + " FROM \"case\" c JOIN batch b ON b.id = c.batch_id JOIN project p ON p.id = c.project_id"
                + extraJoin + where + " AND c.provider_id = ?"
                + " GROUP BY " + dimKeyExpr + ", " + dimNameExpr;
        String repaySide = "SELECT " + dimKeyExpr + " AS dim_key, " + dimNameExpr + " AS dim_name,"
                + " COALESCE(SUM(rl.amount_cents),0) AS repay_cents"
                + " FROM repay_line rl JOIN \"case\" c ON c.id = rl.case_id"
                + " JOIN batch b ON b.id = c.batch_id JOIN project p ON p.id = c.project_id"
                + extraJoin + where + " AND rl.reversed = false AND rl.provider_id_at_repay = ?"
                + " GROUP BY " + dimKeyExpr + ", " + dimNameExpr;
        String sql = "SELECT COALESCE(cs.dim_key, rp.dim_key) AS dim_key,"
                + " COALESCE(cs.dim_name, rp.dim_name) AS dim_name,"
                + " COALESCE(cs.due_cents, 0) AS due_cents,"
                + " COALESCE(rp.repay_cents, 0) AS repay_cents,"
                + " COALESCE(cs.case_count, 0) AS case_count"
                + " FROM (" + caseSide + ") cs"
                + " FULL OUTER JOIN (" + repaySide + ") rp ON rp.dim_key = cs.dim_key"
                + " ORDER BY due_cents DESC, repay_cents DESC";

        List<Object> all = new ArrayList<>(args);
        all.add(providerId);          // caseSide 的 ?
        all.addAll(args);
        all.add(providerId);          // repaySide 的 ?
        List<ReportRowDto> rows = jdbc.query(sql, rowMapper(), all.toArray());
        // 佣金也认快照（钱是谁催回的，佣金就归谁那条线）——与上面的双侧口径保持一致。
        rows = mergeCommission(rows, commissionByDim(dimKeyExpr, extraJoin, where, args, providerId));
        return assemble(s, rows);
    }

    /** rows → KPI + 报表体。三处（默认维/provider 维/穿透）本来各抄一遍，抽出来免得口径漂移。 */
    private ReportDataDto assemble(CurrentSubject s, List<ReportRowDto> rows) {
        long totalDue = 0L, totalRepay = 0L, totalCases = 0L;
        long cInDue = 0L, cInSettled = 0L, cOutDue = 0L, cOutSettled = 0L;
        for (ReportRowDto row : rows) {
            totalDue += row.dueCents() == null ? 0 : row.dueCents();
            totalRepay += row.repayCents() == null ? 0 : row.repayCents();
            totalCases += row.caseCount() == null ? 0 : row.caseCount();
            cInDue += nz(row.commInDueCents());
            cInSettled += nz(row.commInSettledCents());
            cOutDue += nz(row.commOutDueCents());
            cOutSettled += nz(row.commOutSettledCents());
        }
        List<ReportKpiDto> kpis = new ArrayList<>();
        kpis.add(ReportKpiDto.money("应收总额", totalDue));
        kpis.add(ReportKpiDto.money("回款总额", totalRepay));
        kpis.add(ReportKpiDto.rate("回款率", rate(totalRepay, totalDue)));
        kpis.add(ReportKpiDto.count("案件数", totalCases));
        // 佣金双线 KPI（与 /recon/rollup-dual 同口径）：收佣=物业付给平台，付佣=平台付给服务商。
        kpis.add(ReportKpiDto.money("待收佣金", cInDue - cInSettled));
        kpis.add(ReportKpiDto.money("待付佣金", cOutDue - cOutSettled));
        kpis.add(ReportKpiDto.money("佣金毛利", cInDue - cOutDue));
        return new ReportDataDto(scopeLabel(s), kpis, rows, loadCapabilityUsage(s));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    /**
     * provider 维报表（v1.17.0）：案件侧(在催盘子)按当前 c.provider_id、回款侧按 V914 到账快照
     * rl.provider_id_at_repay，FULL OUTER JOIN 后取 org 名。同一 where(c/p/b 别名)施加两侧（args 双份）。
     * dim_key NULL=未派单/无归属；回款率=快照回款/当前应收（分子快照分母当前盘子，注意口径混合——
     * 服务商被结项后其催回的钱仍计入该商，而在催盘子已清零 → 回款率可能 >100% 或分母 0，rate() 兜 0。
     */
    private ReportDataDto providerDimReport(CurrentSubject s, String where, List<Object> args) {
        String caseSide = "SELECT c.provider_id AS dim_key,"
                + " COALESCE(SUM(c.due_cents),0) AS due_cents, COUNT(*) AS case_count"
                + " FROM \"case\" c JOIN batch b ON b.id = c.batch_id JOIN project p ON p.id = c.project_id"
                + where + " GROUP BY c.provider_id";
        String repaySide = "SELECT rl.provider_id_at_repay AS dim_key,"
                + " COALESCE(SUM(rl.amount_cents),0) AS repay_cents"
                + " FROM repay_line rl JOIN \"case\" c ON c.id = rl.case_id"
                + " JOIN batch b ON b.id = c.batch_id JOIN project p ON p.id = c.project_id"
                + where + " AND rl.reversed = false GROUP BY rl.provider_id_at_repay";
        String sql = "SELECT COALESCE(cs.dim_key, rp.dim_key) AS dim_key,"
                + " COALESCE(o.name, '未派单') AS dim_name,"
                + " COALESCE(cs.due_cents, 0) AS due_cents,"
                + " COALESCE(rp.repay_cents, 0) AS repay_cents,"
                + " COALESCE(cs.case_count, 0) AS case_count"
                + " FROM (" + caseSide + ") cs"
                + " FULL OUTER JOIN (" + repaySide + ") rp ON rp.dim_key = cs.dim_key"
                + " LEFT JOIN org o ON o.id = COALESCE(cs.dim_key, rp.dim_key)"
                + " ORDER BY due_cents DESC";
        List<Object> both = new ArrayList<>(args);
        both.addAll(args);
        List<ReportRowDto> rows = jdbc.query(sql, rowMapper(), both.toArray());
        // provider 维的分组键就是「钱是谁催回的」——佣金按 rl.provider_id_at_repay 分组，同一口径。
        rows = mergeCommission(rows, commissionByDim("rl.provider_id_at_repay", "", where, args, null));
        return assemble(s, rows);
    }


    /**
     * 佣金双线按维度聚合（v1.25.1）。**口径必须与 /recon/rollup-dual 逐字一致**，否则同一笔钱在
     * 「结算对账」和「经营报表」上会给出两个数，那是最伤信任的一类 bug：
     *   · 每笔回款 × **该批次的比率**（IN=b.comm_in_rate，OUT=b.pay_out_rate）逐笔 round 后求和
     *     （不是「总回款 × 比率」——批次比率不同，先乘后加与先加后乘不等）；
     *   · 已收/已付看 repay_line 的 settled_in / settled_out（V929 双线各自独立结清）；
     *   · 只计未冲正 reversed = false。
     *
     * <p>**未设 pay_out_rate 的批次**：应付按 0 计入（COALESCE），但同时统计这类批次数并透出——
     * 否则一个漏配付佣比例的批次会把平台真实欠款藏起来，报表上看着「应付很少」，实际是没算。
     *
     * @param provider 非空时钱认 V914 到账快照（provider_id_at_repay），与 dualSideReport 同口径
     */
    private Map<String, long[]> commissionByDim(String dimKeyExpr, String extraJoin,
                                                String where, List<Object> args, Long provider) {
        String sql = "SELECT " + dimKeyExpr + " AS dim_key,"
                + " COALESCE(SUM(round(rl.amount_cents * COALESCE(b.comm_in_rate, 0)))::bigint, 0) AS in_due,"
                + " COALESCE(SUM(CASE WHEN rl.settled_in"
                + "        THEN round(rl.amount_cents * COALESCE(b.comm_in_rate, 0)) ELSE 0 END)::bigint, 0) AS in_settled,"
                + " COALESCE(SUM(round(rl.amount_cents * COALESCE(b.pay_out_rate, 0)))::bigint, 0) AS out_due,"
                + " COALESCE(SUM(CASE WHEN rl.settled_out"
                + "        THEN round(rl.amount_cents * COALESCE(b.pay_out_rate, 0)) ELSE 0 END)::bigint, 0) AS out_settled,"
                + " COUNT(DISTINCT b.id) FILTER (WHERE b.pay_out_rate IS NULL) AS out_rate_missing"
                + " FROM repay_line rl"
                + " JOIN \"case\" c ON c.id = rl.case_id"
                + " JOIN batch b ON b.id = c.batch_id"
                + " JOIN project p ON p.id = c.project_id"
                + extraJoin
                + where + " AND rl.reversed = false"
                + (provider != null ? " AND rl.provider_id_at_repay = ?" : "")
                + " GROUP BY " + dimKeyExpr;

        List<Object> a = new ArrayList<>(args);
        if (provider != null) a.add(provider);

        Map<String, long[]> out = new java.util.HashMap<>();
        jdbc.query(sql, rs -> {
            String k = rs.getString("dim_key");
            out.put(k == null ? "" : k, new long[]{
                    rs.getLong("in_due"), rs.getLong("in_settled"),
                    rs.getLong("out_due"), rs.getLong("out_settled"),
                    rs.getLong("out_rate_missing")});
        }, a.toArray());
        return out;
    }

    /** 把佣金聚合按 dim_key 贴回行上（无回款的维度 → 六项全 0，而不是 null：0 才是「没有佣金」的正确表达）。 */
    private List<ReportRowDto> mergeCommission(List<ReportRowDto> rows, Map<String, long[]> comm) {
        List<ReportRowDto> out = new ArrayList<>(rows.size());
        for (ReportRowDto r : rows) {
            long[] c = comm.get(r.dimKey() == null ? "" : r.dimKey());
            long inDue = c == null ? 0 : c[0], inSettled = c == null ? 0 : c[1];
            long outDue = c == null ? 0 : c[2], outSettled = c == null ? 0 : c[3];
            long missing = c == null ? 0 : c[4];
            out.add(new ReportRowDto(r.dimKey(), r.dimName(), r.dueCents(), r.repayCents(), r.repayRate(),
                    r.caseCount(),
                    inDue, inSettled, inDue - inSettled,
                    outDue, outSettled, outDue - outSettled,
                    missing));
        }
        return out;
    }

    /**\n     * 能力用量聚合（v1.19.0 口径修正）：**FROM billing_usage GROUP BY type**，只量不金额。
     * 此前从 recharge_log 拿 SUM(-delta) 当用量——那是**扣减流水**不是用量真表（充值/冲正混入、
     * 且与新「额度管理」页的 billing_usage 口径打架，两个数字会越差越远）。billing_usage 才是用量 SSOT。
     * range scope：物业/服务商按 org_id；平台全量。caseId 恒 null（报表不下钻到案）；
     * occurredAt 取该类型最近一次用量时间。
     */
    private List<BillingUsageDto> loadCapabilityUsage(CurrentSubject s) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!s.isPlatform()) {
            where.append(" AND org_id = ?");
            args.add(orgIdLong(s));
        }
        String sql = "SELECT type, COALESCE(SUM(qty), 0) AS qty, MAX(occurred_at) AS last_tm"
                + " FROM billing_usage" + where
                + " GROUP BY type ORDER BY type";
        return jdbc.query(sql, (rs, i) -> {
            String type = rs.getString("type");
            double qty = rs.getDouble("qty");
            Timestamp last = rs.getTimestamp("last_tm");
            return new BillingUsageDto(
                    type,                       // id：以聚合键 type 作稳定标识（无明细行 id）
                    type,
                    qty,
                    com.youzheng.huicui.common.BillingUnits.of(type),
                    null,                       // caseId 恒 null：不下钻到案
                    null, null, null, null,     // 报表聚合口径不下钻案件穿透列（业主/房号/项目/批次）
                    last == null ? null : ISO.format(last.toInstant()),
                    null, null);                // orgId/orgName：报表按 scope 已裁剪，聚合行不带组织维度
        }, args.toArray());
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
                    cases,
                    null, null, null, null, null, null, null);   // 佣金六项由 mergeCommission 按 dim_key 回填
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
