package com.youzheng.huicui.web;

import com.youzheng.huicui.dispatch.CaseStateService;
import com.youzheng.huicui.dispatch.CaseStateService.CaseSnapshot;
import com.youzheng.huicui.dispatch.CaseStateService.Transition;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批次承接段（V930·契约 v1.17.0）：结项（终止当前服务商承接·全部收回+承诺保留）+ 承接历史。
 *
 * 端点（/v1 由 context-path 提供）：
 *   POST /batches/{id}/close-engagement  closeBatchEngagement   | case.dispatch | platform | 200/403/404/409/422
 *   GET  /batches/{id}/close-preview     previewCloseEngagement | case.dispatch | platform | 200/403/404/409
 *   GET  /batches/{id}/engagements       listBatchEngagements   | case.dispatch | platform | 200/403/404
 *
 * 【结项语义（用户拍板：全部收回+承诺保留）】
 *   一次性收回该批次当前服务商名下全部在催案件回平台公海 S0：
 *     S1(待接单)/S2(服务商公海)/S3(私海进行中·清 holder) → S0；S4 开放池 provider 已空不选；终态不动。
 *   **无视 BR-M8-11 承诺暂缓**——那是 TC 到期自动释放的缓冲语义；结项是平台主动强制收回，
 *   带有效分期承诺的案件照收（preview/结果都列出作警示），承诺/跟进/activity 一概不动=完整历史随案保留，
 *   重派后新服务商可见。
 *   结项后 batch.provider_id=NULL、status='PENDING'（可重派；不启用 CLOSED——全仓无读方）。
 *   审计：逐案 case.engagement-recall（不写 case.return → 再派护栏① lastReturnedProvider 不触发，
 *   结项后重派对象含派回原商由平台自由裁量）+ 批级 batch.close-engagement。
 *
 * 【每段统计口径】期间回款 = repay_line×case：batch=段.batch AND provider_id_at_repay=段.provider
 *   AND reversed=false AND paid_at>=started_at AND paid_at<（同批同商下一段 started_at，无则不设上界）。
 *   不用 ended_at 作上界：结项后补到账的尾款按 V914 快照仍归旧商，段统计与结算口径一致。
 *   期间回款率 = 期间回款 / 期初剩余应收（批次总应收 − started_at 前全批累计已收）；分母≤0 → null。
 */
@RestController
public class BatchEngagementController {

    private final JdbcTemplate jdbc;
    private final CaseStateService caseState;

