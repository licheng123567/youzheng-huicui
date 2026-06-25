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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M3 平台派单组（batch-provider 组，写端点）。基路径 /v1 由 context-path 提供。
 * 类名带 M3 后缀，避免碰已有 M1/M2 controller（BatchesM2Controller 仅读）。
 *
 * 端点（真值源 openapi-core.yaml dispatch tag + PRD 03 BR-M3-*）：
 *   POST /batches/{id}/dispatch    dispatchBatch    perm=case.dispatch  scope=platform  幂等
 *   POST /batches/{id}/redispatch  redispatchBatch  perm=case.dispatch  scope=platform  幂等
 *   PUT  /batches/{id}/open-rate   setBatchOpenRate perm=case.dispatch  scope=platform
 *
 * 横切落地：
 *   - x-permission：@RequirePermission("case.dispatch") → PermissionInterceptor 缺权限 403。
 *   - x-data-scope=platform：非平台主体一律 403 PERM_403（强裁剪，非仅前端隐藏）。
 *   - 状态机：复用 CaseStateService（lockCase 行锁 → requireState 前置态 → transition 原子 CAS → audit）。
 *
 * 不可 5xx 自律：所有输入路径都映射到 4xx —— 路径/体非法→422、批次/服务商不存在→404、
 *   越权→403、状态冲突→409、防倒挂→422 BIZ_PAYOUT_INVERT、容量护栏→409 BIZ_CAP_EXCEEDED。
 *   GlobalExceptionHandler 兜底亦为 422，绝不外泄 5xx（Gate1 not_a_server_error）。
 *
 * 金额/比率：批次比率列 NUMERIC(6,4)，按契约 Rate=number 原样比较；本组无金额体。
 */
@RestController
public class DispatchM3Controller {

    private final JdbcTemplate jdbc;
    private final CaseStateService caseState;

    public DispatchM3Controller(JdbcTemplate jdbc, CaseStateService caseState) {
        this.jdbc = jdbc;
        this.caseState = caseState;
    }

    // CFG-T2 兜底（settings TIMERS 缺省）：服务商公海/待接单滞留计时 3 天。
    private static final long DEFAULT_T2_SECONDS = 3L * 24 * 3600;

    // ── DispatchInput（契约 schema）──────────────────────────────────────────
    public record DispatchInput(String mode, String providerId, Integer splitCount,
                                List<String> caseIds, BigDecimal payOutRate) {}

    public record OpenRateInput(BigDecimal openRate) {}

    // ── [1] POST /batches/{id}/dispatch ──────────────────────────────────────
    // 前置态：批内目标案件均 S0(PENDING_DISPATCH,PLATFORM_SEA) → 后置态：S1(PENDING_DISPATCH,PROVIDER_SEA)。
    @PostMapping("/batches/{id}/dispatch")
    @RequirePermission("case.dispatch")
    @Transactional
    public Map<String, Object> dispatchBatch(@PathVariable String id, @RequestBody(required = false) DispatchInput in) {
        CurrentSubject s = requirePlatform();
        long batchId = parseId(id, "批次");
        return doDispatch(s, batchId, in, false);
    }

    // ── [2] POST /batches/{id}/redispatch ────────────────────────────────────
    // 前置态：批内目标案件均 S0(PENDING_DISPATCH,PLATFORM_SEA) 且过再派护栏①(新商≠原退回商) → 后置态：S1。
    @PostMapping("/batches/{id}/redispatch")
    @RequirePermission("case.dispatch")
    @Transactional
    public Map<String, Object> redispatchBatch(@PathVariable String id, @RequestBody(required = false) DispatchInput in) {
        CurrentSubject s = requirePlatform();
        long batchId = parseId(id, "批次");
        return doDispatch(s, batchId, in, true);
    }

    // ── [3] PUT /batches/{id}/open-rate ──────────────────────────────────────
    // 前置态：批次存在 → 后置态：batch.open_rate=openRate（不改任何案件状态）。
    @PutMapping("/batches/{id}/open-rate")
    @RequirePermission("case.dispatch")
    @Transactional
    public Map<String, Object> setBatchOpenRate(@PathVariable String id, @RequestBody(required = false) OpenRateInput in) {
        CurrentSubject s = requirePlatform();
        long batchId = parseId(id, "批次");
        if (in == null || in.openRate() == null) {
            throw new ApiException(BizError.VALIDATION_422, "openRate 必填");
        }
        BigDecimal commInRate = loadCommInRateOr404(batchId);
        // 防倒挂 BR-M9-18：开放比率 > 收佣比例 → 422 BIZ_PAYOUT_INVERT。
        if (in.openRate().compareTo(commInRate) > 0) {
            throw new ApiException(BizError.BIZ_PAYOUT_INVERT, "开放抢单比率不得大于收佣比例");
        }
        int n = jdbc.update("UPDATE batch SET open_rate = ?, updated_at = now() WHERE id = ?",
                in.openRate(), batchId);
        if (n == 0) {
            throw new ApiException(BizError.NOT_FOUND_404, "批次不存在: " + id);
        }
        caseState.audit(s, "batch.open-rate", batchId, "openRate=" + in.openRate(), null, null);
        return ok();
    }

