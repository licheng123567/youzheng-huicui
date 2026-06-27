package com.youzheng.huicui.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import org.slf4j.MDC;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M5 质检/风控领域服务（CaseStateService 同范式：加载→校验归属→原子写→审计 audit_log）。
 *
 * 真值源：openapi-core.yaml(qc tag)、PRD 05(BR-M5-07/07a/07b/07c)、V1__core_schema.sql
 * 21/22 节（risk_record / dispose_task 已建表）。
 *
 * 偏离 PLAN_SCHEMA（按实际 V1 表落地）：
 *   - risk_record 无 actor_org_id/actor_org_type/disposed/review_note 列；用 collector_id 派生违规人组织
 *     （account.org_id of collector_id），org.type 区分 PROVIDER/PROPERTY。处置归属判据 = subject.orgId
 *     == 违规人所属 org（BR-M5-07a 谁的员工谁处理）。
 *   - dispose_task 用 provider(org_id)/task_type/status/tm，无 created_by/done_at；risk_id 仅非唯一索引
 *     idx_dispose_risk_id（无 uq_dispose_task_risk），故复核建任务的幂等用「先查后插」兜底。
 *   - 组织侧 dispose 留痕：无 disposed 列，处置动作仅写 audit_log（qc.dispose）留痕，不改 risk_record 行。
 *
 * 处置归属 canDispose（BR-M5-07a）：subject.orgId == actorOrg(risk) 且有 qc.dispose（拦截器已校验权限）。
 *   催收员风险(actorOrg.type=PROVIDER) → 仅该服务商负责人 VL 可 dispose；
 *   物业协调员风险(actorOrg.type=PROPERTY) → 仅该物业负责人 PL 可 dispose；
 *   非本组织风险 → 仅只读 + escalate（qc.escalate），dispose 一律 403 PERM_403。
 *
 * 平台只复核不处置（BR-M5-07c）：reviewRisk 写 reviewed/reviewed_by/reviewed_at；
 *   CONFIRMED/ESCALATED → 建 dispose_task（provider=违规人组织 org，幂等先查后插）；
 *   FALSE_POSITIVE → 撤销风险（reviewed=FALSE_POSITIVE，不建任务；已 PENDING 任务置 DONE 留痕）。
 *
 * 三方隔离 scope（x-data-scope=range，复用 CasesM2Controller.appendRangeScope 思路落到 risk）：
 *   平台→全量；物业(PROPERTY)→risk JOIN case→project p.org_id=本组织；
 *   服务商(PROVIDER)→risk JOIN case→batch b.provider_id=本组织。
 *   （可见性按案件归属侧裁剪：物业看本项目所有风险含他商催收员风险→上报；
 *    collector 派生组织仅用于 dispose 可处置判定，不用于列表裁剪。）
 */
