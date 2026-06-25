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
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * M3 平台侧二选一处置（开放抢单）写端点。横切层范式 + scaffold 的 {@link CaseStateService} 助手。
 *
 * open-for-claim 契约挂 case.dispatch / platform 权限，逻辑属平台处置；路径在 /cases 下，
 * 故按 scaffold 建议单列平台侧控制器（不放 DispatchM3Controller，避免跨组改文件）。
 *
 *   POST /cases/{id}/open-for-claim  openCaseForClaim | perm=case.dispatch | scope=platform | 幂等
 *     前置=S0(PENDING_DISPATCH,PLATFORM_SEA) 且 batch.open_rate 已设
 *     后置=→S4(PENDING_DISPATCH,OPEN_POOL)，source=OPEN
 *     错误：批次未设 open_rate→409 BIZ_OPEN_RATE_REQUIRED；非 S0→409 STATE_409；越权→403；不存在→404。
 *
 * 优雅降级：所有非法输入映射为契约 Error 信封（404/403/409/422），绝不 5xx。
 */
@RestController
public class PlatformDispatchM3Controller {

    private final JdbcTemplate jdbc;
    private final CaseStateService state;

    public PlatformDispatchM3Controller(JdbcTemplate jdbc, CaseStateService state) {
        this.jdbc = jdbc;
        this.state = state;
    }

    @PostMapping("/cases/{id}/open-for-claim")
    @RequirePermission("case.dispatch")
    @Transactional
    public Map<String, Object> openCaseForClaim(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        // scope=platform：非平台主体即便有权限点也越权拒（强裁剪）。
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台运营/超管可开放抢单");
        }
        long caseId = parseId(id);

        CaseSnapshot snap = state.lockCase(caseId);            // 不存在→404

        // 幂等：已在开放池 S4 → 200。
        if (CaseStateService.ST_PENDING_DISPATCH.equals(snap.status())
                && CaseStateService.POOL_OPEN_POOL.equals(snap.pool())) {
            return ok();
        }

        state.requireState(snap, Set.of(CaseStateService.S0)); // 非 S0→409 STATE_409

        // 开放前置 BR-M9-18：批次 open_rate 已设（非空），未设→409 BIZ_OPEN_RATE_REQUIRED。
        BigDecimal openRate = snap.openRate();
        if (openRate == null) {
            throw new ApiException(BizError.BIZ_OPEN_RATE_REQUIRED, "批次未设开放抢单付佣比例");
        }

        Transition t = new Transition(
                snap.status(), snap.pool(), null,
                CaseStateService.ST_PENDING_DISPATCH, CaseStateService.POOL_OPEN_POOL,
                null, "OPEN", null,
                null, null);
        int n = state.transition(caseId, t);
        if (n == 0) {
            throw new ApiException(BizError.STATE_409, "案件状态已变更，开放失败: " + caseId);
        }
        CaseSnapshot after = state.lockCase(caseId);
        state.audit(s, "case.open", caseId, null, snap, after);
        return ok();
    }

    private static Map<String, Object> ok() {
        return Map.of("ok", true);
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + id);
        }
    }
}