    // ── 共享：dispatch / redispatch 主体 ──────────────────────────────────────

    private Map<String, Object> doDispatch(CurrentSubject s, long batchId, DispatchInput in, boolean redispatch) {
        // 1) 入参校验（缺参 → 422，绝不 NPE/5xx）。
        if (in == null) throw new ApiException(BizError.VALIDATION_422, "请求体必填");
        String mode = in.mode();
        if (mode == null || !(mode.equals("WHOLE") || mode.equals("SPLIT"))) {
            throw new ApiException(BizError.VALIDATION_422, "mode 必填且须为 WHOLE|SPLIT");
        }
        if (in.providerId() == null || in.providerId().isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "providerId 必填");
        }
        if (in.payOutRate() == null) {
            throw new ApiException(BizError.VALIDATION_422, "payOutRate 必填");
        }
        long providerId = parseId(in.providerId(), "服务商");

        // 2) 批次存在 + 收佣比例（防倒挂基准）。
        BigDecimal commInRate = loadCommInRateOr404(batchId);

        // 3) 防倒挂：payOutRate > comm_in_rate → 422 BIZ_PAYOUT_INVERT。
        if (in.payOutRate().compareTo(commInRate) > 0) {
            throw new ApiException(BizError.BIZ_PAYOUT_INVERT, "付佣比例不得大于收佣比例");
        }

        // 4) 服务商校验：org.type=PROVIDER 且 ACTIVE，否则 404（不泄露存在性）。
        requireActiveProvider(providerId);

        // 5) 选目标案件（行锁），全部须 S0；含非 S0 → STATE_409。
        List<Long> targetIds = selectTargetCaseIds(batchId, mode, in.splitCount(), in.caseIds());
        if (targetIds.isEmpty()) {
            throw new ApiException(BizError.STATE_409, "批次内无可派单(待派单·平台公海)案件");
        }

        long t2Seconds = t2Seconds();
        Instant t2Deadline = Instant.now().plusSeconds(t2Seconds);

        // 6) 容量护栏 BR-M3-23：服务商持有余量不足 → 409 BIZ_CAP_EXCEEDED。
        //    口径：服务商当前持有(本商公海 S2 + 待接单 S1 + 私海 S3 + 派给本商案件) + 本次派单量 ≤ CFG-HOLDCAP。
        int existing = providerHoldCount(providerId);
        if (existing + targetIds.size() > caseState.holdCap()) {
            throw new ApiException(BizError.BIZ_CAP_EXCEEDED, "服务商持有余量不足(CFG-HOLDCAP)");
        }

        // 7) 逐件锁定 + 校验前置态 S0 + 再派护栏① + 原子转移 S0→S1。
        for (long caseId : targetIds) {
            CaseSnapshot before = caseState.lockCase(caseId);
            // 防御：行锁后再核归属批次一致（理论上 selectTargetCaseIds 已限定）。
            if (before.batchId() != batchId) {
                throw new ApiException(BizError.STATE_409, "案件不属于该批次: " + caseId);
            }
            caseState.requireState(before, java.util.Set.of(CaseStateService.S0));

            if (redispatch) {
                // 再派护栏① BR-M3-16：新 providerId ≠ 该案最近一次退回服务商。
                Long lastReturned = lastReturnedProvider(caseId);
                if (lastReturned != null && lastReturned == providerId) {
                    throw new ApiException(BizError.STATE_409, "再派护栏：新服务商不得为原退回服务商: " + caseId);
                }
            }

            Transition t = new Transition(
                    before.status(), before.pool(), null,
                    CaseStateService.ST_PENDING_DISPATCH, CaseStateService.POOL_PROVIDER_SEA,
                    null, "DISPATCH", null, t2Deadline, null);
            int n = caseState.transition(caseId, t);
            if (n == 0) {
                // 并发：前置态被他人改写。
                throw new ApiException(BizError.STATE_409, "案件状态已变更，派单失败: " + caseId);
            }
            CaseSnapshot after = caseState.lockCase(caseId);
            caseState.audit(s, redispatch ? "case.redispatch" : "case.dispatch", caseId,
                    "providerId=" + providerId, before, after, String.valueOf(providerId));
        }

        // 8) 写批次归属/比率/状态。
        jdbc.update("UPDATE batch SET provider_id = ?, pay_out_rate = ?, status = 'DISPATCHED', updated_at = now()"
                + " WHERE id = ?", providerId, in.payOutRate(), batchId);

