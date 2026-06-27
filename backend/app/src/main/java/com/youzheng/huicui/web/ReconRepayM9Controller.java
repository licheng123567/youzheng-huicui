package com.youzheng.huicui.web;

import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.ReconRollupM9Dto;
import com.youzheng.huicui.web.dto.RepayLineDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * M9 结算·组3「对账汇总 + 回款明细」读端点（横切层范式 + scaffold 共享助手）。
 * 类名带 M9 后缀，与 M1-M4 controller 物理隔离；只承载本组读端点，不碰共享件/其他组/pom。
 *
 * 端点（基路径 /v1 由 server.servlet.context-path 提供，方法注解写裸路径）：
 *   GET /recon/rollup            getReconRollup     | x-data-scope=range | 资金双线硬隔离 | 200 ReconRollupPage / 403。
 *   GET /batches/{id}/repay-lines listBatchRepayLines| x-data-scope=range |                | 200 RepayLinePage / 403 / 404。
 *
 * 注：POST /cases/{id}/repay-lines（createRepayLine）与 /repay-lines/{id}/reverse 已在 M4
 *   PayReduceRepayM4Controller 实现，本组只承载读端点（避免重复实现）。
 *
 * 【资金双线硬隔离 BR-M9-11】（service 层强制复核，不依赖拦截器）：
 *   side=IN（收佣·平台↔物业）  → 仅 PLATFORM + PROPERTY（物业按 batch→project.org_id）可见；
 *                                 PROVIDER 访问 IN → 403 BIZ_WRONG_SETTLE_SIDE。
 *   side=OUT（付佣·平台↔服务商）→ 仅 PLATFORM + PROVIDER（本商 batch.provider_id==orgId）可见；
 *                                 PROPERTY 访问 OUT → 403 BIZ_WRONG_SETTLE_SIDE。
 *   物业↔服务商之间无任何资金接口（强约束）。
 *
 * 【x-data-scope=range 组织级裁剪】（复用 CasesM2Controller.appendRangeScope 范式）：
 *   平台(PLATFORM)   → 全量；
 *   物业(PROPERTY)   → project.org_id = 本组织；
 *   服务商(PROVIDER) → batch.provider_id = 本组织。
 *
 * 优雅降级（绝不 5xx）：路径 id 非法形态/不存在→404；越线/越 scope→403；
 *   period 等查询入参非法→视为不过滤（不抛错），保证读端点最大可用性。
 *
 * 金额：*_cents 列原样以「分」(Long) 返回；Rate 列 NUMERIC(6,4) 为分数(0-1)，原样返回不×100。
 * 列名严格对齐 V1 DDL：repay_line / batch(no) / project(name,org_id) / "case"(双引号·owner_name,room)。
 */
@RestController
public class ReconRepayM9Controller {

    private final JdbcTemplate jdbc;

