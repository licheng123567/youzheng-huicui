package com.youzheng.huicui.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import org.slf4j.MDC;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * M3 CaseState 转移助手（dispatch 组共享件）。
 *
 * 核心范式：加载（行锁）→ 校验前置态 → 原子 CAS UPDATE → 写审计。所有 M3 写端点
 * （dispatch/redispatch/accept/reject/assign/claim/release/return/open-for-claim）
 * 与三个定时系统态（T_collector 自动释放 / T2 自动退回 / 停用触发释放）共用此助手，
 * 仅 actor/reason 不同。真值源：openapi-core.yaml(dispatch tag + /sea)、PRD 03(BR-M3-*)、
 * PRD 09(BR-M9-18)、ERD(CaseStatus/Pool)。
 *
 * 两轴定态：case.status(CaseStatus) × case.pool(Pool)。M3 只在 (status,pool) 组合上转移，
 * legal_stage 不参与（D2 正交）。合法稳态见 StatePair 常量。
 *
 * 表/列对齐：表名 "case" 须双引号；批次归属/比率读 batch(provider_id/pay_out_rate/open_rate/comm_in_rate)；
 * 释放回流判据用 case.origin_pool（V3 迁移新增）。
 */
@Service
public class CaseStateService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CaseStateService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ── CaseStatus / Pool 常量（与 V1 chk 约束一致）──────────────────────────
    public static final String ST_PENDING_DISPATCH = "PENDING_DISPATCH";
    public static final String ST_PROVIDER_SEA      = "PROVIDER_SEA";
    public static final String ST_IN_PROGRESS       = "IN_PROGRESS";

    public static final String POOL_PLATFORM_SEA = "PLATFORM_SEA";
    public static final String POOL_PROVIDER_SEA = "PROVIDER_SEA";
    public static final String POOL_OPEN_POOL    = "OPEN_POOL";
    public static final String POOL_PRIVATE      = "PRIVATE";

    // 合法稳态（M3 关心）：
    public static final StatePair S0 = new StatePair(ST_PENDING_DISPATCH, POOL_PLATFORM_SEA); // 待派单（平台公海）
    public static final StatePair S1 = new StatePair(ST_PENDING_DISPATCH, POOL_PROVIDER_SEA); // 待接单（已派，待接/拒）
    public static final StatePair S2 = new StatePair(ST_PROVIDER_SEA,      POOL_PROVIDER_SEA); // 服务商公海
    public static final StatePair S3 = new StatePair(ST_IN_PROGRESS,       POOL_PRIVATE);      // 私海进行中
    public static final StatePair S4 = new StatePair(ST_PENDING_DISPATCH,  POOL_OPEN_POOL);    // 开放抢单池

    // ── 配置默认（settings 缺省兜底）────────────────────────────────────────
    private static final int DEFAULT_HOLD_CAP = 50;

    // ── records ─────────────────────────────────────────────────────────────

    /** 案件快照（含 batch 派生：provider_id/pay_out_rate/open_rate）。 */
    public record CaseSnapshot(long id, long batchId, String status, String pool,
                               Long holderId, String source, String originPool,
                               Long providerId, BigDecimal payOutRate, BigDecimal openRate) {}

    /** (status,pool) 稳态对。 */
    public record StatePair(String status, String pool) {}

    /**
     * 原子转移意图：CAS 风格 UPDATE，WHERE 带前置 status/pool(+holder_id 期望)防并发覆盖。
     * expectHolderId=null → 要求 holder_id IS NULL（占用前置）；非 null → 要求等于该值（持有人本人）。
     * t2Deadline/tcDeadline 传 null 表示「清空」该列（写 NULL）。
     */
    public record Transition(String fromStatus, String fromPool, Long expectHolderId,
                             String toStatus, String toPool, Long newHolderId, String newSource,
                             String newOriginPool, Instant t2Deadline, Instant tcDeadline) {}

    // ── ① 行级锁加载 ─────────────────────────────────────────────────────────

    /** SELECT ... FOR UPDATE 行级锁加载（须在 @Transactional 内调用）。不存在→404。 */
    public CaseSnapshot lockCase(long caseId) {
        try {
            return jdbc.queryForObject(
                    "SELECT c.id, c.batch_id, c.status, c.pool, c.holder_id, c.source, c.origin_pool,"
                            // 案件级归属唯一权威（不 COALESCE 回落 batch）：NULL=平台公海/无归属。
                            // 比率仍读 batch（计佣粒度=批次）。
                            + " c.provider_id AS provider_id, b.pay_out_rate, b.open_rate"
                            + " FROM \"case\" c JOIN batch b ON b.id = c.batch_id"
                            + " WHERE c.id = ? FOR UPDATE OF c",
                    (rs, i) -> new CaseSnapshot(
                            rs.getLong("id"),
                            rs.getLong("batch_id"),
                            rs.getString("status"),
                            rs.getString("pool"),
                            (Long) rs.getObject("holder_id"),
                            rs.getString("source"),
                            rs.getString("origin_pool"),
                            (Long) rs.getObject("provider_id"),
                            rs.getBigDecimal("pay_out_rate"),
                            rs.getBigDecimal("open_rate")),
                    caseId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + caseId);
        }
    }

    // ── ② 校验前置态 ─────────────────────────────────────────────────────────

    /** 当前 (status,pool) 必须∈ allowed，否则抛 STATE_409。 */
    public void requireState(CaseSnapshot snap, Set<StatePair> allowed) {
        StatePair cur = new StatePair(snap.status(), snap.pool());
        if (!allowed.contains(cur)) {
            throw new ApiException(BizError.STATE_409,
                    "非法状态转移：当前(" + snap.status() + "," + snap.pool() + ")不在允许前置态");
        }
    }

    // ── ③ 原子转移（CAS）────────────────────────────────────────────────────

    /**
     * CAS UPDATE：WHERE 带前置 status/pool(+holder_id 期望)。返回受影响行数；
     * 0 → 并发被覆盖（调用方决定抛 BIZ_ALREADY_CLAIMED 还是 STATE_409）。
     */
    public int transition(long caseId, Transition t) {
        StringBuilder sql = new StringBuilder(
                "UPDATE \"case\" SET status = ?, pool = ?, holder_id = ?, source = ?,"
                        + " origin_pool = ?, t2_deadline = ?, t_collector_deadline = ?, updated_at = now()"
                        + " WHERE id = ? AND status = ? AND pool = ?");
        Object[] args;
        if (t.expectHolderId() == null) {
            sql.append(" AND holder_id IS NULL");
            args = new Object[]{
                    t.toStatus(), t.toPool(), t.newHolderId(), t.newSource(), t.newOriginPool(),
                    toTs(t.t2Deadline()), toTs(t.tcDeadline()),
                    caseId, t.fromStatus(), t.fromPool()};
        } else {
            sql.append(" AND holder_id = ?");
            args = new Object[]{
                    t.toStatus(), t.toPool(), t.newHolderId(), t.newSource(), t.newOriginPool(),
                    toTs(t.t2Deadline()), toTs(t.tcDeadline()),
                    caseId, t.fromStatus(), t.fromPool(), t.expectHolderId()};
        }
        return jdbc.update(sql.toString(), args);
    }

    // ── ③b 案件级 provider 归属维护（V913 引入；与状态转移配对）──────────────
    //
    // 派单/承接 → setCaseProvider(承接 org)；退回/释放/回平台公海 → clearCaseProvider()。
    // 与 batch.provider_id 解耦：单案再派只改本案，不污染同批其他案件（修 codex BLOCKER）。
    // 须在对应 transition() 成功后、同事务内调用（行锁已持有）。

    /** 案件级承接 org 归属置为 providerId（派单/承接/抢单后调用）。 */
    public void setCaseProvider(long caseId, long providerId) {
        jdbc.update("UPDATE \"case\" SET provider_id = ?, updated_at = now() WHERE id = ?",
                providerId, caseId);
    }

    /** 清案件级承接 org 归属（退回平台公海后调用，回到 batch 推导/无归属）。 */
    public void clearCaseProvider(long caseId) {
        jdbc.update("UPDATE \"case\" SET provider_id = NULL, updated_at = now() WHERE id = ?",
                caseId);
    }

    // ── ④ 持有上限 ───────────────────────────────────────────────────────────

    /** 私海持有数 ≥ CFG-HOLDCAP → BIZ_HOLD_CAP。 */
    public void checkHoldCap(long collectorId) {
        if (holdCount(collectorId) >= holdCap()) {
            throw new ApiException(BizError.BIZ_HOLD_CAP, "催收员私海持有已达上限 CFG-HOLDCAP");
        }
    }

    /** 当前 CO 私海持有数：count(pool=PRIVATE AND holder_id=collectorId)。 */
    public int holdCount(long collectorId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM \"case\" WHERE pool = 'PRIVATE' AND holder_id = ?",
                Integer.class, collectorId);
        return n == null ? 0 : n;
    }

    /** CFG-HOLDCAP：读 settings ROTATION.holdCap（取最新版本），缺省 50。 */
    public int holdCap() {
        Integer cap = jdbc.query(
                "SELECT rotation ->> 'holdCap' AS hold_cap FROM settings"
                        + " WHERE domain = 'ROTATION' ORDER BY version DESC LIMIT 1",
                rs -> {
                    if (!rs.next()) return null;
                    String v = rs.getString("hold_cap");
                    if (v == null || v.isBlank()) return null;
                    try { return Integer.valueOf(v); } catch (NumberFormatException e) { return null; }
                });
        return cap == null ? DEFAULT_HOLD_CAP : cap;
    }

    // ── ⑤ 抢单专用（并发核心 BR-M3-07）──────────────────────────────────────

    /**
     * 行锁 + 复核 holder_id IS NULL + pool 判定（本商公海 S2 / 开放池 S4）+ checkHoldCap + transition。
     * 锁内已被占（holder_id 非空 / 非 S2,S4）→ BIZ_ALREADY_CLAIMED。归属 org=holderOrg(派生)。
     * tcSeconds=CFG-TC 由调用方传入。返回成功后的快照来源池供审计。
     */
    public CaseSnapshot claim(long caseId, CurrentSubject co, long tcSeconds) {
        long coId = Long.parseLong(co.accountId());
        long coOrg = Long.parseLong(co.orgId());

        CaseSnapshot snap = lockCase(caseId);

        // 锁内复核：必须仍 holder 为空且处 S2/S4。
        boolean isOpen   = S4.equals(new StatePair(snap.status(), snap.pool()));
        boolean isOwnSea = S2.equals(new StatePair(snap.status(), snap.pool()));
        if (snap.holderId() != null || (!isOpen && !isOwnSea)) {
            throw new ApiException(BizError.BIZ_ALREADY_CLAIMED, "案件已被占用或不可抢: " + caseId);
        }
        // 本商公海：仅本商可抢（scope 已裁剪，此处再核 provider 归属）。开放池跨商不校验归属。
        if (isOwnSea && (snap.providerId() == null || snap.providerId() != coOrg)) {
            throw new ApiException(BizError.PERM_403, "非本服务商公海案件，不可抢: " + caseId);
        }

        checkHoldCap(coId);

        String originPool = isOpen ? POOL_OPEN_POOL : POOL_PROVIDER_SEA;
        Transition t = new Transition(
                snap.status(), snap.pool(), null,
                ST_IN_PROGRESS, POOL_PRIVATE, coId, "CLAIM", originPool,
                null /*清 t2*/, Instant.now().plusSeconds(tcSeconds));
        int n = transition(caseId, t);
        if (n == 0) {
            // 锁内本不该到这，FOR UPDATE 已串行化；兜底按已被占处理。
            throw new ApiException(BizError.BIZ_ALREADY_CLAIMED, "案件已被占用: " + caseId);
        }
        return lockCase(caseId);
    }

    // ── ⑥ holderOrg 派生（开放池结算归属 BR-M3-04/M9-03）────────────────────

    /** holder 所属 org（account.org_id of holder_id）。无 holder → 抛 STATE_409。 */
    public long holderOrg(long caseId) {
        Long org = jdbc.query(
                "SELECT a.org_id FROM \"case\" c JOIN account a ON a.id = c.holder_id WHERE c.id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, caseId);
        if (org == null) {
            throw new ApiException(BizError.STATE_409, "案件无持有人，无法派生结算归属 org: " + caseId);
        }
        return org;
    }

    // ── ⑦ 释放去向判定（BR-M3-09）───────────────────────────────────────────

    /**
     * 按 origin_pool（优先）或 source 反推释放去向：
     *   origin_pool=OPEN_POOL（或 source 含开放池痕迹）→ 回 S4 开放池；
     *   否则 → 回 S2 服务商公海。
     * 调用方再补 t2Deadline（去向 S2 时按 CFG-T2 重置）。expectHolderId 由调用方在 transition 前补本人。
     */
    public Transition resolveReleaseTarget(CaseSnapshot snap, Long expectHolderId, Instant t2Deadline) {
        boolean toOpen = POOL_OPEN_POOL.equals(snap.originPool());
        if (toOpen) {
            // 回 S4 开放池：status=PENDING_DISPATCH, pool=OPEN_POOL，清 holder/计时，origin 清空。
            return new Transition(
                    snap.status(), snap.pool(), expectHolderId,
                    ST_PENDING_DISPATCH, POOL_OPEN_POOL, null, "OPEN", null,
                    null, null);
        }
        // 回 S2 服务商公海：status=PROVIDER_SEA, pool=PROVIDER_SEA，重置 t2，清 tc/holder/origin。
        return new Transition(
                snap.status(), snap.pool(), expectHolderId,
                ST_PROVIDER_SEA, POOL_PROVIDER_SEA, null, "RELEASE", null,
                t2Deadline, null);
    }

    // ── ⑧ 审计 ───────────────────────────────────────────────────────────────

    /**
     * 写 audit_log：每次成功转移后调用。action 形如 case.dispatch / case.claim；
     * target_type='case'；before/after 快照入 JSONB；proxy_for=代操作（actor 非案件归属侧时填，可空）。
     */
    public void audit(CurrentSubject actor, String action, long caseId, String reason,
                      CaseSnapshot before, CaseSnapshot after) {
        audit(actor, action, caseId, reason, before, after, null);
    }

    public void audit(CurrentSubject actor, String action, long caseId, String reason,
                      CaseSnapshot before, CaseSnapshot after, String proxyFor) {
        Long actorId = actor == null ? null : Long.valueOf(actor.accountId());
        String actorName = actor == null ? "system" : actor.name();
        String scope = actor == null ? "system" : actor.orgType();
        jdbc.update(
                "INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope,"
                        + " proxy_for, before_snap, after_snap, reason, trace_id)"
                        + " VALUES (?, ?, ?, ?, 'case', ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                actorId, actorName, action, "case#" + caseId, String.valueOf(caseId), scope,
                proxyFor, toJson(before), toJson(after), reason, MDC.get("traceId"));
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private static Timestamp toTs(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private String toJson(CaseSnapshot s) {
        if (s == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("batchId", s.batchId());
        m.put("status", s.status());
        m.put("pool", s.pool());
        m.put("holderId", s.holderId());
        m.put("source", s.source());
        m.put("originPool", s.originPool());
        m.put("providerId", s.providerId());
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }
}
