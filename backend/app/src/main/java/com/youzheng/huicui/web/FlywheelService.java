package com.youzheng.huicui.web;

import com.youzheng.huicui.audit.AuditService;
import com.youzheng.huicui.common.WilsonStats;
import com.youzheng.huicui.security.CurrentSubject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 话术飞轮「结算」——把真实通话结果（承诺兑现/回款）回流进话术库统计（BR-M5-12 环6）。
 *
 * 确定性、不依赖 LLM：靠 promise.script_id 归因链，按每条话术关联的承诺聚合出
 * uses/promise_rate/repay_rate/wilson 写回 script_lib。纯聚合 → 幂等，可反复跑。
 *
 * 只更新**有归因**的话术，不抹平专家录入的种子/待积累条目。
 * PR-2 追加 promoteEligible（达标 AI 变体自动晋升，环7）。
 */
@Service
public class FlywheelService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public FlywheelService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Result(int recomputed, int promoted) {}

    /** 全量结算：回流重算所有有归因承诺的话术。返回重算条数（promoted 由 PR-2 填，暂 0）。 */
    @Transactional
    public Result recomputeAll(CurrentSubject actor) {
        // 每条有归因 promise 的话术，一次聚合算出 uses/兑现率/回款率/wilson。
        //   uses         = 关联承诺数（战场使用量）
        //   fulfilled    = 兑现承诺数
        //   repay_cases  = 关联案件中存在非冲正回款的案件数 / 关联案件数
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.script_id AS sid,"
                        + " count(*) AS uses,"
                        + " count(*) FILTER (WHERE p.state = 'FULFILLED') AS fulfilled,"
                        + " count(DISTINCT p.case_id) AS cases,"
                        + " count(DISTINCT p.case_id) FILTER (WHERE EXISTS ("
                        + "     SELECT 1 FROM repay_line r WHERE r.case_id = p.case_id AND r.reversed = false"
                        + " )) AS repaid_cases"
                        + " FROM promise p"
                        + " WHERE p.script_id IS NOT NULL"
                        + " GROUP BY p.script_id");

        int n = 0;
        for (Map<String, Object> r : rows) {
            long sid = ((Number) r.get("sid")).longValue();
            long uses = ((Number) r.get("uses")).longValue();
            long fulfilled = ((Number) r.get("fulfilled")).longValue();
            long cases = ((Number) r.get("cases")).longValue();
            long repaidCases = ((Number) r.get("repaid_cases")).longValue();

            double promiseRate = uses > 0 ? Math.round((double) fulfilled / uses * 10000.0) / 10000.0 : 0.0;
            double repayRate = cases > 0 ? Math.round((double) repaidCases / cases * 10000.0) / 10000.0 : 0.0;
            double wilson = WilsonStats.lower3(fulfilled, uses);

            jdbc.update(
                    "UPDATE script_lib SET uses = ?, promise_rate = ?, repay_rate = ?, wilson = ?, updated_at = now()"
                            + " WHERE id = ?",
                    (int) uses, promiseRate, repayRate, wilson, sid);
            n++;
        }

        if (n > 0) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("recomputed", n);
            audit.write(actor, "ai.script.recompute", "script_lib", null,
                    "飞轮结算：按承诺归因回流重算 " + n + " 条话术统计", null, after);
        }
        return new Result(n, 0);
    }
}
