package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * M4 缴费链接/减免/回款组的 case 范围裁剪助手（scaffold 行锁范式，仿 CaseStateService）。
 * 仅本组（PayReduceRepayM4Controller）使用；不碰其他组/共享件。
 *
 * 提供两类 scope 复核——都先按存在性取案件（不存在→404），再按可见性裁剪（越界→403）：
 *
 *  requireCaseActor（x-data-scope=case-actor，BR-M4-01b/14/15 涉案动作）：
 *    精确语义＝持有催收员(CO) + 关联 PL/PC + SA 代操作可见。地基期 JWT 尚未携带「关联 PL/PC/SA 代」
 *    的精细映射，故先用组织级裁剪兜底（与 CasesM2Controller.appendRangeScope 同口径：物业按 project.org_id，
 *    服务商按 batch.provider_id，平台全量），并叠加 CO 本人持有可见。TODO：JWT 接入关联关系后收敛到行级 case-actor。
 *
 *  requireOwnOrg（x-data-scope=own-org，减免/回款标注/冲正）：
 *    平台全量；物业按 project.org_id=本组织；服务商按 batch.provider_id=本组织。越组织→403。
 *
 * 表/列严格对齐 V1 DDL：表名 "case" 双引号；JOIN project(org_id) / batch(provider_id)。
 * 金额列 due_cents / reduce_after_cents 原样以「分」(Long) 透出，由 controller 装配 DTO。
 */
@Service
public class CaseScopeM4Service {

    private final JdbcTemplate jdbc;

    public CaseScopeM4Service(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 案件最小视图（含 scope 派生 org_id/provider_id 与金额/状态/展示字段）。 */
    public record CaseRow(long id, long batchId, long projectId, Long orgId, Long providerId,
                          Long holderId, String status, long dueCents, Long reduceAfterCents,
                          String ownerName, String room) {}

    /** case-actor：存在→404 优先；可见性（组织级兜底 + CO 持有）不满足→403。 */
    public CaseRow requireCaseActor(CurrentSubject s, long caseId) {
        CaseRow c = load(caseId);                 // 不存在→404
        if (s.isPlatform()) return c;             // 平台全量
        // CO 本人持有该案 → 可见。
        Long actor = parseLong(s.accountId());
        if (actor != null && c.holderId() != null && c.holderId().equals(actor)) {
            return c;
        }
        // 组织级兜底（TODO：精确化关联 PL/PC、SA 代）。
        if (!withinOrg(s, c)) {
            throw new ApiException(BizError.PERM_403, "无权操作该案件");
        }
        return c;
    }

    /** own-org：存在→404 优先；越组织→403。平台全量。 */
    public CaseRow requireOwnOrg(CurrentSubject s, long caseId) {
        CaseRow c = load(caseId);                 // 不存在→404
        if (s.isPlatform()) return c;             // 平台全量
        if (!withinOrg(s, c)) {
            throw new ApiException(BizError.PERM_403, "无权操作该案件（越组织范围）");
        }
        return c;
    }

    /** 物业按 project.org_id；服务商按 batch.provider_id；其余非平台主体按 project.org_id 兜底。 */
    private boolean withinOrg(CurrentSubject s, CaseRow c) {
        Long org = parseLong(s.orgId());
        if (org == null) return false;
        if ("PROVIDER".equals(s.orgType())) {
            return c.providerId() != null && c.providerId().equals(org);
        }
        return c.orgId() != null && c.orgId().equals(org);
    }

    /** 取案件 + JOIN project(org_id)/batch(provider_id)。不存在→404。 */
    private CaseRow load(long caseId) {
        try {
            return jdbc.queryForObject(
                    "SELECT c.id, c.batch_id, c.project_id, p.org_id, b.provider_id, c.holder_id,"
                            + " c.status, c.due_cents, c.reduce_after_cents, c.owner_name, c.room"
                            + " FROM \"case\" c"
                            + " JOIN project p ON p.id = c.project_id"
                            + " JOIN batch b ON b.id = c.batch_id"
                            + " WHERE c.id = ?",
                    (rs, i) -> new CaseRow(
                            rs.getLong("id"),
                            rs.getLong("batch_id"),
                            rs.getLong("project_id"),
                            (Long) rs.getObject("org_id"),
                            (Long) rs.getObject("provider_id"),
                            (Long) rs.getObject("holder_id"),
                            rs.getString("status"),
                            rs.getLong("due_cents"),
                            (Long) rs.getObject("reduce_after_cents"),
                            rs.getString("owner_name"),
                            rs.getString("room")),
                    caseId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在");
        }
    }

    private static Long parseLong(String v) {
        try {
            return v == null ? null : Long.valueOf(v);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