    public BatchEngagementController(JdbcTemplate jdbc, CaseStateService caseState) {
        this.jdbc = jdbc;
        this.caseState = caseState;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final Set<String> REASONS = Set.of("INCAPABLE", "COOP_TERMINATED", "PROPERTY_REQUEST", "OTHER");

    // ── [1] POST /batches/{id}/close-engagement ─────────────────────────────
    @PostMapping("/batches/{id}/close-engagement")
    @RequirePermission("case.dispatch")
    @Transactional
    public Map<String, Object> closeBatchEngagement(@PathVariable("id") String id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = requirePlatform();
        long batchId = parseId(id);
        String reason = parseReason(body);                     // 缺/非法 → 422
        String note = parseRequired(body, "note");             // 缺 → 422

        // 批次行锁 = 幂等/并发闸门：结项-结项、结项-派单串行化。
        BatchRow batch = lockBatch(batchId);                   // 不存在 → 404
        if (batch.providerId() == null) {
            throw new ApiException(BizError.STATE_409, "批次无进行中承接，无需结项（可能已结项/未派单）");
        }
        long providerId = batch.providerId();

        // 选案：批内该商名下 S1/S2/S3（ORDER BY id 定锁序防死锁）。
        //   S4 开放池 provider_id 已为 NULL、终态不在三对 (status,pool) 内、单案改派他商的案件 provider 不同——天然不选。
        List<Long> targets = jdbc.queryForList(
                "SELECT id FROM \"case\" WHERE batch_id = ? AND provider_id = ?"
                        + " AND ((status = 'PENDING_DISPATCH' AND pool = 'PROVIDER_SEA')"
                        + "   OR (status = 'PROVIDER_SEA' AND pool = 'PROVIDER_SEA')"
                        + "   OR (status = 'IN_PROGRESS' AND pool = 'PRIVATE'))"
                        + " ORDER BY id",
                Long.class, batchId, providerId);

        // 承诺警示清单在收回前取（收回后 provider 已清空，按商过滤会失准）。
        List<Map<String, Object>> promised = promisedCases(batchId, providerId);

        int s1 = 0, s2 = 0, s3 = 0;
        for (long caseId : targets) {
            CaseSnapshot before = caseState.lockCase(caseId);
            caseState.requireState(before, Set.of(CaseStateService.S1, CaseStateService.S2, CaseStateService.S3));
            // 强制收回：S3 期望 holder=本持有人（清空）；S1/S2 无 holder。无视承诺暂缓（见类注释）。
            Transition t = new Transition(
                    before.status(), before.pool(), before.holderId(),
                    CaseStateService.ST_PENDING_DISPATCH, CaseStateService.POOL_PLATFORM_SEA,
                    null, "RETURN", null, null /*清t2*/, null /*清tc*/);
            int n = caseState.transition(caseId, t);
            if (n == 0) {
                throw new ApiException(BizError.STATE_409, "案件状态并发变更，结项失败: " + caseId);
            }
            caseState.clearCaseProvider(caseId);
            CaseSnapshot after = caseState.lockCase(caseId);
            caseState.audit(s, "case.engagement-recall", caseId,
                    "批次结项收回 reason=" + reason + " note=" + note, before, after, String.valueOf(providerId));
            if (CaseStateService.S1.equals(new CaseStateService.StatePair(before.status(), before.pool()))) s1++;
            else if (CaseStateService.S2.equals(new CaseStateService.StatePair(before.status(), before.pool()))) s2++;
            else s3++;
        }

        // 批次回可派态（不启用 CLOSED）；pay_out_rate 残值保留，下次派单必然覆盖。
        jdbc.update("UPDATE batch SET provider_id = NULL, status = 'PENDING', updated_at = now() WHERE id = ?", batchId);

        // 收段。
        jdbc.update("UPDATE batch_engagement SET ended_at = now(), end_reason = ?, end_note = ?, ended_by = ?"
                        + " WHERE batch_id = ? AND ended_at IS NULL",
                reason, note, Long.valueOf(s.accountId()), batchId);

        // 批级审计。
        jdbc.update("INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope, reason, trace_id)"
                        + " VALUES (?, ?, 'batch.close-engagement', ?, 'batch', ?, ?, ?, ?)",
                Long.valueOf(s.accountId()), s.name(), "batch#" + batchId, String.valueOf(batchId),
                s.orgType(), "结项 provider=" + providerId + " reason=" + reason + " note=" + note + " recalled=" + targets.size(),
                org.slf4j.MDC.get("traceId"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("recalled", targets.size());
        out.put("byState", Map.of("s1", s1, "s2", s2, "s3", s3));
        out.put("promisedCases", promised);
        return out;
    }

    // ── [2] GET /batches/{id}/close-preview ─────────────────────────────────
    // 结项确认框数据源（只读）：可收回统计 + 带有效分期承诺案件警示清单。
    @GetMapping("/batches/{id}/close-preview")
    @RequirePermission("case.dispatch")
    public Map<String, Object> previewCloseEngagement(@PathVariable("id") String id) {
        requirePlatform();
        long batchId = parseId(id);
        BatchRow batch = loadBatch(batchId);                   // 不存在 → 404
        if (batch.providerId() == null) {
            throw new ApiException(BizError.STATE_409, "批次无进行中承接，无需结项");
        }
        long providerId = batch.providerId();
        Map<String, Object> dist = jdbc.query(
                "SELECT count(*) FILTER (WHERE status = 'PENDING_DISPATCH' AND pool = 'PROVIDER_SEA') AS s1,"
                        + " count(*) FILTER (WHERE status = 'PROVIDER_SEA' AND pool = 'PROVIDER_SEA') AS s2,"
                        + " count(*) FILTER (WHERE status = 'IN_PROGRESS' AND pool = 'PRIVATE') AS s3"
                        + " FROM \"case\" WHERE batch_id = ? AND provider_id = ?",
                rs -> {
                    rs.next();
                    long a = rs.getLong("s1"), b = rs.getLong("s2"), c = rs.getLong("s3");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("s1", a); m.put("s2", b); m.put("s3", c); m.put("total", a + b + c);
                    return m;
                }, batchId, providerId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providerId", String.valueOf(providerId));
        out.put("providerName", orgName(providerId));
        out.put("recallable", dist);
        out.put("promisedCases", promisedCases(batchId, providerId));
        return out;
    }

    // ── [3] GET /batches/{id}/engagements ────────────────────────────────────
    // 承接历史：每段 服务商/起止/付佣快照/期间回款/期初剩余应收/期间回款率/结项原因备注/操作人。
    @GetMapping("/batches/{id}/engagements")
    @RequirePermission("case.dispatch")
    public Map<String, Object> listBatchEngagements(@PathVariable("id") String id) {
        requirePlatform();
        long batchId = parseId(id);
        loadBatch(batchId);                                    // 不存在 → 404

        // 批次总应收（案件 due_cents 口径，与批次列表聚合一致）。
        Long dueTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(due_cents),0) FROM \"case\" WHERE batch_id = ?", Long.class, batchId);
        long due = dueTotal == null ? 0L : dueTotal;

        List<Map<String, Object>> segs = jdbc.query(
                "SELECT e.id, e.seq, e.provider_id, o.name AS provider_name, e.pay_out_rate,"
                        + " e.started_at, e.ended_at, e.end_reason, e.end_note, a.name AS ended_by_name,"
                        // 同批同商下一段起点（多段归款上界；无则 NULL=不设上界）
                        + " (SELECT min(e2.started_at) FROM batch_engagement e2"
                        + "   WHERE e2.batch_id = e.batch_id AND e2.provider_id = e.provider_id"
                        + "     AND e2.started_at > e.started_at) AS next_same_start"
                        + " FROM batch_engagement e"
                        + " JOIN org o ON o.id = e.provider_id"
                        + " LEFT JOIN account a ON a.id = e.ended_by"
                        + " WHERE e.batch_id = ? ORDER BY e.seq",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("id")));
                    m.put("seq", rs.getInt("seq"));
                    m.put("providerId", String.valueOf(rs.getLong("provider_id")));
                    m.put("providerName", rs.getString("provider_name"));
                    m.put("payOutRate", rs.getBigDecimal("pay_out_rate"));
                    m.put("startedAt", ts(rs.getTimestamp("started_at")));
                    m.put("endedAt", ts(rs.getTimestamp("ended_at")));
                    m.put("endReason", rs.getString("end_reason"));
                    m.put("endNote", rs.getString("end_note"));
                    m.put("endedByName", rs.getString("ended_by_name"));
                    m.put("_providerId", rs.getLong("provider_id"));
                    m.put("_startedAt", rs.getTimestamp("started_at"));
                    m.put("_nextSameStart", rs.getTimestamp("next_same_start"));
                    return m;
                }, batchId);

