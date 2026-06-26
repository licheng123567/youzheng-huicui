package com.youzheng.huicui.dispatch;

import com.youzheng.huicui.dispatch.CaseStateService.CaseSnapshot;
import com.youzheng.huicui.dispatch.CaseStateService.Transition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 公海定时器自动到期（CFG-T2/TC·BR-M3-08/13）。补"设计说自动、实际从未执行"的定时任务缺口。
 *   expireTC：私海(S3)持有无跟进超 CFG-TC → 自动释放回 S2/S4（有进行中分期承诺暂缓 BR-M8-11）。
 *   expireT2：服务商公海(S2)滞留超 CFG-T2 → 自动退回平台公海 S0（source=RETURN，与手动退回同口径）。
 * 复用 CaseStateService.transition() 的 CAS（幂等：状态已变则 0 行，重复跑无副作用）；逐案事务 + 留痕 activity STATUS。
 * 注：T1 待派单初始即 S0(平台公海)，超时不改状态（仅预警，见 workbench），故此处不做 T1 转移。
 */
@Service
public class ExpiryService {

    private final JdbcTemplate jdbc;
    private final CaseStateService state;

    private static final long DEFAULT_T2_SECONDS = 7L * 24 * 3600;

    public ExpiryService(JdbcTemplate jdbc, CaseStateService state) {
        this.jdbc = jdbc;
        this.state = state;
    }

    public record ExpiryResult(int releasedTC, int returnedT2) {}

    public ExpiryResult runExpiry() {
        return new ExpiryResult(expireTC(), expireT2());
    }

    /** CFG-TC：私海持有无跟进超时 → 自动释放（暂缓含进行中分期承诺者）。 */
    @Transactional
    public int expireTC() {
        List<Long> due = jdbc.queryForList(
            "SELECT id FROM \"case\" WHERE pool = 'PRIVATE' AND closed_at IS NULL"
                + " AND t_collector_deadline IS NOT NULL AND t_collector_deadline < now()", Long.class);
        long t2 = t2Seconds();
        int n = 0;
        for (Long id : due) {
            if (hasActiveInstallment(id)) continue;            // BR-M8-11 暂缓
            CaseSnapshot snap = state.lockCase(id);
            if (snap == null || !CaseStateService.POOL_PRIVATE.equals(snap.pool())) continue;
            Transition t = state.resolveReleaseTarget(snap, snap.holderId(), Instant.now().plusSeconds(t2));
            if (state.transition(id, t) > 0) {
                writeActivity(id, "自动释放：持有催收员无跟进超时（CFG-TC）");
                n++;
            }
        }
        return n;
    }

    /** CFG-T2：服务商公海滞留超时 → 自动退回平台公海 S0（清 holder/计时，source=RETURN）。 */
    @Transactional
    public int expireT2() {
        List<Long> due = jdbc.queryForList(
            "SELECT id FROM \"case\" WHERE status = 'PROVIDER_SEA' AND pool = 'PROVIDER_SEA'"
                + " AND closed_at IS NULL AND t2_deadline IS NOT NULL AND t2_deadline < now()", Long.class);
        int n = 0;
        for (Long id : due) {
            CaseSnapshot snap = state.lockCase(id);
            if (snap == null) continue;
            Transition t = new Transition(
                    snap.status(), snap.pool(), null,           // S2 holder 为空
                    CaseStateService.ST_PENDING_DISPATCH, CaseStateService.POOL_PLATFORM_SEA, null,
                    "RETURN", null, null /*清 t2*/, null /*清 tc*/);
            if (state.transition(id, t) > 0) {
                writeActivity(id, "自动退回平台公海：服务商公海滞留超时（CFG-T2）");
                n++;
            }
        }
        return n;
    }

    private boolean hasActiveInstallment(long caseId) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM promise_installment pi JOIN promise p ON p.id = pi.promise_id"
                + " WHERE p.case_id = ? AND pi.state = 'PENDING'", Integer.class, caseId);
        return n != null && n > 0;
    }

    private void writeActivity(long caseId, String content) {
        jdbc.update("INSERT INTO activity(case_id, type, actor_id, content, ref_type, ref_id)"
                + " VALUES (?, 'STATUS', NULL, ?, 'case', ?)", caseId, content, caseId);
    }

    private long t2Seconds() {
        try {
            Long v = jdbc.query("SELECT timers ->> 't2Seconds' AS v FROM settings WHERE domain = 'TIMERS' ORDER BY version DESC LIMIT 1",
                rs -> { if (!rs.next()) return null; String r = rs.getString("v"); try { return r == null ? null : Long.valueOf(r.trim()); } catch (NumberFormatException e) { return null; } });
            return v == null ? DEFAULT_T2_SECONDS : v;
        } catch (RuntimeException e) { return DEFAULT_T2_SECONDS; }
    }
}
