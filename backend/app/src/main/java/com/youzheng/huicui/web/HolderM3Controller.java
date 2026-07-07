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
 * M3 holder 组（持有/分配/释放/退案）写端点。横切层范式 + scaffold 的 {@link CaseStateService} 助手。
 * 类名带 M3 后缀，与 M1/M2 controller 物理隔离，不碰共享件/其他组。
 *
 * 端点（基路径 /v1 由 context-path 提供，注解写裸路径）：
 *   POST /cases/{id}/assign  assignCase  | perm=case.assign | scope=own-org | 幂等 | S2→S3
 *   POST /cases/{id}/claim   claimCase   | perm=case.claim  | scope=own-org | 幂等 | S2/S4→S3 ★并发核心
 *   POST /cases/{id}/release releaseCase | perm=case.release| scope=case-holder | S3→S2/S4(按来源)
 *   POST /cases/{id}/return  returnCase  | perm=case.return | scope=own-org | S2/S3→S0
 *
 * 范式：每 action 在 @Transactional 内 lockCase(行锁)→requireState(前置态)→CAS transition(防并发)→audit。
 * 优雅降级（Gate1 not_a_server_error 命门）：
 *   案件不存在→404 NOT_FOUND_404；无权限→403 PERM_403（拦截器 + scope 复核）；
 *   状态非法→409 STATE_409；并发被抢→409 BIZ_ALREADY_CLAIMED；超持有上限→409 BIZ_HOLD_CAP；
 *   缺参/参数非法→422 VALIDATION_422。所有路径均映射为契约 Error 信封，绝不 5xx。
 * 金额一律 *_cents；这些 action 200 无体（契约 responses 仅 description: ok）。
 */
@RestController
public class HolderM3Controller {

    private final JdbcTemplate jdbc;
    private final CaseStateService state;

    public HolderM3Controller(JdbcTemplate jdbc, CaseStateService state) {
        this.jdbc = jdbc;
        this.state = state;
    }

    // CFG-TC / CFG-T2 缺省（settings TIMERS 未配时兜底；与种子 7d 一致量级）。
    private static final long DEFAULT_TC_SECONDS = 7L * 24 * 3600;   // 催收员无跟进释放时限
    private static final long DEFAULT_T2_SECONDS = 7L * 24 * 3600;   // 服务商公海滞留时限（CFG-T2 定稿 7 天）

    // ── [1] assignCase  S2(PROVIDER_SEA,PROVIDER_SEA) → S3(IN_PROGRESS,PRIVATE) ──
    @PostMapping("/cases/{id}/assign")
    @RequirePermission("case.assign")
    @Transactional
    public Map<String, Object> assignCase(@PathVariable("id") String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        long collectorId = parseCollectorId(body);

        // 目标 CO 必须属本商且 role=CO/ACTIVE，否则 403（在获取行锁前校验，快速失败）。
        requireOwnCollector(s, collectorId);
        // 全局锁序：先 lockCollector 再 lockCase（collector→case，与 claim / assignCasesBatch 一致，无环）。
        // B-03 修复：先对目标 CO account 行加锁，序列化同一 CO 的并发 assign/claim。
        state.lockCollector(collectorId);
        // 持有上限 BR-M3-05/06：目标 CO 私海持有数 ≥ CFG-HOLDCAP → 409 BIZ_HOLD_CAP。
        state.checkHoldCap(collectorId);

        CaseSnapshot snap = state.lockCase(caseId);          // 不存在→404
        // own-org：S2 案件须本商（batch.provider_id = 本组织），否则按不可见处理 404。
        requireOwnProvider(s, snap, caseId);
        state.requireState(snap, Set.of(CaseStateService.S2)); // 非 S2→409

        // 幂等：若该案已 S3 且 holder=目标 CO（重复分配），视为成功（200）。
        if (CaseStateService.ST_IN_PROGRESS.equals(snap.status())
                && CaseStateService.POOL_PRIVATE.equals(snap.pool())
                && snap.holderId() != null && snap.holderId() == collectorId) {
            return ok();
        }

        Transition t = new Transition(
                snap.status(), snap.pool(), null,
                CaseStateService.ST_IN_PROGRESS, CaseStateService.POOL_PRIVATE,
                collectorId, "ASSIGN", CaseStateService.POOL_PROVIDER_SEA,
                null /*清 t2*/, Instant.now().plusSeconds(tcSeconds()));
        int n = state.transition(caseId, t);
        if (n == 0) {
            // 锁内复核已过却 CAS 落空 → 并发被他人占用。
            throw new ApiException(BizError.BIZ_ALREADY_CLAIMED, "案件已被占用或状态已变更: " + caseId);
        }
        // 承接：案件级归属落到目标 CO 所属 org（前置 S2 同商，归属不变；保持与抢单/派单同口径）。
        state.setCaseProvider(caseId, state.holderOrg(caseId));
        CaseSnapshot after = state.lockCase(caseId);
        state.audit(s, "case.assign", caseId, null, snap, after);
        return ok();
    }

