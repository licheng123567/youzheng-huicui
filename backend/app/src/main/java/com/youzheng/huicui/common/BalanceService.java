package com.youzheng.huicui.common;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 组织能力额度余额服务（v1.19.0·org_balance 权威源）。全仓余额读写的唯一入口。
 *
 * 【行锁范式（取代 pg_advisory_xact_lock）】lock() 用一条 upsert-lock SQL 同时完成
 *   「缺行建行 + 拿行锁 + 读余额」：
 *     INSERT ... ON CONFLICT (org_id,type) DO UPDATE SET updated_at = org_balance.updated_at RETURNING balance
 *   —— DO UPDATE（哪怕 no-op 赋值）才会对既存行加行锁并 RETURNING；DO NOTHING 不会。
 *   相比旧的 pg_advisory_xact_lock((int) orgId, type.hashCode())：无 int 截断、无 hash 碰撞、
 *   锁粒度精确到行、随 @Transactional 提交自动释放。调用方必须在 @Transactional 内。
 *
 * 【预付 vs 后付（BR-M9-10）】STT/SMS 预付：余额不足 → BIZ_QUOTA_EXHAUSTED(409)；
 *   EVIDENCE/LEGAL 后付：直接透支（余额可负=欠用记账，org_balance 无 CHECK(balance>=0)）。
 *
 * 【双写】org_balance（权威）+ recharge_log（流水，balance 列仍写"操作后快照"供对账可读）；
 *   charge 另写 billing_usage（用量明细，unit 走 BillingUnits.of）。
 *
 * 【归属口径】STT→承接服务商 COALESCE(case.provider_id, project.org_id)；
 *   SMS/EVIDENCE/LEGAL→物业 project.org_id（对齐 DevSeeder 既有种子口径）。见 billingOrgOf()。
 */
@Service
public class BalanceService {

    private final JdbcTemplate jdbc;

    public BalanceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 行锁 + 读余额（缺行建 0 行）。须在 @Transactional 内。 */
    public BigDecimal lock(long orgId, String type) {
        BigDecimal bal = jdbc.queryForObject(
                "INSERT INTO org_balance(org_id, type, balance, updated_at) VALUES (?, ?, 0, now())"
                        + " ON CONFLICT (org_id, type) DO UPDATE SET updated_at = org_balance.updated_at"
                        + " RETURNING balance",
                BigDecimal.class, orgId, type);
        return bal == null ? BigDecimal.ZERO : bal;
    }

    /** 只读余额（无锁）；缺行→0。 */
    public BigDecimal get(long orgId, String type) {
        BigDecimal bal = jdbc.query(
                "SELECT balance FROM org_balance WHERE org_id = ? AND type = ?",
                rs -> rs.next() ? rs.getBigDecimal("balance") : null, orgId, type);
        return bal == null ? BigDecimal.ZERO : bal;
    }

    /** 充值（+qty）：行锁 → 更新余额 → 记流水。返回新余额。 */
    public BigDecimal credit(long orgId, String type, BigDecimal qty, String ref, String note, Long operatedBy) {
        BigDecimal old = lock(orgId, type);
        BigDecimal now = old.add(qty);
        writeBalance(orgId, type, now);
        writeLog(orgId, type, qty, now, ref, note, operatedBy);
        return now;
    }

    /**
     * 扣费（-qty）+ 写用量明细。预付项余额不足 → BIZ_QUOTA_EXHAUSTED(409)（调用方事务回滚）；
     * 后付项（EVIDENCE/LEGAL）直接透支。返回新余额。
     */
    public BigDecimal charge(long orgId, String type, BigDecimal qty, Long caseId,
                             String ref, String note, Long operatedBy) {
        BigDecimal now = doCharge(orgId, type, qty, caseId, ref, note, operatedBy, true);
        return now;
    }

    /**
     * 扣费（批量循环专用）：预付项余额不足返 false（调用方 skip 本条，不整单失败），不抛异常。
     * 后付项恒 true。
     */
    public boolean tryCharge(long orgId, String type, BigDecimal qty, Long caseId,
                             String ref, String note, Long operatedBy) {
        BigDecimal old = lock(orgId, type);
        if (BillingUnits.isPrepaid(type) && old.compareTo(qty) < 0) {
            return false;
        }
        BigDecimal now = old.subtract(qty);
        writeBalance(orgId, type, now);
        writeLog(orgId, type, qty.negate(), now, ref, note, operatedBy);
        writeUsage(orgId, type, qty, caseId);
        return true;
    }

