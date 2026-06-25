package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * M8 结案：POST /cases/{id}/close —— 手动撤案/坏账（CloseInput{kind,reason}，case.close）。
 * 结清(SETTLED)由 M4 回款标注 maybeSettle 自动判定（US-M4-08，非本端点）；结案脱敏由 M2 getCase 统一处理(BR-M8-09)。
 * 撤案/坏账仅留痕、不设审核流(BR-M2-17/M8)；无回款不产生佣金(BR-M9-05)。
 */
@RestController
public class CloseM8Controller {

    private static final Set<String> CLOSE_KINDS = Set.of("WITHDRAWN", "BAD_DEBT");
    // 已终态(不可再结案)
    private static final Set<String> TERMINAL = Set.of("SETTLED", "WITHDRAWN", "BAD_DEBT", "VOIDED");

    private final JdbcTemplate jdbc;
    private final CaseScopeM4Service scope;

    public CloseM8Controller(JdbcTemplate jdbc, CaseScopeM4Service scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    @PostMapping("/cases/{id}/close")
    @RequirePermission("case.close")
    @Transactional
    public Map<String, Object> closeCase(@PathVariable("id") String id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseCaseId(id);
        String kind = str(body, "kind");
        String reason = str(body, "reason");
        if (kind == null || !CLOSE_KINDS.contains(kind)) {
            throw new ApiException(BizError.VALIDATION_422, "kind 必填且须为 WITHDRAWN/BAD_DEBT");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "reason 必填（受控原因 CFG-CLOSE-REASONS）");
        }
        CaseScopeM4Service.CaseRow c = scope.requireOwnOrg(s, caseId);   // 不存在→404 / 越组织→403
        if (TERMINAL.contains(c.status())) {
            throw new ApiException(BizError.STATE_409, "案件已处终态(" + c.status() + ")，不可再结案");
        }
        // CAS：仅当仍非终态时更新，防并发重复结案。
        int n = jdbc.update(
                "UPDATE \"case\" SET status = ?, closed_kind = ?, closed_at = now(), updated_at = now()"
                        + " WHERE id = ? AND status NOT IN ('SETTLED','WITHDRAWN','BAD_DEBT','VOIDED')",
                kind, kind, caseId);
        if (n == 0) throw new ApiException(BizError.STATE_409, "案件状态已变更，结案未生效");
        audit(s, caseId, kind, reason);
        return Map.of("ok", true, "status", kind);
    }

    private void audit(CurrentSubject s, long caseId, String kind, String reason) {
        try {
            jdbc.update("INSERT INTO audit_log(actor, action, target, target_type, target_id, reason)"
                            + " VALUES (?, 'case.close', ?, 'case', ?, ?)",
                    s.name(), "case#" + caseId, String.valueOf(caseId), kind + "：" + reason);
        } catch (Exception ignore) { /* 审计失败不阻断主流程；列名兜底 */ }
    }

    private long parseCaseId(String id) {
        try { return Long.parseLong(id); }
        catch (NumberFormatException e) { throw new ApiException(BizError.NOT_FOUND_404, "案件不存在"); }
    }

    private String str(Map<String, Object> b, String k) {
        if (b == null) return null;
        Object v = b.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
