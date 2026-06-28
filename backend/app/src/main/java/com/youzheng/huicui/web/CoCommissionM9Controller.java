package com.youzheng.huicui.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.Commission;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.CoCommissionBatchRowM9Dto;
import com.youzheng.huicui.web.dto.CoCommissionPersonM9Dto;
import com.youzheng.huicui.web.dto.CoPayDocLineM9Dto;
import com.youzheng.huicui.web.dto.CoPayDocM9Dto;
import com.youzheng.huicui.web.dto.MySettlementM9Dto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M9 组2：催收员佣金（服务商内部·BR-M9-19）。控制器 CoCommissionM9Controller。
 * 类名带 M9 后缀，与 M1-M4 物理隔离；只承载本组端点，不碰共享件/其他组/pom。
 *
 * 资金双线之 OUT 线（付佣）独立的「服务商内部提成」账：物业/平台不可见（强约束）。
 * 全端点 x-data-scope=own-org + service 层强制 PROVIDER-only（平台/物业访问 → 403 PERM_403）。
 *
 * 端点（基路径 /v1 由 context-path 提供，注解写裸路径）：
 *   GET  /co-commissions                                  listCoCommissions   | perm=cocomm.manage    | 200 CoCommissionPersonPage
 *   GET  /co-commissions/{collectorId}/batches            listCoCommissionBatches | perm=cocomm.manage | 200/404（穿透下钻 M-05）
 *   PUT  /co-commissions/{collectorId}/batches/{batchId}/rate setCoCommissionRate | perm=cocomm.manage | 200/422(防倒挂)
 *   GET  /co-pay-docs                                     listCoPayDocs       | perm=cocomm.manage    | 200 CoPayDocPage
 *   POST /co-pay-docs                                     createCoPayDoc      | perm=cocomm.manage 幂等| 201/409/422
 *   GET  /co-pay-docs/{id}                                getCoPayDoc         | perm=cocomm.manage    | 200/404（含 lines 快照）
 *   POST /co-pay-docs/{id}/confirm-pay                    confirmCoPayDoc     | perm=cocomm.manage 幂等| 200/404/409
 *   GET  /me/settlement                                   getMySettlement     | perm=cocomm.self.view | 200（本人只读）
 *
 * 优雅降级（绝不 5xx）：路径 id 非法形态/不存在 → 404；越线/越 scope → 403；
 *   状态不允许 → 409（已 SETTLED 重复确认 / 明细已被占用锁定）；缺必填/防倒挂 → 422。
 *
 * 金额一律 *_cents（Long，分）原样返回；rate 为 NUMERIC(6,4) 分数（0-1），不×100。
 * 内部「已结」判定走 co_pay_doc.status=SETTLED（经 co_pay_doc_line 关联），不污染平台双线 repay_line.settled。
 */
