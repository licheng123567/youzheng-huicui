package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.security.CurrentSubject;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * orgs-system 组轻量审计写入器（抽 RiskQcService.audit 范式：actor/action/target/target_type/target_id
 * + before/after 快照 JSONB + trace_id）。
 *
 * 敏感动作（org.create / org.owner.rebind / reset-password 等）必落 audit_log（BR-M1-08/15）。
 * 与 RiskQcService.audit 同列名口径，写 audit_log(actor_id, actor, action, target, target_type,
 * target_id, scope, proxy_for, before_snap, after_snap, reason, trace_id)。tm/created_at 走 DEFAULT now()。
 *
 * 只承载本组写入，独立于其他组的 service（不复用 RiskQcService，避免跨组耦合 target_type 固定为 'risk'）。
 */
@Service
public class OrgSystemAuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OrgSystemAuditService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 写 audit_log。actor 取当前主体（accountId/name/orgType 派生 actor_id/actor/scope）。
     * targetId 为 string（兼容多类型）；before/after 为快照 Map（可 null）；reason/proxyFor 可 null。
     */
    public void write(CurrentSubject actor, String action, String targetType, String targetId,
                      String scope, String proxyFor, String reason,
                      Map<String, Object> before, Map<String, Object> after) {
        Long actorId = actor == null ? null : parseLongOrNull(actor.accountId());
        String actorName = actor == null ? "system" : actor.name();
        String effScope = scope != null ? scope : (actor == null ? "system" : actor.orgType());
        String target = (targetType == null ? "" : targetType) + "#" + targetId;
        jdbc.update(
                "INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope,"
                        + " proxy_for, before_snap, after_snap, reason, trace_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                actorId, actorName, action, target, targetType, targetId, effScope,
                proxyFor, toJson(before), toJson(after), reason, MDC.get("traceId"));
    }

    private String toJson(Map<String, Object> m) {
        if (m == null) return null;
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseLongOrNull(String v) {
        if (v == null) return null;
        try {
            return Long.valueOf(v);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