        return ok();
    }

    // ── 选目标案件 ────────────────────────────────────────────────────────────

    /**
     * mode=WHOLE → 全批待派单(S0)案件；mode=SPLIT → caseIds 优先，否则按入池时间序取 splitCount 个(D3)。
     * 仅取 S0(PENDING_DISPATCH,PLATFORM_SEA) 案件（前置态裁剪），含非 S0 由后续逐件 requireState 兜 409。
     */
    private List<Long> selectTargetCaseIds(long batchId, String mode, Integer splitCount, List<String> caseIds) {
        if ("SPLIT".equals(mode) && caseIds != null && !caseIds.isEmpty()) {
            // caseIds 优先：解析为 long，非法元素 → 422。逐件归属由 doDispatch 行锁内复核。
            List<Long> ids = new ArrayList<>(caseIds.size());
            for (String c : caseIds) ids.add(parseId(c, "案件"));
            return ids;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id FROM \"case\" WHERE batch_id = ? AND status = ? AND pool = ?"
                        + " ORDER BY created_at, id");
        List<Object> args = new ArrayList<>();
        args.add(batchId);
        args.add(CaseStateService.ST_PENDING_DISPATCH);
        args.add(CaseStateService.POOL_PLATFORM_SEA);
        if ("SPLIT".equals(mode)) {
            int n = (splitCount == null || splitCount < 0) ? 0 : splitCount;
            sql.append(" LIMIT ?");
            args.add(n);
        }
        return jdbc.queryForList(sql.toString(), Long.class, args.toArray());
    }

    // ── 服务商持有量（容量护栏 BR-M3-23）──────────────────────────────────────

    /**
     * 服务商已持有案件量：派给本商批次下、尚在派单/承接/私海周转中的案件
     * （状态 ∈ {PENDING_DISPATCH 待接单, PROVIDER_SEA, IN_PROGRESS}）。结案/退回平台已不计。
     */
    private int providerHoldCount(long providerId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM \"case\" c JOIN batch b ON b.id = c.batch_id"
                        + " WHERE b.provider_id = ?"
                        + " AND c.status IN ('PENDING_DISPATCH','PROVIDER_SEA','IN_PROGRESS')"
                        + " AND c.pool IN ('PROVIDER_SEA','PRIVATE')",
                Integer.class, providerId);
        return n == null ? 0 : n;
    }

    /** 该案最近一次退回服务商 id（从 audit_log case.reject/case.return 轨迹取 proxy_for），无则 null。 */
    private Long lastReturnedProvider(long caseId) {
        return jdbc.query(
                "SELECT proxy_for FROM audit_log WHERE target_type = 'case' AND target_id = ?"
                        + " AND action IN ('case.reject','case.return') AND proxy_for IS NOT NULL"
                        + " ORDER BY id DESC LIMIT 1",
                rs -> {
                    if (!rs.next()) return null;
                    String v = rs.getString(1);
                    if (v == null || v.isBlank()) return null;
                    try { return Long.valueOf(v); } catch (NumberFormatException e) { return null; }
                },
                String.valueOf(caseId));
    }

    // ── 公共校验/工具 ─────────────────────────────────────────────────────────

    /** x-data-scope=platform：非平台主体 → 403（强裁剪）。 */
    private CurrentSubject requirePlatform() {
        CurrentSubject s = SubjectContext.get();
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台可执行派单/开放比率操作");
        }
        return s;
    }

    /** 批次收佣比例（防倒挂基准）；批次不存在 → 404。 */
    private BigDecimal loadCommInRateOr404(long batchId) {
        BigDecimal rate = jdbc.query(
                "SELECT comm_in_rate FROM batch WHERE id = ?",
                rs -> rs.next() ? rs.getBigDecimal("comm_in_rate") : null, batchId);
        if (rate == null) {
            throw new ApiException(BizError.NOT_FOUND_404, "批次不存在: " + batchId);
        }
        return rate;
    }

    /** 服务商校验：org.type=PROVIDER 且 status=ACTIVE，否则 404（不泄露存在性/越权）。 */
    private void requireActiveProvider(long providerId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM org WHERE id = ? AND type = 'PROVIDER' AND status = 'ACTIVE'",
                Integer.class, providerId);
        if (n == null || n == 0) {
            throw new ApiException(BizError.NOT_FOUND_404, "服务商不存在或不可用: " + providerId);
        }
    }

    /** CFG-T2：读 settings TIMERS.t2Seconds（取最新版本），缺省 3 天。 */
    private long t2Seconds() {
        Long sec = jdbc.query(
                "SELECT timers ->> 't2Seconds' AS t2 FROM settings"
                        + " WHERE domain = 'TIMERS' ORDER BY version DESC LIMIT 1",
                rs -> {
                    if (!rs.next()) return null;
                    String v = rs.getString("t2");
                    if (v == null || v.isBlank()) return null;
                    try { return Long.valueOf(v); } catch (NumberFormatException e) { return null; }
                });
        return sec == null ? DEFAULT_T2_SECONDS : sec;
    }

    /** 路径/体内的 id 解析：非数字 → 422（绝不 NumberFormatException 冒泡成 5xx）。 */
    private static long parseId(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, what + " id 必填");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(BizError.VALIDATION_422, what + " id 非法: " + raw);
        }
    }

    private static Map<String, Object> ok() {
        return Map.of("ok", true);
    }
}