@RestController
public class CoCommissionM9Controller {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CoCommissionM9Controller(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    // ── [1] GET /co-commissions ──────────────────────────────────────────────
    // 按催收员聚合；dueCents/settledCents/unsettledCents 由 repay_line × rate 实时汇总（非存储）。
    @GetMapping("/co-commissions")
    @RequirePermission("cocomm.manage")
    public Page<CoCommissionPersonM9Dto> listCoCommissions(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        long providerOrgId = requireProvider();
        Pageable pg = Pageable.of(page, size);

        // 聚合维度=催收员（在本服务商 org 内、且已被设过任一批次比例的 collector）。
        // batch.provider_id = 本商，确保只统计本商承接批次（OUT 线隔离）。
        String base = "FROM co_commission cc"
                + " JOIN account a ON a.id = cc.collector_id"
                + " JOIN batch b ON b.id = cc.batch_id"
                + " WHERE a.org_id = ? AND b.provider_id = ?";

        Long total = jdbc.queryForObject(
                "SELECT count(DISTINCT cc.collector_id) " + base,
                Long.class, providerOrgId, providerOrgId);

        // 单条聚合 SQL（消除 N+1·原 listCoCommissionBatches 同口径，聚合维度由 batch 换 collector）：
        //   按 cc.collector_id 分组，一次性算出每催收员 batchCount/dueCents/settledCents/unsettledCents。
        //   口径与原 aggregatePerson 逐人逐批逐笔计算严格一致：
        //   - 可计提明细 = 该(collector,batch)下、到账时点本催收员持有(rl.collector_id_at_repay)、
        //     未冲正(rl.reversed=false)的回款；LEFT JOIN 保证“设过比例但暂无明细”的批次仍计入 batchCount。
        //   - batchCount = COUNT(DISTINCT cc.batch_id)，= 原 loadRateBatches.size()（设过比例的批次数，无论有无明细）。
        //   - 逐笔佣金 = round(rl.amount_cents × cc.rate)（HALF_UP 整数分；金额/比率非负 ⇒ PG round 与
        //     Java Commission.lineCommissionCents 等价），先逐笔舍入再 SUM（绝不汇总后再舍入）。
        //   - “已结” = 该回款明细被某 SETTLED 的 co_pay_doc 关联（不看 repay_line.settled）；
        //     dueCents=全部、settledCents=已结、unsettledCents=due-settled。
        //   只对本页 collector 聚合（先分页选出 collector，再 JOIN 聚合）；ORDER BY collector_id 同原顺序。
        String aggSql =
                "SELECT cc.collector_id,"
                        + "       a.name AS collector_name,"
                        + "       count(DISTINCT cc.batch_id) AS batch_count,"
                        + "       COALESCE(SUM(round(rl.amount_cents * cc.rate))::bigint, 0) AS due_cents,"
                        + "       COALESCE(SUM(CASE WHEN rl.id IS NOT NULL AND EXISTS ("
                        + "             SELECT 1 FROM co_pay_doc_line cpl"
                        + "               JOIN co_pay_doc d ON d.id = cpl.co_pay_doc_id"
                        + "              WHERE cpl.repay_line_id = rl.id AND d.status = 'SETTLED')"
                        + "           THEN round(rl.amount_cents * cc.rate) ELSE 0 END)::bigint, 0)"
                        + "         AS settled_cents"
                        + " FROM co_commission cc"
                        + " JOIN account a ON a.id = cc.collector_id"
                        + " JOIN batch b ON b.id = cc.batch_id"
                        + " LEFT JOIN repay_line rl ON rl.batch_id = cc.batch_id"
                        + "        AND rl.collector_id_at_repay = cc.collector_id AND rl.reversed = false"
                        + " WHERE a.org_id = ? AND b.provider_id = ?"
                        + "   AND cc.collector_id IN ("
                        + "       SELECT cc2.collector_id FROM co_commission cc2"
                        + "         JOIN account a2 ON a2.id = cc2.collector_id"
                        + "         JOIN batch b2 ON b2.id = cc2.batch_id"
                        + "        WHERE a2.org_id = ? AND b2.provider_id = ?"
                        + "        GROUP BY cc2.collector_id ORDER BY cc2.collector_id"
                        + "        LIMIT ? OFFSET ?)"
                        + " GROUP BY cc.collector_id, a.name"
                        + " ORDER BY cc.collector_id";

        List<CoCommissionPersonM9Dto> items = jdbc.query(aggSql,
                (rs, i) -> {
                    long due = rs.getLong("due_cents");
                    long settled = rs.getLong("settled_cents");
                    return new CoCommissionPersonM9Dto(
                            String.valueOf(rs.getLong("collector_id")),
                            rs.getString("collector_name"),
                            rs.getInt("batch_count"),
                            due, settled, due - settled);
                },
                providerOrgId, providerOrgId, providerOrgId, providerOrgId, pg.size, pg.offset);

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [1b] GET /co-commissions/{collectorId}/batches ───────────────────────
    // M-05 穿透下钻：某催收员按批次的应结/未结佣金（人聚合 → 批次级）。own-org：催收员须属本商。
    // 复用 loadRateBatches（人×批次设过比例）+ loadActiveLinesForCollectorBatch（批次内本人持有未冲正回款）。
    @GetMapping("/co-commissions/{collectorId}/batches")
    @RequirePermission("cocomm.manage")
    public List<CoCommissionBatchRowM9Dto> listCoCommissionBatches(
            @PathVariable("collectorId") String collectorIdRaw) {
        long providerOrgId = requireProvider();
        long collectorId = parseId(collectorIdRaw, "催收员不存在");
        // own-org 复核：催收员须属本商。不存在 → 404；越组织 → 403。
        requireCollectorOfProvider(collectorId, providerOrgId);

        // 单条聚合 SQL（消除 N+1）：按 cc.batch_id 分组，一次性算出每批次
        //   dueCents / unsettledCents / unsettledLineCount。口径与原逐笔计算严格一致：
        //   - BLOCKER-1·按到账快照归属：可计提明细 = 该批次下、到账时点本催收员持有
        //     (repay_line.collector_id_at_repay)、未冲正(rl.reversed=false)的回款（不再 join 当前 case.holder_id）；
        //     用 LEFT JOIN 保证“设过比例但暂无明细”的批次仍出 due=0 行（与原 for 循环逐批出行一致）。
        //   - 逐笔佣金 = round(amount_cents × rate)（HALF_UP 到整数分，金额/比率非负 ⇒ PG round 与 Java HALF_UP 等价），
        //     先逐笔舍入再 SUM（绝不汇总后再舍入），完全复刻 Commission.lineCommissionCents 的求和口径。
        //   - “已结” = 该回款明细被某 SETTLED 的 co_pay_doc 关联（co_pay_doc_line + co_pay_doc.status='SETTLED'），
        //     不看 repay_line.settled；未结明细才计入 unsettledCents / unsettledLineCount。
        //   ORDER BY cc.batch_id 保持原 loadRateBatches 的输出顺序。
        String sql =
                "SELECT cc.batch_id,"
                        + "       b.no AS batch_name,"
                        + "       cc.rate,"
                        + "       COALESCE(SUM(round(rl.amount_cents * cc.rate))::bigint, 0) AS due_cents,"
                        + "       COALESCE(SUM(CASE WHEN rl.id IS NOT NULL AND NOT EXISTS ("
                        + "             SELECT 1 FROM co_pay_doc_line cpl"
                        + "               JOIN co_pay_doc d ON d.id = cpl.co_pay_doc_id"
                        + "              WHERE cpl.repay_line_id = rl.id AND d.status = 'SETTLED')"
                        + "           THEN round(rl.amount_cents * cc.rate) ELSE 0 END)::bigint, 0)"
                        + "         AS unsettled_cents,"
                        + "       COALESCE(SUM(CASE WHEN rl.id IS NOT NULL AND NOT EXISTS ("
                        + "             SELECT 1 FROM co_pay_doc_line cpl"
                        + "               JOIN co_pay_doc d ON d.id = cpl.co_pay_doc_id"
                        + "              WHERE cpl.repay_line_id = rl.id AND d.status = 'SETTLED')"
                        + "           THEN 1 ELSE 0 END), 0) AS unsettled_line_count"
                        + " FROM co_commission cc"
                        + " JOIN batch b ON b.id = cc.batch_id"
                        + " LEFT JOIN repay_line rl ON rl.batch_id = cc.batch_id"
                        + "        AND rl.collector_id_at_repay = cc.collector_id AND rl.reversed = false"
                        + " WHERE cc.collector_id = ? AND b.provider_id = ?"
                        + " GROUP BY cc.batch_id, b.no, cc.rate"
                        + " ORDER BY cc.batch_id";

        return jdbc.query(sql,
                (rs, i) -> new CoCommissionBatchRowM9Dto(
                        String.valueOf(rs.getLong("batch_id")),
                        rs.getString("batch_name"),
                        rs.getBigDecimal("rate"),
                        rs.getLong("due_cents"),
                        rs.getLong("unsettled_cents"),
                        rs.getInt("unsettled_line_count")),
                collectorId, providerOrgId);
    }

    // ── [2] PUT /co-commissions/{collectorId}/batches/{batchId}/rate ─────────
    // 防倒挂：rate ≤ batch.pay_out_rate，超 → 422 BIZ_PAYOUT_INVERT。UPSERT co_commission。
    @PutMapping("/co-commissions/{collectorId}/batches/{batchId}/rate")
    @RequirePermission("cocomm.manage")
    @Transactional
    public Map<String, Object> setCoCommissionRate(
            @PathVariable("collectorId") String collectorIdRaw,
            @PathVariable("batchId") String batchIdRaw,
            @RequestBody(required = false) Map<String, Object> body) {
        long providerOrgId = requireProvider();
        long collectorId = parseId(collectorIdRaw, "催收员不存在");
        long batchId = parseId(batchIdRaw, "批次不存在");

        BigDecimal rate = parseRequiredRate(body);   // 缺/非数/越界(0-1) → 422

        // own-org 复核：催收员须属本商；批次须本商承接（OUT 线）。不存在 → 404；越组织 → 403。
        requireCollectorOfProvider(collectorId, providerOrgId);
        BigDecimal payOutRate = requireBatchOfProvider(batchId, providerOrgId);

        // 防倒挂 BR-M9-14/US-M9-02：催收员比例不得超过付佣比例。
        if (payOutRate == null) {
            // 付佣比例未设（开放抢单/未派单），无锚点可比 → 视为越界拒绝（不可在无 pay_out_rate 下定提成）。
            throw new ApiException(BizError.BIZ_PAYOUT_INVERT,
                    "[field=rate][rule=lte_payout] 批次付佣比例未设定，无法核定催收员比例（防倒挂）");
        }
        if (rate.compareTo(payOutRate) > 0) {
            throw new ApiException(BizError.BIZ_PAYOUT_INVERT,
                    "[field=rate][rule=lte_payout] 催收员比例不得超过付佣比例(防倒挂)");
        }

        BigDecimal before = jdbc.query(
                "SELECT rate FROM co_commission WHERE collector_id = ? AND batch_id = ?",
                rs -> rs.next() ? rs.getBigDecimal("rate") : null, collectorId, batchId);

        // UPSERT ON CONFLICT(uq_co_comm_coll_batch)。
        jdbc.update(
                "INSERT INTO co_commission(collector_id, batch_id, rate) VALUES (?, ?, ?)"
                        + " ON CONFLICT ON CONSTRAINT uq_co_comm_coll_batch"
                        + " DO UPDATE SET rate = EXCLUDED.rate, updated_at = now()",
                collectorId, batchId, rate);

        // 审计：actor=当前主体，含 before/after 关键字段。
        insertCoActivity("CO_RATE_SET", actorId(),
                "设催收员佣金比例 collector=" + collectorId + " batch=" + batchId
                        + " before=" + before + " after=" + rate);
        return Map.of("ok", true);
    }

    // ── [3] GET /co-pay-docs ─────────────────────────────────────────────────
    @GetMapping("/co-pay-docs")
    @RequirePermission("cocomm.manage")
    public Page<CoPayDocM9Dto> listCoPayDocs(
            @RequestParam(required = false) String collectorId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        long providerOrgId = requireProvider();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(
                " WHERE a.org_id = ?");   // own-org：仅本商催收员的支付单
        List<Object> args = new ArrayList<>();
        args.add(providerOrgId);
        if (collectorId != null && !collectorId.isBlank()) {
            Long cid = tryParseLong(collectorId);
            // 非法 collectorId 形态：返回空集（不 5xx），用恒假条件。
            where.append(" AND d.collector_id = ?");
            args.add(cid == null ? -1L : cid);
        }
        if (status != null && !status.isBlank()) {
            if (!"PENDING_PAY".equals(status) && !"SETTLED".equals(status)) {
                throw new ApiException(BizError.VALIDATION_422, "status 非法（仅 PENDING_PAY/SETTLED）");
            }
            where.append(" AND d.status = ?");
            args.add(status);
        }

        String base = "FROM co_pay_doc d JOIN account a ON a.id = d.collector_id" + where;
        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<CoPayDocM9Dto> items = jdbc.query(
                "SELECT d.id, d.collector_id, a.name AS collector_name, d.count, d.amount_cents,"
                        + " d.status, d.created_at " + base
                        + " ORDER BY d.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new CoPayDocM9Dto(
                        String.valueOf(rs.getLong("id")),
                        String.valueOf(rs.getLong("collector_id")),
                        rs.getString("collector_name"),
                        rs.getInt("count"),
                        rs.getLong("amount_cents"),
                        rs.getString("status"),
                        ts(rs.getTimestamp("created_at")),
                        null,    // documentUrl 占位（文件通道 TBD）
                        false,   // sealed 占位（电子签章 TBD）
                        null),   // list 不穿透 lines（详情端点返回）
                pageArgs.toArray());
        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [4] POST /co-pay-docs ────────────────────────────────────────────────
    // 勾选未结明细组单：FOR UPDATE 行锁校验未被其他 co_pay_doc_line 占用，算 amount=Σ(amount×rate)。
    @PostMapping("/co-pay-docs")
    @RequirePermission("cocomm.manage")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CoPayDocM9Dto createCoPayDoc(@RequestBody(required = false) Map<String, Object> body) {
        long providerOrgId = requireProvider();
        long collectorId = parseRequiredLongBody(body, "collectorId");   // 缺/非数 → 422
        List<Long> lineIds = parseRequiredLineIds(body);                 // 空/缺 → 422

        // own-org：催收员须属本商。不存在 → 404；越组织 → 403。
        requireCollectorOfProvider(collectorId, providerOrgId);

        long amount = 0L;
        int count = 0;
        List<CoPayDocLineM9Dto> snapshot = new ArrayList<>();
        // B-05 组单时点明细快照（resp 资金正确性·阻断）：每条 line 固化 case/room/owner/repay/rate/comm，
        //   详情读快照不再事后重查 co_commission——防比率/归属变更令历史单据失真。
        List<LineSnapshot> snaps = new ArrayList<>();
        for (Long lineId : lineIds) {
            // FOR UPDATE 行锁；不存在 → 404；越组织（批次非本商）→ 403。
            LockedLine ll = lockLineForCollector(lineId, collectorId, providerOrgId);
            // 已被任一 co_pay_doc_line 占用 → 409（内部明细已纳入其他单 / 已结算）。
            if (isLineInAnyCoPayDoc(lineId)) {
                throw new ApiException(BizError.STATE_409,
                        "[BIZ_LINE_LOCKED] 回款明细已纳入其他佣金支付单（已结算/已占用）: " + lineId);
            }
            // 催收员对该明细所属批次的比例（未设比例不可组单）→ 422。
            BigDecimal rate = jdbc.query(
                    "SELECT rate FROM co_commission WHERE collector_id = ? AND batch_id = ?",
                    rs -> rs.next() ? rs.getBigDecimal("rate") : null, collectorId, ll.batchId());
            if (rate == null) {
                throw new ApiException(BizError.VALIDATION_422,
                        "催收员对该批次未设佣金比例，无法组单: line=" + lineId + " batch=" + ll.batchId());
            }
            long comm = Commission.lineCommissionCents(ll.amountCents(), rate);
            amount += comm;
            count++;
            snaps.add(new LineSnapshot(lineId, ll.caseId(), ll.room(), ll.ownerName(),
                    ll.amountCents(), rate, comm));
            snapshot.add(new CoPayDocLineM9Dto(
                    String.valueOf(lineId), String.valueOf(ll.caseId()),
                    ll.ownerName(), ll.room(), ll.amountCents(), comm));
        }

        String linesJson = writeJson(lineIds);
        Long docId = jdbc.queryForObject(
                "INSERT INTO co_pay_doc(collector_id, line_ids, count, amount_cents, status)"
                        + " VALUES (?, ?::jsonb, ?, ?, 'PENDING_PAY') RETURNING id",
                Long.class, collectorId, linesJson, count, amount);

        for (LineSnapshot ls : snaps) {
            jdbc.update(
                    "INSERT INTO co_pay_doc_line(co_pay_doc_id, repay_line_id,"
                            + " case_id, room, owner_name, repay_cents, rate, comm_cents)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    docId, ls.lineId(), ls.caseId(), ls.room(), ls.ownerName(),
                    ls.repayCents(), ls.rate(), ls.commCents());
        }

        insertCoActivity("CO_PAY_DOC_CREATE", actorId(),
                "生成佣金支付单 doc=" + docId + " collector=" + collectorId
                        + " count=" + count + " amountCents=" + amount);

        String createdAt = jdbc.query(
                "SELECT created_at FROM co_pay_doc WHERE id = ?",
                rs -> rs.next() ? ts(rs.getTimestamp("created_at")) : null, docId);
        String collectorName = jdbc.query(
                "SELECT name FROM account WHERE id = ?",
                rs -> rs.next() ? rs.getString("name") : null, collectorId);

        return new CoPayDocM9Dto(String.valueOf(docId), String.valueOf(collectorId),
                collectorName, count, amount, "PENDING_PAY", createdAt, null, false, snapshot);
    }

    // ── [5] GET /co-pay-docs/{id} ────────────────────────────────────────────
    // 含 lines 穿透快照。不存在/非法 id → 404；越组织 → 403。
    @GetMapping("/co-pay-docs/{id}")
    @RequirePermission("cocomm.manage")
    public CoPayDocM9Dto getCoPayDoc(@PathVariable("id") String id) {
        long providerOrgId = requireProvider();
        long docId = parseId(id, "佣金支付单不存在");
        DocRow doc = loadDoc(docId);                                  // 不存在 → 404
        requireCollectorOfProvider(doc.collectorId(), providerOrgId); // 越组织 → 403

        // B-05 lines 只读组单时点快照（resp 资金正确性·阻断）：直接读 co_pay_doc_line 快照列，
        //   不再事后重查 co_commission.rate / case 展示字段——历史单据不随比率/归属变更失真。
        //   契约 CoPayDoc.lines 字段不变，仅换数据来源。
        List<CoPayDocLineM9Dto> lines = jdbc.query(
                "SELECT repay_line_id, case_id, owner_name, room, repay_cents, comm_cents"
                        + " FROM co_pay_doc_line WHERE co_pay_doc_id = ? ORDER BY repay_line_id",
                (rs, i) -> new CoPayDocLineM9Dto(
                        String.valueOf(rs.getLong("repay_line_id")),
                        idOrNull(rs, "case_id"),
                        rs.getString("owner_name"),
                        rs.getString("room"),
                        longOrNull(rs, "repay_cents"),
                        longOrNull(rs, "comm_cents")),
                docId);
        return new CoPayDocM9Dto(String.valueOf(docId), String.valueOf(doc.collectorId()),
                doc.collectorName(), doc.count(), doc.amountCents(), doc.status(),
                ts(doc.createdAt()), null, false, lines);
    }

    // ── [6] POST /co-pay-docs/{id}/confirm-pay ───────────────────────────────
    // PENDING_PAY → SETTLED。已 SETTLED → 409。内部已结判定走 co_pay_doc.status，不动 repay_line.settled。
    @PostMapping("/co-pay-docs/{id}/confirm-pay")
    @RequirePermission("cocomm.manage")
    @Transactional
    public Map<String, Object> confirmCoPayDoc(@PathVariable("id") String id) {
        long providerOrgId = requireProvider();
        long docId = parseId(id, "佣金支付单不存在");
        DocRow doc = lockDoc(docId);                                  // 不存在 → 404
        requireCollectorOfProvider(doc.collectorId(), providerOrgId); // 越组织 → 403

        if ("SETTLED".equals(doc.status())) {
            throw new ApiException(BizError.STATE_409, "佣金支付单已结算，不可重复确认: " + docId);
        }
        if (!"PENDING_PAY".equals(doc.status())) {
            throw new ApiException(BizError.STATE_409, "佣金支付单状态不可确认: " + doc.status());
        }

        // 状态推进（仅 PENDING_PAY 行命中）；内部「已结」专走 co_pay_doc.status，绝不写 repay_line.settled。
        int rows = jdbc.update(
                "UPDATE co_pay_doc SET status = 'SETTLED', updated_at = now()"
                        + " WHERE id = ? AND status = 'PENDING_PAY'",
                docId);
        if (rows == 0) {
            throw new ApiException(BizError.STATE_409, "并发确认冲突，请重试: " + docId);
        }

        insertCoActivity("CO_PAY_DOC_CONFIRM", actorId(),
                "确认佣金支付完成 doc=" + docId + " collector=" + doc.collectorId()
                        + " before=PENDING_PAY after=SETTLED");
        return Map.of("ok", true);
    }

    // ── [7] GET /me/settlement ───────────────────────────────────────────────
    // 催收员只读自查（本人）。比例/已支付由服务商设，催收员只读。
    @GetMapping("/me/settlement")
    @RequirePermission("cocomm.self.view")
    public MySettlementM9Dto getMySettlement() {
        CurrentSubject s = SubjectContext.get();
        // own-org（本人）：仅服务商内部账户可有内部提成（PROVIDER-only）。平台/物业 → 403。
        if (!"PROVIDER".equals(s.orgType())) {
            throw new ApiException(BizError.PERM_403, "催收员结算仅服务商内部可见");
        }
        long me = actorId();

        List<RateBatch> rbs = loadRateBatchesSelf(me);
        long total = 0L, settled = 0L;
        List<MySettlementM9Dto.Row> rows = new ArrayList<>();
        for (RateBatch rb : rbs) {
            for (LineRow ln : loadActiveLinesForCollectorBatch(me, rb.batchId())) {
                long comm = Commission.lineCommissionCents(ln.amountCents(), rb.rate());
                boolean lineSettled = isLineInternallySettled(ln.id());
                total += comm;
                if (lineSettled) settled += comm;
                rows.add(new MySettlementM9Dto.Row(
                        String.valueOf(rb.batchId()), ln.amountCents(), rb.rate(), comm, lineSettled));
            }
        }
        return new MySettlementM9Dto(total, settled, total - settled, rows);
    }

    // ── PROVIDER-only / own-org 守卫 ─────────────────────────────────────────

    /** 全端点强制：仅服务商内部可见。平台/物业 → 403。返回本商 org_id。 */
    private long requireProvider() {
        CurrentSubject s = SubjectContext.get();
        if (!"PROVIDER".equals(s.orgType())) {
            throw new ApiException(BizError.PERM_403, "催收员佣金为服务商内部，平台/物业不可见");
        }
        return Long.parseLong(s.orgId());
    }

    /** 催收员须属本商；不存在 → 404，越组织 → 403。 */
    private void requireCollectorOfProvider(long collectorId, long providerOrgId) {
        Long orgId = jdbc.query(
                "SELECT org_id FROM account WHERE id = ?",
                rs -> rs.next() ? rs.getLong("org_id") : null, collectorId);
        if (orgId == null) {
            throw new ApiException(BizError.NOT_FOUND_404, "催收员不存在: " + collectorId);
        }
        if (orgId != providerOrgId) {
            throw new ApiException(BizError.PERM_403, "催收员不属本服务商");
        }
    }

    /** 批次须本商承接；不存在 → 404，越组织 → 403。返回 pay_out_rate（可能 null）。 */
    private BigDecimal requireBatchOfProvider(long batchId, long providerOrgId) {
        Map<String, Object> row = jdbc.query(
                "SELECT provider_id, pay_out_rate FROM batch WHERE id = ?",
                rs -> {
                    if (!rs.next()) return null;
                    Map<String, Object> m = new LinkedHashMap<>();
                    long pid = rs.getLong("provider_id");
                    m.put("provider_id", rs.wasNull() ? null : pid);
                    m.put("pay_out_rate", rs.getBigDecimal("pay_out_rate"));
                    return m;
                }, batchId);
        if (row == null) {
            throw new ApiException(BizError.NOT_FOUND_404, "批次不存在: " + batchId);
        }
        Object pid = row.get("provider_id");
        if (pid == null || ((Long) pid) != providerOrgId) {
            throw new ApiException(BizError.PERM_403, "批次不属本服务商");
        }
        return (BigDecimal) row.get("pay_out_rate");
    }

    // ── 汇总数据访问 ──────────────────────────────────────────────────────────

    private record RateBatch(long batchId, BigDecimal rate) {}
    private record LineRow(long id, long amountCents) {}

    /** 本人（me/settlement）：本人设过比例的（批次,比率）——不限 provider，本人天然属本商。 */
    private List<RateBatch> loadRateBatchesSelf(long collectorId) {
        // 仅本商批次：JOIN batch 并按 provider_id = 本人所属服务商 org 过滤，
        // 防历史跨商设过比例的批次串入本人结算(审计 M-3)。
        return jdbc.query(
                "SELECT cc.batch_id, cc.rate FROM co_commission cc"
                        + " JOIN batch b ON b.id = cc.batch_id"
                        + " WHERE cc.collector_id = ?"
                        + "   AND b.provider_id = (SELECT org_id FROM account WHERE id = ?)"
                        + " ORDER BY cc.batch_id",
                (rs, i) -> new RateBatch(rs.getLong("batch_id"), rs.getBigDecimal("rate")),
                collectorId, collectorId);
    }

    /**
     * 催收员某批次的「可计提」回款明细：该批次下、到账时点由本催收员持有的、未冲正的回款。
     * BLOCKER-1·按到账快照归属：用 repay_line.collector_id_at_repay（登记回款时固化），
     *   不再 join 当前 case.holder_id——换持有人后历史佣金不漂移（哪个催收员到账时点持有就算谁的）。
     * 基数=减免后实收·不含税（repay_line.amount_cents）。
     */
    private List<LineRow> loadActiveLinesForCollectorBatch(long collectorId, long batchId) {
        return jdbc.query(
                "SELECT rl.id, rl.amount_cents FROM repay_line rl"
                        + " WHERE rl.batch_id = ? AND rl.collector_id_at_repay = ? AND rl.reversed = false",
                (rs, i) -> new LineRow(rs.getLong("id"), rs.getLong("amount_cents")),
                batchId, collectorId);
    }

    /** 内部已结判定：该回款明细被某 SETTLED 的 co_pay_doc 关联（不看 repay_line.settled）。 */
    private boolean isLineInternallySettled(long repayLineId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM co_pay_doc_line cpl"
                        + " JOIN co_pay_doc d ON d.id = cpl.co_pay_doc_id"
                        + " WHERE cpl.repay_line_id = ? AND d.status = 'SETTLED'",
                Long.class, repayLineId);
        return n != null && n > 0;
    }

    /** 该回款明细是否已被任一 co_pay_doc_line 占用（组单去重，PENDING_PAY 或 SETTLED 均占）。 */
    private boolean isLineInAnyCoPayDoc(long repayLineId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM co_pay_doc_line WHERE repay_line_id = ?",
                Long.class, repayLineId);
        return n != null && n > 0;
    }