    public ReconRepayM9Controller(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── [1] GET /recon/rollup ────────────────────────────────────────────────
    // 对账汇总(按批次×period)。无 x-permission（靠 side 双线 + range scope 控可见性）。
    @GetMapping("/recon/rollup")
    public Page<ReconRollupM9Dto> getReconRollup(
            @RequestParam(required = false) String side,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        String sideVal = requireSide(side);          // 缺/非法 IN|OUT → 422
        requireSideVisible(s, sideVal);              // 双线硬隔离：错线 → 403 BIZ_WRONG_SETTLE_SIDE
        String periodKey = normalizePeriod(period);  // 非法 period → null（不过滤，绝不 5xx）
        Pageable pg = Pageable.of(page, size);

        // 双线比率快照列：IN=batch.comm_in_rate / OUT=batch.pay_out_rate（NUMERIC(6,4)·分数）。
        boolean in = "IN".equals(sideVal);
        String commRateCol = in ? "b.comm_in_rate" : "b.pay_out_rate";

        // B-03 OUT 付佣按到账归属快照（resp 资金正确性·阻断）：服务商可见/聚合一律按 repay_line.provider_id_at_repay，
        //   不再按 batch.provider_id——否则单案再派(只改 case.provider_id)后付佣仍结给旧服务商。
        //   providerSnapshotId 非空 ⇒ 本次汇总须按该服务商快照裁剪 base/settled 聚合（见 buildRollup）。
        Long providerSnapshotId = (!in && "PROVIDER".equals(s.orgType())) ? Long.valueOf(s.orgId()) : null;

        // MEDIUM·空汇总行修复：batch 列表/total 须与 buildRollup 同口径只计未冲正(reversed=false)明细，
        //   否则纯冲正批次在分页/计数里出现，却被 buildRollup 全过滤 → 空汇总行。
        StringBuilder where = new StringBuilder(" WHERE rl.reversed = false");
        List<Object> args = new ArrayList<>();
        appendRangeScope(s, where, args, in);        // 组织级裁剪（OUT 服务商按快照）
        if (periodKey != null) {
            // period 按 paid_at 月份过滤（date_trunc 月对齐 yyyy-MM-01）。
            where.append(" AND date_trunc('month', rl.paid_at) = date_trunc('month', CAST(? AS DATE))");
            args.add(periodKey + "-01");
        }

        // 按 batch 汇总：仅统计未冲正(reversed=false)明细。
        //   baseCents=Σ amount_cents、cnt=笔数、settledBase=Σ(已纳入 PAID 单·settled=true 的 amount_cents)。
        //   应收口径 dueBase：批次内未冲正案件应收(reduce_after_cents 优先，无则 due_cents)，用于回款率分母。
        String base =
                "FROM repay_line rl"
                        + " JOIN batch b ON b.id = rl.batch_id"
                        + " JOIN project p ON p.id = b.project_id"
                        + where;

        // 先取参与本汇总的 batch 列表（分页在 batch 维度）。
        String idSql = "SELECT rl.batch_id, b.no AS batch_no, p.name AS proj_name"
                + " " + base
                + " GROUP BY rl.batch_id, b.no, p.name"
                + " ORDER BY rl.batch_id DESC LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<long[]> idHolder = new ArrayList<>();
        List<String[]> nameHolder = new ArrayList<>();
        jdbc.query(idSql, rs -> {
            idHolder.add(new long[]{rs.getLong("batch_id")});
            nameHolder.add(new String[]{rs.getString("batch_no"), rs.getString("proj_name")});
        }, pageArgs.toArray());

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT 1 " + base + " GROUP BY rl.batch_id) t",
                Long.class, args.toArray());