        // 每段统计（段数通常个位数，逐段查询可接受）。
        for (Map<String, Object> seg : segs) {
            long pid = (Long) seg.remove("_providerId");
            java.sql.Timestamp startTs = (java.sql.Timestamp) seg.remove("_startedAt");
            java.sql.Timestamp upperTs = (java.sql.Timestamp) seg.remove("_nextSameStart");

            StringBuilder sql = new StringBuilder(
                    "SELECT COALESCE(SUM(rl.amount_cents),0) FROM repay_line rl"
                            + " JOIN \"case\" c ON c.id = rl.case_id"
                            + " WHERE c.batch_id = ? AND rl.provider_id_at_repay = ?"
                            + " AND rl.reversed = false AND rl.paid_at >= ?::date");
            List<Object> args = new ArrayList<>(List.of(batchId, pid, startTs));
            if (upperTs != null) { sql.append(" AND rl.paid_at < ?::date"); args.add(upperTs); }
            Long periodRepay = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
            long repay = periodRepay == null ? 0L : periodRepay;

            // 期初剩余应收 = 批次总应收 − started_at 前全批累计已收（reversed=false）。
            Long prior = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(rl.amount_cents),0) FROM repay_line rl"
                            + " JOIN \"case\" c ON c.id = rl.case_id"
                            + " WHERE c.batch_id = ? AND rl.reversed = false AND rl.paid_at < ?::date",
                    Long.class, batchId, startTs);
            long opening = due - (prior == null ? 0L : prior);

            seg.put("periodRepayCents", repay);
            seg.put("openingDueCents", Math.max(opening, 0L));
            seg.put("periodRepayRate", opening > 0
                    ? BigDecimal.valueOf(repay).divide(BigDecimal.valueOf(opening), 4, java.math.RoundingMode.HALF_UP)
                    : null);
        }
        return Map.of("items", segs);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** 该商名下带有效分期承诺（PENDING 期）的在催案件警示清单（须在收回动作前调用——收回会清 provider）。 */
    private List<Map<String, Object>> promisedCases(long batchId, long providerId) {
        List<Object> args = new ArrayList<>(List.of(batchId, providerId));
        return jdbc.query(
                "SELECT c.id, c.owner_name, c.room,"
                        + " count(pi.id) AS pending_installments, min(pi.due_date) AS next_due"
                        + " FROM \"case\" c"
                        + " JOIN promise p ON p.case_id = c.id"
                        + " JOIN promise_installment pi ON pi.promise_id = p.id AND pi.state = 'PENDING'"
                        + " WHERE c.batch_id = ? AND c.provider_id = ?"
                        + " AND c.status NOT IN ('SETTLED','WITHDRAWN','BAD_DEBT','VOIDED')"
                        + " GROUP BY c.id, c.owner_name, c.room ORDER BY c.id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("caseId", String.valueOf(rs.getLong("id")));
                    m.put("ownerName", rs.getString("owner_name"));
                    m.put("room", rs.getString("room"));
                    m.put("pendingInstallments", rs.getInt("pending_installments"));
                    java.sql.Date d = rs.getDate("next_due");
                    m.put("nextDueDate", d == null ? null : d.toLocalDate().toString());
                    return m;
                }, args.toArray());
    }

    private record BatchRow(long id, Long providerId, String status) {}

    private BatchRow lockBatch(long batchId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT id, provider_id, status FROM batch WHERE id = ? FOR UPDATE",
                (rs, i) -> new BatchRow(rs.getLong("id"), (Long) rs.getObject("provider_id"), rs.getString("status")),
                batchId);
        if (rows.isEmpty()) throw new ApiException(BizError.NOT_FOUND_404, "批次不存在");
        return rows.get(0);
    }

    private BatchRow loadBatch(long batchId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT id, provider_id, status FROM batch WHERE id = ?",
                (rs, i) -> new BatchRow(rs.getLong("id"), (Long) rs.getObject("provider_id"), rs.getString("status")),
                batchId);
        if (rows.isEmpty()) throw new ApiException(BizError.NOT_FOUND_404, "批次不存在");
        return rows.get(0);
    }

    private String orgName(long orgId) {
        return jdbc.query("SELECT name FROM org WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, orgId);
    }

    private CurrentSubject requirePlatform() {
        CurrentSubject s = SubjectContext.get();
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台可执行批次结项/查看承接历史");
        }
        return s;
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "批次不存在");
        }
    }

    private static String parseReason(Map<String, Object> body) {
        Object v = body == null ? null : body.get("reason");
        String r = v == null ? null : String.valueOf(v).trim();
        if (r == null || r.isBlank()) throw new ApiException(BizError.VALIDATION_422, "缺少 reason（结项原因）");
        if (!REASONS.contains(r)) {
            throw new ApiException(BizError.VALIDATION_422, "reason 非法（仅 INCAPABLE/COOP_TERMINATED/PROPERTY_REQUEST/OTHER）");
        }
        return r;
    }

    private static String parseRequired(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        String r = v == null ? null : String.valueOf(v).trim();
        if (r == null || r.isBlank()) throw new ApiException(BizError.VALIDATION_422, "缺少 " + key + "（结项备注必填）");
        return r;
    }

    private static String ts(java.sql.Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }
}
