package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.BatchParseDtos.BatchParseInput;
import com.youzheng.huicui.web.dto.BatchParseDtos.BatchParseResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 计费/配额相关写端点。
 *   [10] POST /recordings/batch-parse — batchParseRecordings | case.call | own-org | @Transactional
 *
 * 基路径 /v1 由 context-path 提供。Idempotency-Key 由 IdempotencyInterceptor header 层兜底。
 *
 * 批量补解析（BR-M5-02）：
 *   目标 = call_recording JOIN case JOIN project（own-org=p.org_id=本 org）且 status∈(PENDING,QUOTA_BLOCKED)，
 *   过滤优先级 caseIds > batchId > 无过滤（本 org 全部待解析），按 recorded_at 时间顺序处理。
 *   读 STT 余额（recharge_log 最新 balance WHERE org_id=? AND type='STT'），逐条按时长（分钟，向上取整）扣减：
 *     余额够 → 置 PARSING（queued++）+ 写 billing_usage(STT,分钟) + recharge_log delta=-分钟、balance 滚动；
 *     余额不足 → 剩余置 QUOTA_BLOCKED（skipped++）。
 *   全部耗尽且 queued==0 → 409 BIZ_QUOTA_EXHAUSTED。响应 202。
 *   非法输入绝不 5xx（403 无权 / 409 余额耗尽）。
 */
@RestController
public class BillingController {

    private final JdbcTemplate jdbc;

    public BillingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 待解析录音行（含归属 org 与时长）。 */
    private record RecRow(long id, long caseId, long orgId, int durationSec) {}

    @PostMapping("/recordings/batch-parse")
    @RequirePermission("case.call")
    @Transactional
    public ResponseEntity<BatchParseResult> batchParseRecordings(@RequestBody(required = false) BatchParseInput in) {
        CurrentSubject s = SubjectContext.get();
        long myOrg = Long.parseLong(s.orgId());

        // 平台主体无 org 录音池语义；own-org 端点要求按本 org 解析。平台不限制 org，但本端点语义是 own-org，
        // 故平台调用时仍按 own-org（其 org 的录音，通常为空）。非法输入不 5xx。
        StringBuilder where = new StringBuilder(
                " WHERE r.status IN ('PENDING','QUOTA_BLOCKED')");
        List<Object> args = new ArrayList<>();
        if (!s.isPlatform()) {
            where.append(" AND p.org_id = ?");
            args.add(myOrg);
        }

        boolean hasCaseIds = in != null && in.caseIds() != null && !in.caseIds().isEmpty();
        if (hasCaseIds) {
            List<Long> ids = parseIds(in.caseIds());
            if (!ids.isEmpty()) {
                where.append(" AND c.id IN (").append(placeholders(ids.size())).append(")");
                args.addAll(ids);
            } else {
                // 全部非法 id → 无目标，按耗尽语义返回（无可解析）。
                where.append(" AND 1=0");
            }
        } else if (in != null && in.batchId() != null && !in.batchId().isBlank()) {
            Long bid = tryParse(in.batchId());
            if (bid != null) {
                where.append(" AND c.batch_id = ?");
                args.add(bid);
            } else {
                where.append(" AND 1=0");
            }
        }

        List<RecRow> targets = jdbc.query(
                "SELECT r.id, r.case_id, p.org_id, COALESCE(r.duration_sec, 0) AS duration_sec"
                        + " FROM call_recording r"
                        + " JOIN \"case\" c ON c.id = r.case_id"
                        + " JOIN project p ON p.id = c.project_id"
                        + where
                        + " ORDER BY r.recorded_at NULLS LAST, r.id",
                (rs, i) -> new RecRow(
                        rs.getLong("id"), rs.getLong("case_id"), rs.getLong("org_id"), rs.getInt("duration_sec")),
                args.toArray());

        if (targets.isEmpty()) {
            // 无可解析目标（无录音/全非法过滤）→ 409 余额耗尽语义（queued==0）。
            throw new ApiException(BizError.BIZ_QUOTA_EXHAUSTED, "无可解析录音或余额已耗尽");
        }

        long billingOrg = s.isPlatform() ? targets.get(0).orgId() : myOrg;
        // 串行化本 org STT 余额读-扣-写：advisory 事务锁防并发丢失更新(审计 H-1)。@Transactional 提交时释放。
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", (int) billingOrg, "STT".hashCode());
        // STT 余额（最新 balance 快照）。
        BigDecimal balance = sttBalance(billingOrg);

        int queued = 0, skipped = 0;
        for (RecRow r : targets) {
            // 时长→分钟（向上取整，至少 1 分钟）。
            long minutes = Math.max(1, (long) Math.ceil(r.durationSec() / 60.0));
            BigDecimal cost = BigDecimal.valueOf(minutes);
            if (balance.compareTo(cost) >= 0) {
                jdbc.update("UPDATE call_recording SET status = 'PARSING', updated_at = now() WHERE id = ?", r.id());
                jdbc.update(
                        "INSERT INTO billing_usage(org_id, type, qty, unit, case_id) VALUES (?, 'STT', ?, '分钟', ?)",
                        billingOrg, cost, r.caseId());
                balance = balance.subtract(cost);
                jdbc.update(
                        "INSERT INTO recharge_log(org_id, type, delta, balance, ref, note, operated_by)"
                                + " VALUES (?, 'STT', ?, ?, ?, '批量补解析扣减', ?)",
                        billingOrg, cost.negate(), balance.setScale(3, RoundingMode.HALF_UP),
                        "rec#" + r.id(), Long.valueOf(s.accountId()));
                queued++;
            } else {
                jdbc.update(
                        "UPDATE call_recording SET status = 'QUOTA_BLOCKED', updated_at = now() WHERE id = ?", r.id());
                skipped++;
            }
        }

        if (queued == 0) {
            // 全部余额不足 → 409（事务已置 QUOTA_BLOCKED，但抛异常将回滚；地基期以 409 为准，标记可后台重置）。
            throw new ApiException(BizError.BIZ_QUOTA_EXHAUSTED, "STT 余额不足，全部跳过");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new BatchParseResult(queued, skipped));
    }

    /** STT 余额：recharge_log 最新一条 balance；无记录视为 0。 */
    private BigDecimal sttBalance(long orgId) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT balance FROM recharge_log WHERE org_id = ? AND type = 'STT' ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getBigDecimal("balance"), orgId);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    private static List<Long> parseIds(List<String> raw) {
        List<Long> out = new ArrayList<>();
        for (String r : raw) {
            Long v = tryParse(r);
            if (v != null) out.add(v);
        }
        return out;
    }

    private static Long tryParse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
