package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色工作台 + 派单决策辅助（v1.1.0 覆盖补点）。纯读聚合，绝不 5xx；空结果返空数组合法。
 *   GET /workbench                              getWorkbench       —— BR-M4-20/20a：CO/PC=今日驾驶舱待办；管理角色=仪表盘 KPI
 *   GET /dispatch/provider-metrics              getProviderMetrics —— BR-M3-24：各服务商客观经营指标(仅陈列不评分)，platform
 *   GET /providers/{id}/collector-capacity      getCollectorCapacity —— BR-M3-23：本服务商催收员持仓余量+推荐，own-org
 * 待办仅发"有数据支撑"的类目：PROMISE_DUE/RELEASE_WARN/TICKET_RECEIPT（REDUCE_APPROVE 等枚举为前向兼容，
 * 减免审批线下不设待批队列→无数据，不杜撰）。持仓 holding=私海(PRIVATE)持有案件数。
 */
@RestController
public class WorkbenchDispatchController {

    private final JdbcTemplate jdbc;

    public WorkbenchDispatchController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Kpi(String label, int value, String filterKey) {}
    public record Todo(String category, String urgency, String caseId, String title, String deadline, String refType, String refId) {}
    public record WorkbenchData(String role, String layout, List<Kpi> kpis, List<Todo> todos) {}
    public record ProviderMetric(String providerId, String providerName, int activeCases, int collectorCount, double avgHolding, Double recentRepayRate) {}
    public record CollectorCapacity(String collectorId, String name, int holding, int remaining, boolean recommended) {}

