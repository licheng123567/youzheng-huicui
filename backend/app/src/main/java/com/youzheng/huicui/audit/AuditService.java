package com.youzheng.huicui.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.security.CurrentSubject;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 统一审计写入服务（members 组新建；抽 {@link com.youzheng.huicui.dispatch.RiskQcService#audit}
 * / {@link com.youzheng.huicui.dispatch.CaseStateService#audit} 的 audit_log 写入范式为可复用件）。
 *
 * 列对齐 V2 audit_log：actor_id/actor/action/target/target_type/target_id/scope/proxy_for/
 *   before_snap::jsonb/after_snap::jsonb/reason/trace_id（MDC traceId）。
 *
 * 敏感动作（member.create/disable/enable/reset_password/update/supervision）必落 audit_log，
 *   reset_password 的 after_snap 仅记 {passwordReset:true}，绝不记明文/哈希。
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 写一条审计。actor 为 null 视为系统触发（actor_id=NULL, actor='system'）。
     * scope 取 actor.orgType()；proxy_for 代操作场景填，否则 null。
     */
    public void write(CurrentSubject actor, String action, String targetType, String targetId,
                      String reason, Map<String, Object> before, Map<String, Object> after) {
        write(actor, action, targetType, targetId, reason, before, after, null);
    }

    public void write(CurrentSubject actor, String action, String targetType, String targetId,
                      String reason, Map<String, Object> before, Map<String, Object> after,
                      String proxyFor) {
        Long actorId = actor == null ? null : parseLongOrNull(actor.accountId());
        String actorName = actor == null ? "system" : actor.name();
        String scope = actor == null ? "system" : actor.orgType();
        String target = (targetType == null ? "" : targetType) + "#" + targetId;
        jdbc.update(
                "INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope,"
                        + " proxy_for, before_snap, after_snap, reason, trace_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                actorId, actorName, action, target, targetType, targetId, scope,
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
        try {
            return v == null ? null : Long.valueOf(v);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
