package com.youzheng.huicui.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 契约 MyStats（催收员"我的业绩·结算"·服务商内部考核口径·仅本人 BR-M9-19a）。
 * 批次为主线：顶部 KPI + 提成汇总（累计/已结/待结），rows 每行一个批次并携案件级明细 lines[]，
 * 每笔 lines[].settled 表示是否关联 SETTLED 内部结算单，前端据此分"已结算/未结算清单"。
 * 全部字段由真实数据实时聚合（到账归属快照口径 B-03）：
 *   repayCents/repayCases/commissionCents ← repay_line × co_commission（当月，顶部 KPI）
 *   total/settled/unsettledCommissionCents ← 全时段逐笔提成（结算汇总三宫格）
 *   connectRate/promiseFulfillRate ← activity 通话标记 / promise 兑现统计
 *   rows ← 按批次聚合（持有/回款/回款率/提成/已结待结/明细）
 * rate 均为分数 0-1（契约 Rate）；金额逐笔 Commission.lineCommissionCents 舍入后 SUM。
 */
public record MyStatsM9Dto(
        String month,
        Long repayCents,
        Integer repayCases,
        Long commissionCents,
        BigDecimal connectRate,
        BigDecimal promiseFulfillRate,
        Long totalCommissionCents,
        Long settledCommissionCents,
        Long unsettledCommissionCents,
        List<Row> rows) {

    public record Row(
            String batchId,
            String batch,
            String project,
            Integer holdCount,
            Long repayCents,
            BigDecimal repayRate,
            BigDecimal rate,
            Long commissionCents,
            Long settledCommissionCents,
            Long unsettledCommissionCents,
            Integer settledLineCount,
            Integer totalLineCount,
            List<Line> lines) {}

    public record Line(
            String caseId,
            String ownerMasked,
            String room,
            Long repayCents,
            Long commissionCents,
            String paidAt,
            Boolean settled,
            String closedAt) {}
}