    // ── [2] claimCase  S2/S4 → S3  ★并发核心（SELECT FOR UPDATE 原子占用）──
    @PostMapping("/cases/{id}/claim")
    @RequirePermission("case.claim")
    @Transactional
    public Map<String, Object> claimCase(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        long coId = parseLong(s.accountId());

        // 幂等快检：不加行锁，仅确认案件存在（不存在→404）。
        // 注：快检后若本人已持有，直接 200；否则进入全局锁序路径。
        CaseSnapshot pre = state.peekCase(caseId);            // 不存在→404，不加 FOR UPDATE 锁
        if (CaseStateService.ST_IN_PROGRESS.equals(pre.status())
                && CaseStateService.POOL_PRIVATE.equals(pre.pool())
                && pre.holderId() != null && pre.holderId() == coId) {
            return ok();
        }

        // 全局锁序：先 lockCollector 再 lockCase（claim 内部遵守此顺序），
        // 与 assignCase / assignCasesBatch 一致（collector→case，无环）。
        // 已被抢→BIZ_ALREADY_CLAIMED；非 S2/S4→BIZ_ALREADY_CLAIMED；
        // 本商公海非本商→PERM_403；超上限→BIZ_HOLD_CAP。
        CaseSnapshot after = state.claim(caseId, s, tcSeconds());
        // 承接：案件级归属落到抢单 CO 所属 org（开放池跨商抢单时令本案归属抢单方，本商公海抢单为同 org 无变化）。
        state.setCaseProvider(caseId, parseLong(s.orgId()));
        state.audit(s, "case.claim", caseId, null, pre, after);
        return ok();
    }

    // ── [3] releaseCase  S3 → S2/S4（按 origin_pool 回流）──
    @PostMapping("/cases/{id}/release")
    @RequirePermission("case.release")
    @Transactional
    public Map<String, Object> releaseCase(@PathVariable("id") String id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        String reason = requireReason(body);                 // 缺 reason → 422

        long coId = parseLong(s.accountId());
        CaseSnapshot snap = state.lockCase(caseId);           // 不存在→404
        // 幂等：已非私海/已非本人持有 → 区分。case-holder：非本人持有 → 403。
        state.requireState(snap, Set.of(CaseStateService.S3)); // 非 S3→409
        if (snap.holderId() == null || snap.holderId() != coId) {
            throw new ApiException(BizError.PERM_403, "非本人持有案件，不可释放: " + caseId);
        }

        Instant t2 = Instant.now().plusSeconds(t2Seconds());
        Transition base = state.resolveReleaseTarget(snap, coId, t2); // 按 origin_pool 判回 S2/S4
        int n = state.transition(caseId, base);
        if (n == 0) {
            throw new ApiException(BizError.STATE_409, "案件状态已变更，释放失败: " + caseId);
        }
        // 案件级归属维护：回服务商公海(S2) → 案件仍属本商，保留 provider_id（本商其他 CO 须可见/可再抢，claim S2 校验依赖之）；
        //   回开放池(S4) → 案件离开本商私海，跨商开放抢单，清 provider_id=NULL（避免 range scope 仍只让旧商可见）。
        if (CaseStateService.POOL_OPEN_POOL.equals(base.toPool())) {
            state.clearCaseProvider(caseId);
        }
        CaseSnapshot after = state.lockCase(caseId);
        state.audit(s, "case.release", caseId, reason, snap, after);
        return ok();
    }

