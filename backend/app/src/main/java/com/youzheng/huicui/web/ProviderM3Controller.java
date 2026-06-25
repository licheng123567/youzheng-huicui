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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * M3 服务商承接组（batch-provider 组，写端点）。基路径 /v1 由 context-path 提供。
 * 类名带 M3 后缀，不碰已有 M1/M2 controller。
 *
 * 端点（真值源 openapi-core.yaml dispatch tag + PRD 03 BR-M3-*）：
 *   POST /cases/{id}/accept  acceptCase  perm=case.accept  scope=own-org  幂等
 *   POST /cases/{id}/reject  rejectCase  perm=case.accept  scope=own-org  幂等  body=ReasonInput
 *
 * 横切落地：
 *   - x-permission：@RequirePermission("case.accept") → PermissionInterceptor 缺权限 403。
 *   - x-data-scope=own-org：accept/reject 仅作用于「派给本服务商」的案件（batch.provider_id=本主体 org）；
 *     非本商案件统一以 404 处理（不泄露存在性），平台主体亦不可走此端点（own-org 语义）→ 404/403。
 *   - 状态机：复用 CaseStateService（lockCase 行锁 → requireState 前置态 → transition 原子 CAS → audit）。
 *
 * 不可 5xx 自律：路径非法→422、案件不存在/非本商→404、状态冲突→409、缺 reason→422。
 *   GlobalExceptionHandler 兜底亦 4xx，绝不外泄 5xx（Gate1 not_a_server_error）。
 */
@RestController
public class ProviderM3Controller {

    private final JdbcTemplate jdbc;
    private final CaseStateService caseState;

    public ProviderM3Controller(JdbcTemplate jdbc, CaseStateService caseState) {
        this.jdbc = jdbc;
        this.caseState = caseState;
    }

    private static final long DEFAULT_T2_SECONDS = 3L * 24 * 3600;

    public record ReasonInput(String reason) {}

    // ── [1] POST /cases/{id}/accept ──────────────────────────────────────────
    // 前置态：S1(PENDING_DISPATCH,PROVIDER_SEA) 且 batch.provider_id=本商 → 后置态：S2(PROVIDER_SEA,PROVIDER_SEA)。
    @PostMapping("/cases/{id}/accept")
    @RequirePermission("case.accept")
    @Transactional
    public Map<String, Object> acceptCase(@PathVariable String id) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        long myOrg = parseOrg(s);

        CaseSnapshot before = caseState.lockCase(caseId);
        requireOwnProvider(before, myOrg, caseId);

        // 幂等：已在 S2(本商公海) 视为承接成功 → 200。
        if (CaseStateService.S2.equals(new CaseStateService.StatePair(before.status(), before.pool()))) {
            return ok();
        }
        caseState.requireState(before, Set.of(CaseStateService.S1));

        Instant t2 = Instant.now().plusSeconds(t2Seconds());
        // → S2：清旧 t2 并按服务商公海重置 t2（BR-M3-13 滞留计时起算）；source 置 ACCEPT；holder 保持空。
        Transition t = new Transition(
                before.status(), before.pool(), null,
                CaseStateService.ST_PROVIDER_SEA, CaseStateService.POOL_PROVIDER_SEA,
                null, "ACCEPT", null, t2, null);
        int n = caseState.transition(caseId, t);
        if (n == 0) {
            throw new ApiException(BizError.STATE_409, "案件状态已变更，承接失败: " + caseId);
        }
        CaseSnapshot after = caseState.lockCase(caseId);
        caseState.audit(s, "case.accept", caseId, null, before, after);
        return ok();
    }

    // ── [2] POST /cases/{id}/reject ──────────────────────────────────────────
    // 前置态：S1(PENDING_DISPATCH,PROVIDER_SEA) 且本商 → 后置态：S0(PENDING_DISPATCH,PLATFORM_SEA)，清 provider_id，记原退回商。
    @PostMapping("/cases/{id}/reject")
    @RequirePermission("case.accept")
    @Transactional
    public Map<String, Object> rejectCase(@PathVariable String id, @RequestBody(required = false) ReasonInput in) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        long myOrg = parseOrg(s);
        if (in == null || in.reason() == null || in.reason().isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "reason 必填");
        }

        CaseSnapshot before = caseState.lockCase(caseId);
        requireOwnProvider(before, myOrg, caseId);

        // 幂等：已回平台公海(S0)视为已拒成功 → 200。
        if (CaseStateService.S0.equals(new CaseStateService.StatePair(before.status(), before.pool()))) {
            return ok();
        }
        caseState.requireState(before, Set.of(CaseStateService.S1));

        // → S0：回平台公海，清 holder/t2/origin；source=RETURN（拒接=退回平台由平台重派）。
        Transition t = new Transition(
                before.status(), before.pool(), null,
                CaseStateService.ST_PENDING_DISPATCH, CaseStateService.POOL_PLATFORM_SEA,
                null, "RETURN", null, null, null);
        int n = caseState.transition(caseId, t);
        if (n == 0) {
            throw new ApiException(BizError.STATE_409, "案件状态已变更，拒接失败: " + caseId);
        }
        // 清批次 provider_id 归属（退回平台，等待重派）。
        jdbc.update("UPDATE batch SET provider_id = NULL, updated_at = now() WHERE id = ?", before.batchId());

        CaseSnapshot after = caseState.lockCase(caseId);
        // proxy_for 记本商为「原退回服务商」，供 redispatch 护栏①与频次统计 BR-M3-24。
        caseState.audit(s, "case.reject", caseId, in.reason(), before, after, String.valueOf(myOrg));
        return ok();
    }

    // ── 共享校验 ──────────────────────────────────────────────────────────────

    /**
     * own-org 强裁剪：案件须派给本服务商（batch.provider_id = 本主体 org）。
     * 非本商（含 provider 为空/平台主体）→ 404，不泄露存在性。
     */
    private void requireOwnProvider(CaseSnapshot snap, long myOrg, long caseId) {
        if (snap.providerId() == null || snap.providerId() != myOrg) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在或非本服务商: " + caseId);
        }
    }

    /** CFG-T2：读 settings TIMERS.t2Seconds 最新版本，缺省 3 天。 */
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

    private static long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "案件 id 必填");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(BizError.VALIDATION_422, "案件 id 非法: " + raw);
        }
    }

    /** 当前主体 org（own-org 主体须有 orgId；缺失/非法 → 403）。 */
    private static long parseOrg(CurrentSubject s) {
        try {
            return Long.parseLong(s.orgId());
        } catch (Exception e) {
            throw new ApiException(BizError.PERM_403, "当前主体无有效组织，不可执行承接/拒接");
        }
    }

    private static Map<String, Object> ok() {
        return Map.of("ok", true);
    }
}