    // ── GET /workbench ──────────────────────────────────────────────────────
    @GetMapping("/workbench")
    public WorkbenchData getWorkbench() {
        CurrentSubject s = SubjectContext.get();
        String role = s.role() == null ? "" : s.role();
        boolean cockpit = "CO".equals(role) || "PC".equals(role);
        List<Todo> todos = new ArrayList<>();
        List<Kpi> kpis = new ArrayList<>();

        if ("CO".equals(role)) {
            Long me = parseLong(s.accountId());
            if (me != null) {
                // PROMISE_DUE：本人持有案件的未兑现分期，7 日内到期（≤3 日 HIGH）
                todos.addAll(jdbc.query(
                    "SELECT c.id AS case_id, c.owner_name, pi.due_date, pi.id AS inst_id,"
                        + " (pi.due_date <= now() + interval '3 days') AS hot"
                        + " FROM promise_installment pi JOIN promise p ON p.id = pi.promise_id"
                        + " JOIN \"case\" c ON c.id = p.case_id"
                        + " WHERE c.holder_id = ? AND pi.state = 'PENDING' AND pi.due_date <= now() + interval '7 days'"
                        + " ORDER BY pi.due_date", (rs, i) -> new Todo("PROMISE_DUE",
                            rs.getBoolean("hot") ? "HIGH" : "MED", String.valueOf(rs.getLong("case_id")),
                            "承诺到期：" + rs.getString("owner_name"), String.valueOf(rs.getObject("due_date")),
                            "promise_installment", String.valueOf(rs.getLong("inst_id"))), me));
                // RELEASE_WARN：本人持有、临近自动释放（t_collector_deadline 2 日内）
                todos.addAll(jdbc.query(
                    "SELECT id AS case_id, owner_name, t_collector_deadline FROM \"case\""
                        + " WHERE holder_id = ? AND closed_at IS NULL AND t_collector_deadline IS NOT NULL"
                        + " AND t_collector_deadline <= now() + interval '2 days'"
                        + " ORDER BY t_collector_deadline", (rs, i) -> new Todo("RELEASE_WARN", "HIGH",
                            String.valueOf(rs.getLong("case_id")), "临近自动释放：" + rs.getString("owner_name"),
                            String.valueOf(rs.getObject("t_collector_deadline")), "case", String.valueOf(rs.getLong("case_id"))), me));
                // TICKET_RECEIPT：本人持有案件、已被协调员处理（回执待看）
                todos.addAll(jdbc.query(
                    "SELECT t.id, t.case_id, c.owner_name FROM ticket t JOIN \"case\" c ON c.id = t.case_id"
                        + " WHERE c.holder_id = ? AND t.status = 'HANDLED' AND t.handled_at >= now() - interval '7 days'"
                        + " ORDER BY t.handled_at DESC", (rs, i) -> new Todo("TICKET_RECEIPT", "LOW",
                            String.valueOf(rs.getLong("case_id")), "工单回执：" + rs.getString("owner_name"),
                            null, "ticket", String.valueOf(rs.getLong("id"))), me));
            }
        } else if ("PC".equals(role)) {
            Long org = parseLong(s.orgId());
            if (org != null) {
                // TICKET_RECEIPT(PC 侧)：本物业待处理工单（to_role=PC, PENDING）
                todos.addAll(jdbc.query(
                    "SELECT t.id, t.case_id, c.owner_name, t.type FROM ticket t"
                        + " JOIN \"case\" c ON c.id = t.case_id JOIN project p ON p.id = c.project_id"
                        + " WHERE p.org_id = ? AND t.to_role = 'PC' AND t.status = 'PENDING'"
                        + " ORDER BY t.created_at", (rs, i) -> new Todo("TICKET_RECEIPT", "MED",
                            String.valueOf(rs.getLong("case_id")), "待处理工单：" + rs.getString("type"),
                            null, "ticket", String.valueOf(rs.getLong("id"))), org));
            }
        } else if ("VL".equals(role)) {
            Long org = parseLong(s.orgId());
            if (org != null) {
                // T2_RETURN_WARN：本商服务商公海案件临近退回平台公海（BR-M3-13a，向 VL 预警）
                long warn = timerSeconds("t2WarnSeconds", 86400);
                todos.addAll(jdbc.query(
                    "SELECT c.id, c.owner_name, c.t2_deadline FROM \"case\" c JOIN batch b ON b.id = c.batch_id"
                        + " WHERE b.provider_id = ? AND c.pool = 'PROVIDER_SEA' AND c.status = 'PROVIDER_SEA'"
                        + " AND c.t2_deadline IS NOT NULL AND c.t2_deadline > now() AND c.t2_deadline < now() + (? || ' seconds')::interval"
                        + " ORDER BY c.t2_deadline", (rs, i) -> new Todo("T2_RETURN_WARN", "HIGH",
                            String.valueOf(rs.getLong("id")), "即将退回平台公海：" + rs.getString("owner_name"),
                            String.valueOf(rs.getObject("t2_deadline")), "case", String.valueOf(rs.getLong("id"))), org, warn));
            }
        } else if (s.isPlatform()) {
            // T1_DISPATCH_WARN：待派单(平台公海未派)超 CFG-T1 仍未派（BR-M3-01，向平台预警）
            long t1 = timerSeconds("t1Seconds", 172800);
            todos.addAll(jdbc.query(
                "SELECT c.id, c.owner_name, b.created_at FROM \"case\" c JOIN batch b ON b.id = c.batch_id"
                    + " WHERE c.pool = 'PLATFORM_SEA' AND c.status = 'PENDING_DISPATCH' AND b.provider_id IS NULL"
                    + " AND b.created_at < now() - (? || ' seconds')::interval ORDER BY b.created_at LIMIT 50",
                (rs, i) -> new Todo("T1_DISPATCH_WARN", "MED", String.valueOf(rs.getLong("id")),
                    "待派单超时：" + rs.getString("owner_name"), null, "case", String.valueOf(rs.getLong("id"))), t1));
        }
        // KPI：按 category 汇总（可点即筛）
        kpis.add(new Kpi("待办合计", todos.size(), null));
        addCountKpi(kpis, todos, "PROMISE_DUE", "承诺到期");
        addCountKpi(kpis, todos, "RELEASE_WARN", "临近释放");
        addCountKpi(kpis, todos, "TICKET_RECEIPT", "工单回执");
        addCountKpi(kpis, todos, "T2_RETURN_WARN", "临近退回");
        addCountKpi(kpis, todos, "T1_DISPATCH_WARN", "待派超时");
        return new WorkbenchData(role, cockpit ? "cockpit" : "dashboard", kpis, todos);
    }

    /** settings TIMERS.<key> 秒值，缺省 dflt（不致 5xx）。 */
    private long timerSeconds(String key, long dflt) {
        try {
            Long v = jdbc.query("SELECT timers ->> ? AS v FROM settings WHERE domain = 'TIMERS' ORDER BY version DESC LIMIT 1",
                rs -> { if (!rs.next()) return null; String r = rs.getString("v"); try { return r == null ? null : Long.valueOf(r.trim()); } catch (NumberFormatException e) { return null; } }, key);
            return v == null ? dflt : v;
        } catch (RuntimeException e) { return dflt; }
    }

    private void addCountKpi(List<Kpi> kpis, List<Todo> todos, String cat, String label) {
        int n = (int) todos.stream().filter(t -> cat.equals(t.category())).count();
        if (n > 0) kpis.add(new Kpi(label, n, cat));
    }