    // ── [4] returnCase  S2/S3 → S0（本商退案回平台公海）──
    @PostMapping("/cases/{id}/return")
    @RequirePermission("case.return")
    @Transactional
    public Map<String, Object> returnCase(@PathVariable("id") String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        String reason = requireReason(body);                 // 缺 reason → 422

        CaseSnapshot snap = state.lockCase(caseId);           // 不存在→404
        requireOwnProvider(s, snap, caseId);                  // 非本商→404（不可见）
        state.requireState(snap, Set.of(CaseStateService.S2, CaseStateService.S3)); // 非 S2/S3→409

        // → S0：清 holder、回平台公海；source=RETURN；清 t2/tc。期望持有人按当前快照（S3 有 holder，S2 无）。
        Transition t = new Transition(
                snap.status(), snap.pool(), snap.holderId(),
                CaseStateService.ST_PENDING_DISPATCH, CaseStateService.POOL_PLATFORM_SEA,
                null, "RETURN", null,
                null /*清 t2*/, null /*清 tc*/);
        int n = state.transition(caseId, t);
        if (n == 0) {
            throw new ApiException(BizError.STATE_409, "案件状态已变更，退案失败: " + caseId);
        }
        // 案件级归属清空：回平台公海，case.provider_id=NULL（不动 batch.provider_id，避免污染同批）。
        // before 快照（snap）仍保留退回前 providerId → redispatch 护栏①经 before_snap->>'providerId' 推导原退回商。
        state.clearCaseProvider(caseId);
        CaseSnapshot after = state.lockCase(caseId);
        state.audit(s, "case.return", caseId, reason, snap, after);
        return ok();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<String, Object> ok() {
        return Map.of("ok", true);
    }

    /** 路径 id 非法形态统一 404，避免存在性泄漏 / 防 5xx。 */
    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + id);
        }
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, "非法标识: " + v);
        }
    }

    /** body.collectorId 必填且为数字，否则 422。 */
    private static long parseCollectorId(Map<String, Object> body) {
        Object v = body == null ? null : body.get("collectorId");
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 collectorId");
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, "collectorId 非法: " + v);
        }
    }

    /** ReasonInput.reason 必填，否则 422。 */
    private static String requireReason(Map<String, Object> body) {
        Object v = body == null ? null : body.get("reason");
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 reason");
        }
        return String.valueOf(v).trim();
    }

    /**
     * own-org 复核：S2/S3 案件 batch.provider_id 须 = 当前主体 org（平台主体放行）。
     * 不满足按不可见 → 404（避免存在性泄漏，等价 scope 裁剪）。
     */
    private void requireOwnProvider(CurrentSubject s, CaseSnapshot snap, long caseId) {
        if (s.isPlatform()) return;
        Long org = parseOrgId(s);
        if (org == null || snap.providerId() == null || !snap.providerId().equals(org)) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + caseId);
        }
    }

    /** 目标 collector 必须属本商且 role_template=CO 且 status=ACTIVE，否则 403。 */
    private void requireOwnCollector(CurrentSubject s, long collectorId) {
        Long org = parseOrgId(s);
        if (org == null) {
            throw new ApiException(BizError.PERM_403, "无组织上下文，不可分配");
        }
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM account WHERE id = ? AND org_id = ?"
                        + " AND role_template = 'CO' AND status = 'ACTIVE'",
                Integer.class, collectorId, org);
        if (n == null || n == 0) {
            throw new ApiException(BizError.PERM_403, "目标非本服务商在岗催收员: " + collectorId);
        }
    }

    private static Long parseOrgId(CurrentSubject s) {
        try {
            return s.orgId() == null ? null : Long.valueOf(s.orgId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** CFG-TC：settings TIMERS.tcSeconds，缺省 DEFAULT_TC_SECONDS。 */
    private long tcSeconds() {
        return timerSeconds("tcSeconds", DEFAULT_TC_SECONDS);
    }

    /** CFG-T2：settings TIMERS.t2Seconds，缺省 DEFAULT_T2_SECONDS。 */
    private long t2Seconds() {
        return timerSeconds("t2Seconds", DEFAULT_T2_SECONDS);
    }

    private long timerSeconds(String key, long dflt) {
        try {
            Long v = jdbc.query(
                    "SELECT timers ->> ? AS v FROM settings WHERE domain = 'TIMERS'"
                            + " ORDER BY version DESC LIMIT 1",
                    rs -> {
                        if (!rs.next()) return null;
                        String raw = rs.getString("v");
                        if (raw == null || raw.isBlank()) return null;
                        try { return Long.valueOf(raw.trim()); } catch (NumberFormatException e) { return null; }
                    }, key);
            return v == null ? dflt : v;
        } catch (RuntimeException e) {
            return dflt;   // 配置读异常不致 5xx，退兜底
        }
    }
}