    // ── 行锁 / 取数（须在 @Transactional 内调用 FOR UPDATE）───────────────────

    private record LockedLine(long id, long caseId, long batchId, String ownerName, String room, long amountCents) {}

    /** B-05 组单时点明细快照（落 co_pay_doc_line 快照列）。 */
    private record LineSnapshot(long lineId, long caseId, String room, String ownerName,
                                long repayCents, BigDecimal rate, long commCents) {}

    /**
     * 组单行锁：FOR UPDATE 锁定 repay_line；校验到账时点属该催收员持有 & 批次本商。不存在 → 404，越权 → 403。
     * BLOCKER-1·按到账快照归属：以 repay_line.collector_id_at_repay 判持有（非当前 case.holder_id），
     *   换持有人后历史回款仍归到账时点催收员；owner_name/room 为展示字段仍取当前 case 快照。
     */
    private LockedLine lockLineForCollector(long lineId, long collectorId, long providerOrgId) {
        LockedLine ll;
        try {
            ll = jdbc.queryForObject(
                    "SELECT rl.id, rl.case_id, rl.batch_id, c.owner_name, c.room, rl.amount_cents,"
                            + " rl.collector_id_at_repay, b.provider_id, rl.reversed"
                            + " FROM repay_line rl"
                            + " JOIN \"case\" c ON c.id = rl.case_id"
                            + " JOIN batch b ON b.id = rl.batch_id"
                            + " WHERE rl.id = ? FOR UPDATE OF rl",
                    (rs, i) -> {
                        // 越组织/越持有 → 用哨兵值，外层判定（这里仅取，后判）。
                        long holder = rs.getLong("collector_id_at_repay");
                        boolean holderNull = rs.wasNull();
                        Long providerId = (Long) rs.getObject("provider_id");
                        boolean reversed = rs.getBoolean("reversed");
                        if (reversed) {
                            throw new ApiException(BizError.STATE_409,
                                    "[BIZ_LINE_LOCKED] 回款明细已冲正，不可组单: " + lineId);
                        }
                        if (providerId == null || providerId != providerOrgId) {
                            throw new ApiException(BizError.PERM_403, "回款明细所属批次不属本服务商");
                        }
                        if (holderNull || holder != collectorId) {
                            throw new ApiException(BizError.PERM_403, "回款明细非该催收员到账时点持有");
                        }
                        return new LockedLine(rs.getLong("id"), rs.getLong("case_id"),
                                rs.getLong("batch_id"), rs.getString("owner_name"),
                                rs.getString("room"), rs.getLong("amount_cents"));
                    },
                    lineId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "回款明细不存在: " + lineId);
        }
        return ll;
    }