    // ── GET /dispatch/provider-metrics（BR-M3-24·platform·case.dispatch）────────
    @GetMapping("/dispatch/provider-metrics")
    @RequirePermission("case.dispatch")
    public java.util.Map<String, Object> getProviderMetrics() {
        List<ProviderMetric> items = jdbc.query(
            "SELECT o.id AS pid, o.name AS pname,"
                + " (SELECT count(*) FROM \"case\" c JOIN batch b ON b.id = c.batch_id"
                + "    WHERE b.provider_id = o.id AND c.closed_at IS NULL) AS active_cases,"
                + " (SELECT count(*) FROM account a WHERE a.org_id = o.id AND a.role_template = 'CO' AND a.status = 'ACTIVE') AS collector_cnt,"
                + " (SELECT coalesce(sum(rl.amount_cents),0) FROM repay_line rl JOIN \"case\" c ON c.id = rl.case_id"
                + "    JOIN batch b ON b.id = c.batch_id WHERE b.provider_id = o.id AND rl.reversed = FALSE AND rl.paid_at >= now() - interval '30 days') AS repay30,"
                + " (SELECT coalesce(sum(c.due_cents),0) FROM \"case\" c JOIN batch b ON b.id = c.batch_id WHERE b.provider_id = o.id) AS due_total"
                + " FROM org o WHERE o.type = 'PROVIDER' ORDER BY o.id",
            (rs, i) -> {
                int active = rs.getInt("active_cases");
                int cnt = rs.getInt("collector_cnt");
                long repay30 = rs.getLong("repay30");
                long dueTotal = rs.getLong("due_total");
                double avg = cnt > 0 ? (double) active / cnt : 0.0;
                Double rate = dueTotal > 0 ? Math.round((double) repay30 / dueTotal * 10000.0) / 10000.0 : null;
                return new ProviderMetric(String.valueOf(rs.getLong("pid")), rs.getString("pname"), active, cnt, avg, rate);
            });
        return java.util.Map.of("items", items);
    }

    // ── GET /providers/{id}/collector-capacity（BR-M3-23·own-org·case.assign）───
    @GetMapping("/providers/{id}/collector-capacity")
    @RequirePermission("case.assign")
    public java.util.Map<String, Object> getCollectorCapacity(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        Long org = parseLong(id);
        if (org == null) throw new ApiException(BizError.NOT_FOUND_404, "服务商不存在");
        // own-org：非平台只能查本组织
        if (!s.isPlatform()) {
            Long myOrg = parseLong(s.orgId());
            if (myOrg == null || !myOrg.equals(org)) throw new ApiException(BizError.PERM_403, "仅可查看本服务商催收员余量");
        }
        int holdCap = holdCap();
        List<CollectorCapacity> raw = jdbc.query(
            "SELECT a.id, a.name,"
                + " (SELECT count(*) FROM \"case\" c WHERE c.holder_id = a.id AND c.pool = 'PRIVATE' AND c.closed_at IS NULL) AS holding"
                + " FROM account a WHERE a.org_id = ? AND a.role_template = 'CO' AND a.status = 'ACTIVE' ORDER BY a.id",
            (rs, i) -> {
                int holding = rs.getInt("holding");
                return new CollectorCapacity(String.valueOf(rs.getLong("id")), rs.getString("name"),
                        holding, Math.max(0, holdCap - holding), false);
            }, org);
        // 推荐：余量最大者（≥1 才推荐）
        int maxRemain = raw.stream().mapToInt(CollectorCapacity::remaining).max().orElse(0);
        List<CollectorCapacity> items = new ArrayList<>();
        boolean picked = false;
        for (CollectorCapacity c : raw) {
            boolean rec = !picked && maxRemain > 0 && c.remaining() == maxRemain;
            if (rec) picked = true;
            items.add(new CollectorCapacity(c.collectorId(), c.name(), c.holding(), c.remaining(), rec));
        }
        return java.util.Map.of("holdCap", holdCap, "items", items);
    }

    /** ROTATION 设置 holdCap（CFG-HOLDCAP）；缺则默认 50。 */
    private int holdCap() {
        try {
            Integer v = jdbc.query(
                "SELECT (rotation ->> 'holdCap')::int AS hc FROM settings WHERE domain = 'ROTATION' ORDER BY version DESC LIMIT 1",
                rs -> rs.next() ? (Integer) rs.getObject("hc") : null);
            return v != null ? v : 50;
        } catch (RuntimeException e) {
            return 50;
        }
    }

    private static Long parseLong(String v) {
        try { return v == null ? null : Long.valueOf(v.trim()); } catch (RuntimeException e) { return null; }
    }
}