    private BigDecimal doCharge(long orgId, String type, BigDecimal qty, Long caseId,
                                String ref, String note, Long operatedBy, boolean throwOnShort) {
        BigDecimal old = lock(orgId, type);
        if (throwOnShort && BillingUnits.isPrepaid(type) && old.compareTo(qty) < 0) {
            throw new ApiException(BizError.BIZ_QUOTA_EXHAUSTED,
                    type + " 额度不足（余 " + old.toPlainString() + BillingUnits.of(type)
                            + "，需 " + qty.toPlainString() + BillingUnits.of(type) + "），请先充值");
        }
        BigDecimal now = old.subtract(qty);
        writeBalance(orgId, type, now);
        writeLog(orgId, type, qty.negate(), now, ref, note, operatedBy);
        writeUsage(orgId, type, qty, caseId);
        return now;
    }

    /**
     * 计费归属 org：STT→承接服务商（COALESCE(case.provider_id, project.org_id)，案件级归属权威）；
     * 其余（SMS/EVIDENCE/LEGAL）→物业 project.org_id。案件不存在→404。
     */
    public long billingOrgOf(long caseId, String type) {
        String col = BillingUnits.STT.equals(type)
                ? "COALESCE(c.provider_id, p.org_id)"
                : "p.org_id";
        Long org = jdbc.query(
                "SELECT " + col + " AS bill_org FROM \"case\" c JOIN project p ON p.id = c.project_id"
                        + " WHERE c.id = ?",
                rs -> rs.next() ? (Long) rs.getObject("bill_org") : null, caseId);
        if (org == null) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在或无计费归属组织: " + caseId);
        }
        return org;
    }

    private void writeBalance(long orgId, String type, BigDecimal balance) {
        jdbc.update("UPDATE org_balance SET balance = ?, updated_at = now() WHERE org_id = ? AND type = ?",
                balance, orgId, type);
    }

    /** 流水（recharge_log）：delta +充值/-扣减，balance=操作后快照（对账可读，非权威源）。 */
    private void writeLog(long orgId, String type, BigDecimal delta, BigDecimal balance,
                          String ref, String note, Long operatedBy) {
        jdbc.update(
                "INSERT INTO recharge_log(org_id, type, delta, balance, ref, note, operated_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                orgId, type, delta, balance, ref, note, resolveOperator(operatedBy));
    }

    /**
     * recharge_log.operated_by 是 NOT NULL（DDL V2）。扣费可能由系统链路触发而无显式 actor
     * （如 best-effort 短信、定时任务）——按 显式传入 → 当前主体 → 平台超管兜底 依次解析，
     * 绝不让 NULL 打到 NOT NULL 列上（否则 DataIntegrityViolation → 5xx）。
     */
    private Long resolveOperator(Long operatedBy) {
        if (operatedBy != null) return operatedBy;
        try {
            com.youzheng.huicui.security.CurrentSubject s = com.youzheng.huicui.security.SubjectContext.get();
            if (s != null && s.accountId() != null) return Long.valueOf(s.accountId());
        } catch (RuntimeException ignored) {
            // 无主体上下文（系统链路）→ 落到平台超管兜底
        }
        Long sa = jdbc.query("SELECT id FROM account WHERE role_template = 'SA' ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (sa == null) {
            throw new ApiException(BizError.STATE_409, "无可归属的计费操作人（缺平台账号）");
        }
        return sa;
    }

    /** 用量明细（billing_usage）：只量不金额（BR-M10-01）；unit 走 BillingUnits 单一真源。 */
    private void writeUsage(long orgId, String type, BigDecimal qty, Long caseId) {
        jdbc.update(
                "INSERT INTO billing_usage(org_id, type, qty, unit, case_id, occurred_at)"
                        + " VALUES (?, ?, ?, ?, ?, now())",
                orgId, type, qty, BillingUnits.of(type), caseId);
    }
}