    private record DocRow(long id, long collectorId, String collectorName, int count,
                          long amountCents, String status, Timestamp createdAt, String lineIdsJson) {}

    private DocRow loadDoc(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT d.id, d.collector_id, a.name AS collector_name, d.count, d.amount_cents,"
                            + " d.status, d.created_at, d.line_ids::text AS line_ids"
                            + " FROM co_pay_doc d JOIN account a ON a.id = d.collector_id WHERE d.id = ?",
                    (rs, i) -> new DocRow(rs.getLong("id"), rs.getLong("collector_id"),
                            rs.getString("collector_name"), rs.getInt("count"),
                            rs.getLong("amount_cents"), rs.getString("status"),
                            rs.getTimestamp("created_at"), rs.getString("line_ids")),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "佣金支付单不存在: " + id);
        }
    }

    private DocRow lockDoc(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT d.id, d.collector_id, NULL AS collector_name, d.count, d.amount_cents,"
                            + " d.status, d.created_at, d.line_ids::text AS line_ids"
                            + " FROM co_pay_doc d WHERE d.id = ? FOR UPDATE",
                    (rs, i) -> new DocRow(rs.getLong("id"), rs.getLong("collector_id"),
                            rs.getString("collector_name"), rs.getInt("count"),
                            rs.getLong("amount_cents"), rs.getString("status"),
                            rs.getTimestamp("created_at"), rs.getString("line_ids")),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "佣金支付单不存在: " + id);
        }
    }

    /** co_pay_doc_line 快照列读取的空安全助手（快照可空·历史回填）。 */
    private static String idOrNull(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : String.valueOf(v);
    }

    private static Long longOrNull(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    // ── activity 审计 ─────────────────────────────────────────────────────────
    // 内部提成无关联单一案件（聚合维度=人/单），写无 case_id 维度审计行（type 复用 activity）。
    private void insertCoActivity(String type, Long actorId, String content) {
        // 佣金线属资金动作须留痕。activity.case_id NOT NULL 而内部提成无单一案件锚点——
        // 故改落 audit_log(无 case 约束)，确保设比例/生成佣金单/确认支付有审计(审计 M-2)。
        try {
            jdbc.update(
                    "INSERT INTO audit_log(actor_id, actor, action, target, target_type)"
                            + " VALUES (?, ?, ?, ?, 'co_commission')",
                    actorId, String.valueOf(actorId), type, content);
        } catch (RuntimeException e) {
            // 审计尽力而为：写失败不阻断主流程（业务一致性已由事务+乐观/行锁保证）。
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    private Long actorId() {
        CurrentSubject s = SubjectContext.get();
        try {
            return s.accountId() == null ? null : Long.valueOf(s.accountId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 路径 id 非法形态 → 404（避免存在性泄漏 / 防 5xx）。 */
    private static long parseId(String id, String notFoundMsg) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, notFoundMsg);
        }
    }

    private static Long tryParseLong(String v) {
        try {
            return Long.valueOf(v.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** rate 必填、可解析、且 ∈ [0,1] 分数口径，否则 422。 */
    private static BigDecimal parseRequiredRate(Map<String, Object> body) {
        Object v = body == null ? null : body.get("rate");
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 rate");
        }
        BigDecimal r;
        try {
            r = new BigDecimal(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, "rate 非法");
        }
        if (r.signum() < 0 || r.compareTo(BigDecimal.ONE) > 0) {
            throw new ApiException(BizError.VALIDATION_422, "rate 越界（应为 0-1 分数）");
        }
        return r;
    }

    private static long parseRequiredLongBody(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 " + key);
        }
        try {
            if (v instanceof Number n) return n.longValue();
            return Long.parseLong(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, key + " 非法");
        }
    }

    /** lineIds 必填、非空数组、每项可解析为 long，否则 422。 */
    private List<Long> parseRequiredLineIds(Map<String, Object> body) {
        Object v = body == null ? null : body.get("lineIds");
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 lineIds 或为空");
        }
        List<Long> out = new ArrayList<>();
        // MED·显式拒重复 lineId（解析阶段 HashSet 检重 → 422），与 PaymentRequest.parseLineIds 同口径，
        //   不再靠 co_pay_doc_line DB 主键 409 回滚兜底（重复会令 amount/count 重复计费再触 PK 冲突）。
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Object o : list) {
            if (o == null || String.valueOf(o).isBlank()) {
                throw new ApiException(BizError.VALIDATION_422, "lineIds 含空项");
            }
            long id;
            try {
                if (o instanceof Number n) id = n.longValue();
                else id = Long.parseLong(String.valueOf(o).trim());
            } catch (RuntimeException e) {
                throw new ApiException(BizError.VALIDATION_422, "lineIds 含非法项: " + o);
            }
            if (!seen.add(id)) {
                throw new ApiException(BizError.VALIDATION_422, "lineIds 含重复 id: " + id);
            }
            out.add(id);
        }
        return out;
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return "[]";
        }
    }

    private List<Long> readLongList(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            List<Object> raw = json.readValue(jsonText, new TypeReference<List<Object>>() {});
            List<Long> out = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof Number n) out.add(n.longValue());
                else { Long l = tryParseLong(String.valueOf(o)); if (l != null) out.add(l); }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
