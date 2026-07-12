package com.youzheng.huicui.web.dto;

import java.math.BigDecimal;

/**
 * 平台批次双线总账行 DTO（对齐契约 components.schemas.ReconRollupDual·v1.16.0）。
 * 平台专属视角（SA/SE）：一行批次同时给出收佣(IN)与付佣(OUT)两线的 应结/已结/未结 + 毛利。
 *
 * 字段：
 *   batch/batchId/proj/period/baseCents/cnt/repayRate ← 口径同 ReconRollupM9Dto（未冲正明细聚合）
 *   commInRate        ← batch.comm_in_rate（分数 0-1，必有）
 *   dueInCents        ← Σ round(amount×commInRate)（应收佣）
 *   settledInCents    ← Σ(settled_in=TRUE × commInRate)（已收佣）
 *   unsettledInCents  ← dueIn - settledIn（未收佣）
 *   payOutRate        ← 付佣生效率=batch.pay_out_rate（v1.18.0 去 open_rate 兜底，与组单 resolveCommRate 口径一致）；
 *                       两率皆空 → null（未设付佣比例，OUT 四列+毛利同为 null）
 *   dueOutCents/settledOutCents/unsettledOutCents ← 付佣线同构（应付/已付/未付）；payOutRate=null 时
 *                       dueOut/unsettledOut/gross=null，settledOut 恒 0（无率无法组单）
 *   grossCents        ← dueIn - dueOut（平台毛利·应结口径）
 *
 * 金额 *_cents 原样「分」(Long)；Rate 为分数(0-1) 不×100；逐笔 round(amount×rate) 再 SUM（B-04，与 Commission 一致）。
 */
public record ReconRollupDualM9Dto(
        String batch,
        String batchId,
        String proj,
        String period,
        Long baseCents,
        Integer cnt,
        BigDecimal repayRate,
        BigDecimal commInRate,
        Long dueInCents,
        Long settledInCents,
        Long unsettledInCents,
        BigDecimal payOutRate,
        Long dueOutCents,
        Long settledOutCents,
        Long unsettledOutCents,
        Long grossCents
) {}
