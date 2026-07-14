package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 法律保留（legal hold）。
 *
 * <p>正在**投诉 / 诉讼 / 监管检查**的案件，留存清理必须能暂停 ——
 * 否则定时任务会在你最需要证据的那一刻，把通话录音和业主身份一起删掉。
 * 置上之后，{@link com.youzheng.huicui.retention.RetentionService} 的 PII 去标识化与录音删除
 * 都会跳过该案件。
 *
 * <p><b>由物业发起</b>（产品决策）：他们是纠纷的第一线，也是这批个人信息的责任主体。
 * 权限点 {@code case.hold} 只给了 PL / PC。
 *
 * <p>置/撤都写审计：这是个能让数据「不被删」的开关，必须留痕谁在什么时候为什么开的 ——
 * 否则它就成了一个绕过留存策略的后门。
 */
@RestController
public class LegalHoldController {

    private final JdbcTemplate jdbc;
    private final CaseScopeM4Service scope;

    public LegalHoldController(JdbcTemplate jdbc, CaseScopeM4Service scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    /** POST /cases/{id}/legal-hold —— 置上法律保留（reason 必填：不写理由的保留没法审计）。 */
    @PostMapping("/cases/{id}/legal-hold")
    @RequirePermission("case.hold")
    @Transactional
    public Map<String, Object> setLegalHold(@PathVariable("id") String id,
                                            @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        scope.requireCaseActor(s, caseId);   // 不存在→404；非本物业→403

        Object r = body == null ? null : body.get("reason");
        String reason = r == null ? "" : String.valueOf(r).trim();
        if (reason.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "reason 必填：法律保留会让该案件的数据不被清理，必须写明理由");
        }

        Long actorId = Long.parseLong(s.accountId());
        jdbc.update("UPDATE \"case\" SET legal_hold = TRUE, legal_hold_reason = ?, legal_hold_by = ?,"
                + " legal_hold_at = now(), updated_at = now() WHERE id = ?", reason, actorId, caseId);

        audit(s, actorId, caseId, "case.legal-hold.set", reason);
        return Map.of("ok", true, "legalHold", true);
    }

    /** DELETE /cases/{id}/legal-hold —— 撤销保留，该案件重新进入正常的留存清理队列。 */
    @DeleteMapping("/cases/{id}/legal-hold")
    @RequirePermission("case.hold")
    @Transactional
    public Map<String, Object> releaseLegalHold(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        scope.requireCaseActor(s, caseId);

        Long actorId = Long.parseLong(s.accountId());
        jdbc.update("UPDATE \"case\" SET legal_hold = FALSE, legal_hold_reason = NULL, legal_hold_by = NULL,"
                + " legal_hold_at = NULL, updated_at = now() WHERE id = ?", caseId);

        audit(s, actorId, caseId, "case.legal-hold.release", "撤销法律保留");
        return Map.of("ok", true, "legalHold", false);
    }

    private void audit(CurrentSubject s, long actorId, long caseId, String action, String reason) {
        jdbc.update("INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, reason)"
                        + " VALUES (?, ?, ?, ?, 'case', ?, ?)",
                actorId, s.name(), action, "案件 " + caseId, String.valueOf(caseId), reason);
    }

    private long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + id);
        }
    }
}