        List<ReconRollupM9Dto> items = new ArrayList<>();
        for (int i = 0; i < idHolder.size(); i++) {
            long batchId = idHolder.get(i)[0];
            String batchNo = nameHolder.get(i)[0];
            String projName = nameHolder.get(i)[1];
            items.add(buildRollup(s, batchId, batchNo, projName, periodKey, commRateCol,
                    periodArgs(periodKey), providerSnapshotId));
        }
        return Page.of(items, pg, total == null ? 0 : total);
    }

    /** 单 batch 行的聚合 + 佣金/结算/回款率计算（逐笔 × 固化比率·分数·HALF_UP）。 */
    private ReconRollupM9Dto buildRollup(CurrentSubject s, long batchId, String batchNo, String projName,
                                         String periodKey, String commRateCol, Object[] periodArg,
                                         Long providerSnapshotId) {
        // 固化比率快照（分数 0-1）。批次比率可能为 null（付佣未设）→ 视作 0。
        BigDecimal commRate = jdbc.query(
                "SELECT " + commRateCol + " AS r FROM batch b WHERE b.id = ?",
                rs -> rs.next() ? rs.getBigDecimal("r") : null, batchId);
        if (commRate == null) commRate = BigDecimal.ZERO;

        // B-04 逐笔舍入再求和（resp 资金正确性）：dueCents/settledCents 由 SUM(round(amount×rate)) 得出，
        //   与 domain/Commission.lineCommissionCents、PaymentRequest 单据算法严格一致；不再 round(SUM(amount)×rate)。
        //   金额/比率非负 ⇒ PG round() 半数进位与 BigDecimal HALF_UP 等价。
        // B-03 OUT 服务商按到账归属快照裁剪：providerSnapshotId 非空时仅计入 provider_id_at_repay=本商的明细。
        // HIGH-2·SE 越范围聚合修复：buildRollup 聚合此前只按 batch/reversed/providerSnapshotId 裁剪，
        //   SE（按 data_range·非整批 provider）聚合会把整批越范围明细一并求和。改：JOIN batch/project 后对
        //   CurrentSubject 追加 DataScope.appendRange（provider 维按 rl.provider_id_at_repay、property 按 p.org_id、
        //   area 按 p.area），与列表/明细同口径。SA 全量不追加；PROVIDER 由 providerSnapshotId 已裁剪。
        StringBuilder agg = new StringBuilder(
                "SELECT COALESCE(SUM(rl.amount_cents),0) AS base,"
                        + " COUNT(*) AS cnt,"
                        + " COALESCE(SUM(round(rl.amount_cents * ?))::bigint, 0) AS due_cents,"
                        + " COALESCE(SUM(CASE WHEN rl.settled THEN round(rl.amount_cents * ?) ELSE 0 END)::bigint, 0)"
                        + "   AS settled_cents"
                        + " FROM repay_line rl"
                        + " JOIN batch b ON b.id = rl.batch_id"
                        + " JOIN project p ON p.id = b.project_id"
                        + " WHERE rl.batch_id = ? AND rl.reversed = false");
        List<Object> aggArgs = new ArrayList<>();
        aggArgs.add(commRate);
        aggArgs.add(commRate);
        aggArgs.add(batchId);
        if (providerSnapshotId != null) {
            agg.append(" AND rl.provider_id_at_repay = ?");
            aggArgs.add(providerSnapshotId);
        }
        // SE data_range 三维裁剪（provider 维按到账归属快照·与外层 appendRangeScope 同口径）；
        //   SA/PROVIDER/PROPERTY 在 appendRange 内各自处理（SA 全量；非平台已被列表层裁剪到本批可见）。
        com.youzheng.huicui.common.DataScope.appendRange(
                s, agg, aggArgs, "rl.provider_id_at_repay", "p.org_id", "p.area", "b.project_id", "rl.batch_id");
        if (periodKey != null) {
            agg.append(" AND date_trunc('month', rl.paid_at) = date_trunc('month', CAST(? AS DATE))");
            aggArgs.add(periodArg[0]);
        }
        long[] sums = jdbc.query(agg.toString(), rs -> {
            if (!rs.next()) return new long[]{0, 0, 0, 0};
            return new long[]{rs.getLong("base"), rs.getLong("cnt"),
                    rs.getLong("due_cents"), rs.getLong("settled_cents")};
        }, aggArgs.toArray());
        long baseCents = sums[0];
        int cnt = (int) sums[1];
        long dueCents = sums[2];
        long settledCents = sums[3];
        long unsettledCents = dueCents - settledCents;

        // 应收口径（回款率分母）：批次内未冲正案件应收（减免后优先），活跃明细对应案件。
        Long dueBase = jdbc.queryForObject(
                "SELECT COALESCE(SUM(COALESCE(c.reduce_after_cents, c.due_cents)),0)"
                        + " FROM \"case\" c WHERE c.batch_id = ?",
                Long.class, batchId);
        long denom = dueBase == null ? 0L : dueBase;

        // 回款率·分数(0-1)：base / 应收；无应收口径(0)时给 null（避免除零·展示层自处理）。
        BigDecimal repayRate = denom > 0
                ? BigDecimal.valueOf(baseCents).divide(BigDecimal.valueOf(denom), 4, RoundingMode.HALF_UP)
                : null;

        return new ReconRollupM9Dto(
                batchNo, String.valueOf(batchId), projName, periodKey,
                baseCents, cnt, repayRate, commRate.stripTrailingZeros(),
                dueCents, settledCents, unsettledCents);
    }

    // ── [2] GET /batches/{id}/repay-lines ────────────────────────────────────
    // 逐笔回款明细 + 结算状态（支付申请单组单数据源）。range 裁剪：id 非法→404，跨线/越权→403。
    @GetMapping("/batches/{id}/repay-lines")
    public Page<RepayLineDto> listBatchRepayLines(
            @PathVariable("id") String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        long batchId = parseBatchId(id);                 // 非法形态 → 404
        BatchRow b = loadBatch(batchId);                 // 不存在 → 404
        requirePropertySideAccess(s, b);                 // 物业/PC 越组织/越协调 → 403（OUT 服务商不在此挡，按明细快照裁剪）
        Pageable pg = Pageable.of(page, size);

        // HIGH-1·明细按 side 区分 + 默认只列 reversed=false（与 rollup 同口径）：
        //   OUT 服务商 → rl.provider_id_at_repay = 本商（到账归属快照，不按 batch.provider_id，单案再派后不漂移）；
        //   SE → data_range 三维裁剪；SA → 全量；物业/PC → 已由 requirePropertySideAccess 限定本物业(+协调集)。
        StringBuilder where = new StringBuilder(" WHERE rl.batch_id = ? AND rl.reversed = false");
        List<Object> args = new ArrayList<>();
        args.add(batchId);
        appendRepayLineScope(s, where, args);

        String base = "FROM repay_line rl"
                + " JOIN \"case\" c ON c.id = rl.case_id"
                + " JOIN batch b ON b.id = rl.batch_id"
                + " JOIN project p ON p.id = b.project_id"
                + where;

        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        String listSql = "SELECT rl.id, rl.case_id, c.owner_name, c.room, rl.amount_cents,"
                + " rl.channel, rl.paid_at, rl.settled, rl.payment_request_id"
                + " " + base + " ORDER BY rl.id DESC LIMIT ? OFFSET ?";
        List<RepayLineDto> items = jdbc.query(listSql, repayLineRowMapper(), pageArgs.toArray());
        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Object[] periodArgs(String periodKey) {
        return periodKey == null ? new Object[]{null} : new Object[]{periodKey + "-01"};
    }

    /** side 必填且 ∈ {IN, OUT}，否则 422（ReconSideEnum）。 */
    private static String requireSide(String side) {
        String v = side == null ? null : side.trim();
        if (v == null || v.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 side（IN/OUT）");
        }
        if (!"IN".equals(v) && !"OUT".equals(v)) {
            throw new ApiException(BizError.VALIDATION_422, "side 非法（仅 IN/OUT）");
        }
        return v;
    }

    /**
     * 资金双线硬隔离（读端点级·BR-M9-11）：
     *   IN  → PROVIDER 不可见（403 BIZ_WRONG_SETTLE_SIDE）；
     *   OUT → PROPERTY 不可见（403 BIZ_WRONG_SETTLE_SIDE）。
     *   平台两线皆可见；具体 batch 归属再由 appendRangeScope/requireBatchVisible 裁剪。
     */
    private static void requireSideVisible(CurrentSubject s, String side) {
        if (s.isPlatform()) return;
        String org = s.orgType();
        if ("IN".equals(side) && "PROVIDER".equals(org)) {
            throw new ApiException(BizError.BIZ_WRONG_SETTLE_SIDE, "服务商无权访问收佣线(IN)");
        }
        if ("OUT".equals(side) && "PROPERTY".equals(org)) {
            throw new ApiException(BizError.BIZ_WRONG_SETTLE_SIDE, "物业无权访问付佣线(OUT)");
        }
    }

    /** period 规整为 yyyy-MM；非法/空 → null（不过滤，绝不 5xx）。 */
    private static String normalizePeriod(String period) {
        if (period == null || period.isBlank()) return null;
        String p = period.trim();
        // 接受 yyyy-MM 或 yyyy-MM-..（取前 7 位）。校验为 4-2 数字形态，否则忽略。
        if (p.length() >= 7) p = p.substring(0, 7);
        if (p.matches("\\d{4}-\\d{2}")) return p;
        return null;
    }

    /**
     * x-data-scope=range 追加到 WHERE（含前导 AND）。平台全量；物业按 p.org_id；
     * 服务商：IN 线（理论被 side 双线挡掉）仍按 b.provider_id；OUT 付佣线按到账归属快照 rl.provider_id_at_repay
     *   （B-03：单案再派只改 case.provider_id 不动 batch，故须按到账时点快照裁剪服务商可见批次）。
     */
    private void appendRangeScope(CurrentSubject s, StringBuilder where, List<Object> args, boolean in) {
        // 服务商裁剪列：IN 线按 batch.provider_id；OUT 付佣线按到账归属快照 rl.provider_id_at_repay（B-03）。
        // 此列同时用于 SE data_range providers 维（OUT 线亦按快照，口径一致）。
        String providerCol = in ? "b.provider_id" : "rl.provider_id_at_repay";
        // batch 维汇总（无 case 别名）：PC 协调集按 batch.project_id / rl.batch_id 判定。
        com.youzheng.huicui.common.DataScope.appendRange(
                s, where, args, providerCol, "p.org_id", "p.area", "b.project_id", "rl.batch_id");
    }

    private record BatchRow(long id, Long providerId, long projectId, Long projectOrgId) {}

    private BatchRow loadBatch(long batchId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT b.id, b.provider_id, b.project_id, p.org_id AS proj_org"
                        + " FROM batch b JOIN project p ON p.id = b.project_id WHERE b.id = ?",
                (rs, i) -> new BatchRow(rs.getLong("id"),
                        (Long) rs.getObject("provider_id"),
                        rs.getLong("project_id"),
                        (Long) rs.getObject("proj_org")),
                batchId);
        if (rows.isEmpty()) {
            throw new ApiException(BizError.NOT_FOUND_404, "批次不存在");
        }
        return rows.get(0);
    }

    /**
     * 物业线（IN）访问门（HIGH-1）：PROPERTY 须 project.org_id=本组织；PC 还须该批次 ∈ 本人协调集，否则 403。
     *   服务商(PROVIDER·OUT)不在此挡——其可见性按明细到账归属快照(rl.provider_id_at_repay)由 appendRepayLineScope
     *   裁剪（不按 batch.provider_id，单案再派后归属不漂移）；平台(SA/SE)放行，行级裁剪交 appendRepayLineScope。
     */
    private void requirePropertySideAccess(CurrentSubject s, BatchRow b) {
        if (s.isPlatform()) return;                       // SA/SE 放行（SE 由 appendRepayLineScope 行级裁剪）
        if ("PROVIDER".equals(s.orgType())) return;       // OUT 服务商按快照裁剪，不在此门
        long orgId = Long.parseLong(s.orgId());           // PROPERTY 及兜底
        if (b.projectOrgId() == null || b.projectOrgId() != orgId) {
            throw new ApiException(BizError.PERM_403, "无权查看该批次回款明细");
        }
        if (s.isPC() && !pcCoordinatesBatch(s, b)) {      // PC 须协调该批次/项目
            throw new ApiException(BizError.PERM_403, "无权查看该批次回款明细（非协调范围）");
        }
    }

    /**
     * 回款明细行级裁剪（HIGH-1·与 rollup 同口径）：
     *   SA → 不追加（全量）；SE → data_range 三维（provider 维按到账归属快照 rl.provider_id_at_repay）；
     *   PROVIDER → rl.provider_id_at_repay = 本商（OUT 快照）；PROPERTY/PC → 已由 requirePropertySideAccess 限定，
     *     此处不再追加（PC 协调集已在门处复核）。
     */
    private void appendRepayLineScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        if (s.isPlatform()) {
            if (!s.isSE()) return;                        // SA 全量
            // SE：provider 维按到账归属快照；物业/区域按 batch→project；PC 维 null（SE 非 PC）。
            com.youzheng.huicui.common.DataScope.appendRange(
                    s, where, args, "rl.provider_id_at_repay", "p.org_id", "p.area", null, null);
            return;
        }
        if ("PROVIDER".equals(s.orgType())) {             // OUT 服务商按到账归属快照
            where.append(" AND rl.provider_id_at_repay = ?");
            args.add(Long.valueOf(s.orgId()));
        }
        // PROPERTY/PC：门处已限定 project.org_id(+协调集)，行级无需再叠加。
    }

    /** PC 协调集（B-02·batch 维）：批次 id ∈ batch_coordinators 或 批次 project_id ∈ project_coordinators。 */
    private boolean pcCoordinatesBatch(CurrentSubject s, BatchRow b) {
        Long acct;
        try { acct = s.accountId() == null ? null : Long.valueOf(s.accountId()); }
        catch (RuntimeException e) { acct = null; }
        if (acct == null) return false;
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT 1) x"
                        + " WHERE ? IN (SELECT batch_id FROM batch_coordinators WHERE coordinator_id = ?)"
                        + "    OR ? IN (SELECT project_id FROM project_coordinators WHERE coordinator_id = ?)",
                Long.class, b.id(), acct, b.projectId(), acct);
        return n != null && n > 0;
    }

    private static org.springframework.jdbc.core.RowMapper<RepayLineDto> repayLineRowMapper() {
        return (rs, i) -> new RepayLineDto(
                String.valueOf(rs.getLong("id")),
                String.valueOf(rs.getLong("case_id")),
                rs.getString("owner_name"),
                rs.getString("room"),
                longOrNull(rs, "amount_cents"),
                rs.getString("channel"),
                dateStr(rs),
                rs.getBoolean("settled"),
                idOrNull(rs, "payment_request_id"));
    }

    private static String dateStr(ResultSet rs) throws SQLException {
        java.sql.Date d = rs.getDate("paid_at");
        return d == null ? null : d.toLocalDate().toString();
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static String idOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : String.valueOf(v);
    }

    /** /batches/{id} 路径 id 非法形态 → 404（避免存在性泄漏 / 防 5xx）。 */
    private static long parseBatchId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "批次不存在");
        }
    }
}
