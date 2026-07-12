package com.youzheng.huicui.web.dto;

/**
 * 经营报表聚合行 DTO（对齐契约 components.schemas.ReportRow）。
 * 按 dimension(project/batch/month) 分组聚合：
 *   dimKey/dimName ← project→p.id/p.name；batch→b.id/b.no；month→to_char(c.created_at,'YYYY-MM')；
 *   dueCents=SUM(c.due_cents)（分 Long）；repayCents=COALESCE(SUM(r.amount_cents),0)（仅 reversed=false 明细）；
 *   repayRate=repayCents/dueCents（0 分母→0，0-1 分数）；caseCount=COUNT(DISTINCT c.id)。
 * 金额 *_cents 原样以「分」(Long) 返回，契约 Money=integer 分。
 */
public record ReportRowDto(
        String dimKey,
        String dimName,
        Long dueCents,
        Long repayCents,
        Double repayRate,
        Long caseCount,
        // v1.25.1 佣金双线（口径与 /recon/rollup-dual 完全一致：按每笔回款 × 该批次的比率，
        // 已收/已付看 repay_line 的 settled_in / settled_out；只计未冲正 reversed=false）：
        //   IN 线 = 物业付给平台的收佣；OUT 线 = 平台付给服务商的付佣。
        Long commInDueCents,        // 应收佣金
        Long commInSettledCents,    // 已收佣金
        Long commInUnsettledCents,  // 待收佣金 = 应收 − 已收
        Long commOutDueCents,       // 应付佣金
        Long commOutSettledCents,   // 已付佣金
        Long commOutUnsettledCents, // 待付佣金
        // 未设付佣比例的批次数：这些批次的应付按 0 计入，会低估「应付佣金」——必须让人看见，
        // 否则一个漏配比率的批次就把平台的欠款藏起来了。
        Long outRateMissingBatches
) {}