@Service
public class RiskQcService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RiskQcService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ── 风险快照（含违规人组织派生：actorOrgId/actorOrgType）─────────────────────
    public record RiskSnapshot(long id, long caseId, long collectorId,
                               long providerId, long propertyId,
                               String type, String level, String reviewed,
                               long actorOrgId, String actorOrgType) {}

    // ── ① 加载（带违规人组织派生）。不存在→404 ──────────────────────────────────
    /** 无 scope 加载用于存在性判定；越 scope 可见性由调用方单独校验（先存在后可见，参 getCase）。 */
    public RiskSnapshot loadRisk(long riskId) {
        try {
            return jdbc.queryForObject(
                    "SELECT r.id, r.case_id, r.collector_id, r.provider_id, r.property_id,"
                            + " r.type, r.level, r.reviewed,"
                            + " a.org_id AS actor_org_id, o.type AS actor_org_type"
                            + " FROM risk_record r"
                            + " JOIN account a ON a.id = r.collector_id"
                            + " JOIN org o ON o.id = a.org_id"
                            + " WHERE r.id = ?",
                    (rs, i) -> new RiskSnapshot(
                            rs.getLong("id"),
                            rs.getLong("case_id"),
                            rs.getLong("collector_id"),
                            rs.getLong("provider_id"),
                            rs.getLong("property_id"),
                            rs.getString("type"),
                            rs.getString("level"),
                            rs.getString("reviewed"),
                            rs.getLong("actor_org_id"),
                            rs.getString("actor_org_type")),
                    riskId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "风险记录不存在: " + riskId);
        }
    }

    // ── ② range 可见性（三方隔离）。越 scope → false ────────────────────────────
    /** 平台全量；物业按 project.org_id；服务商按 batch.provider_id（落到风险所属案件归属侧）。 */
    public boolean visibleByRange(CurrentSubject s, long riskId) {
        if (!s.isPlatform() && parseOrgIdOrNull(s) == null) return false;
        // SA 全量 / SE 三维 data_range / PROVIDER c.provider_id / PL p.org_id / PC 行级协调集（统一收口）。
        StringBuilder where = new StringBuilder(" WHERE r.id = ?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(riskId);
        com.youzheng.huicui.common.DataScope.appendRange(
                s, where, args, "c.provider_id", "p.org_id", "p.area", "c.project_id", "c.batch_id");
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM risk_record r"
                        + " JOIN \"case\" c ON c.id = r.case_id"
                        + " JOIN project p ON p.id = c.project_id"
                        + " JOIN batch b ON b.id = c.batch_id" + where,
                Long.class, args.toArray());
        return n != null && n > 0;
    }

    /** 先存在后可见：不存在→404，越 scope→404（不可见即不存在，避免存在性泄漏）。 */
    public RiskSnapshot loadVisibleRisk(CurrentSubject s, long riskId) {
        RiskSnapshot risk = loadRisk(riskId);   // 不存在→404
        if (!visibleByRange(s, riskId)) {
            throw new ApiException(BizError.NOT_FOUND_404, "风险记录不存在: " + riskId);
        }
        return risk;
    }

    // ── ③ 处置归属判定（BR-M5-07a 谁的员工谁处理）────────────────────────────────
    /** subject.orgId == 违规人所属 org → 可实质处置；否则非本组织员工，dispose 一律 403。 */
    public boolean canDispose(CurrentSubject s, RiskSnapshot risk) {
        if (s.isPlatform()) return false;       // 平台只复核不处置（BR-M5-07c）
        Long org = parseOrgIdOrNull(s);
        return org != null && org == risk.actorOrgId();
    }

    // ── ④ 审计 ──────────────────────────────────────────────────────────────────
    /** 写 audit_log：action 形如 qc.dispose/qc.escalate/qc.review；target_type='risk'。 */
    public void audit(CurrentSubject actor, String action, long riskId, String reason,
                      Map<String, Object> before, Map<String, Object> after) {
        Long actorId = actor == null ? null : Long.valueOf(actor.accountId());
        String actorName = actor == null ? "system" : actor.name();
        String scope = actor == null ? "system" : actor.orgType();
        jdbc.update(
                "INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope,"
                        + " proxy_for, before_snap, after_snap, reason, trace_id)"
                        + " VALUES (?, ?, ?, ?, 'risk', ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                actorId, actorName, action, "risk#" + riskId, String.valueOf(riskId), scope,
                null, toJson(before), toJson(after), reason, MDC.get("traceId"));
    }

    /** risk 快照 → audit before/after 用 Map。 */
    public Map<String, Object> snapMap(RiskSnapshot r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("caseId", r.caseId());
        m.put("collectorId", r.collectorId());
        m.put("level", r.level());
        m.put("type", r.type());
        m.put("reviewed", r.reviewed());
        m.put("actorOrgId", r.actorOrgId());
        m.put("actorOrgType", r.actorOrgType());
        return m;
    }

    private String toJson(Map<String, Object> m) {
        if (m == null) return null;
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseOrgIdOrNull(CurrentSubject s) {
        try {
            return s.orgId() == null ? null : Long.valueOf(s.orgId());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
