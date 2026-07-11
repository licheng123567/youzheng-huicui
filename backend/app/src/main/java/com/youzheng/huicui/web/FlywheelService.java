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

    /** 全量结算：先回流重算（uses/wilson 最新），再对达标 AI 变体自动晋升（环7）。 */
    @Transactional
    public Result recomputeAll(CurrentSubject actor) {
        int recomputed = doRecompute(actor);
        int promoted = promoteEligible(actor);
        return new Result(recomputed, promoted);
    }

    private int doRecompute(CurrentSubject actor) {
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
        return n;
    }

    /**
     * 达标 AI 变体自动晋升（环7·BR-M5-12a）：
     *   source=AI_MINED 且 status=CANDIDATE 且 variant.state=WINNER，uses≥minUses 且 uplift≥minUplift
     *   → status=EFFECTIVE、variant.state=PROMOTED（保留 text 可回滚）。
     * source=EXPERT 永不自动（仍走人工 promoteScriptVariant）。阈值取 ai_config.flywheel.trigger。
     */
    private int promoteEligible(CurrentSubject actor) {
        long[] th = parseTrigger();
        long minUses = th[0];
        double minUplift = th[1] / 10000.0;   // parseTrigger 返回 uplift×10000 的整数，避免 double 传递

        List<Map<String, Object>> cands = jdbc.queryForList(
                "SELECT id, uses, (variant->>'uplift')::numeric AS uplift,"
                        + " status, variant->>'state' AS vstate"
                        + " FROM script_lib"
                        + " WHERE source = 'AI_MINED' AND status = 'CANDIDATE'"
                        + "   AND variant->>'state' = 'WINNER'"
                        + "   AND uses >= ?"
                        + "   AND (variant->>'uplift')::numeric >= ?"
                        + " FOR UPDATE",
                minUses, minUplift);

        int promoted = 0;
        for (Map<String, Object> c : cands) {
            long sid = ((Number) c.get("id")).longValue();
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("status", c.get("status"));
            before.put("variantState", c.get("vstate"));
            jdbc.update(
                    "UPDATE script_lib SET status = 'EFFECTIVE',"
                            + " variant = jsonb_set(variant, '{state}', '\"PROMOTED\"'), updated_at = now()"
                            + " WHERE id = ?", sid);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", "EFFECTIVE");
            after.put("variantState", "PROMOTED");
            audit.write(actor, "ai.script.variant.autopromote", "script_lib", String.valueOf(sid),
                    "达标 AI 变体自动晋升为现行（uses≥" + minUses + " 且 uplift≥" + minUplift + "，保留旧版可回滚）",
                    before, after);
            promoted++;
        }
        return promoted;
    }

    /** 解析 ai_config.flywheel.trigger("uses>=N AND wilson_uplift>=X")→[minUses, uplift×10000]；失败取默认 300/0.02。 */
    private long[] parseTrigger() {
        long minUses = 300;
        long upliptX10000 = 200;   // 0.02
        try {
            String trigger = jdbc.query(
                    "SELECT value->'flywheel'->>'trigger' FROM settings WHERE domain='AI' ORDER BY version DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : null);
            if (trigger != null) {
                java.util.regex.Matcher u = java.util.regex.Pattern.compile("uses\\s*>=\\s*(\\d+)").matcher(trigger);
                if (u.find()) minUses = Long.parseLong(u.group(1));
                java.util.regex.Matcher up = java.util.regex.Pattern.compile("uplift\\s*>=\\s*([0-9.]+)").matcher(trigger);
                if (up.find()) upliptX10000 = Math.round(Double.parseDouble(up.group(1)) * 10000);
            }
        } catch (RuntimeException ignore) { /* 解析失败取默认，绝不 5xx */ }
        return new long[]{minUses, upliptX10000};
    }

    /** 单条重算（promoteScriptVariant 晋升后回填其 wilson/rates，兑现"以变体实测回填"承诺）。 */
    @Transactional
    public void recomputeOne(long scriptId) {
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT count(*) AS uses, count(*) FILTER (WHERE state='FULFILLED') AS fulfilled,"
                        + " count(DISTINCT case_id) AS cases,"
                        + " count(DISTINCT case_id) FILTER (WHERE EXISTS ("
                        + "   SELECT 1 FROM repay_line r WHERE r.case_id = promise.case_id AND r.reversed = false)) AS repaid"
                        + " FROM promise WHERE script_id = ?", scriptId);
        long uses = ((Number) r.get("uses")).longValue();
        if (uses == 0) return;   // 无归因不动
        long fulfilled = ((Number) r.get("fulfilled")).longValue();
        long cases = ((Number) r.get("cases")).longValue();
        long repaid = ((Number) r.get("repaid")).longValue();
        jdbc.update("UPDATE script_lib SET uses=?, promise_rate=?, repay_rate=?, wilson=?, updated_at=now() WHERE id=?",
                (int) uses,
                Math.round((double) fulfilled / uses * 10000.0) / 10000.0,
                cases > 0 ? Math.round((double) repaid / cases * 10000.0) / 10000.0 : 0.0,
                WilsonStats.lower3(fulfilled, uses),
                scriptId);
    }
}
