package com.youzheng.huicui.web.dto;

import java.math.BigDecimal;

/**
 * 对账汇总行 DTO（对齐契约 components.schemas.ReconRollup）。M9 组3 对账汇总（展示用·组单数据源）。
 *
 * 字段与契约 1:1：
 *   batch        ← batch.no（批次编号，展示名）
 *   batchId      ← batch.id
 *   proj         ← project.name（项目名）
 *   period       ← 入参 period（如 2026-06；未传则汇总全期 → 回显 null）
 *   baseCents    ← Σ 未冲正 repay_line.amount_cents（基数口径=减免后实收·不含税 BR-M9-01b·分 Long）
 *   cnt          ← 未冲正 repay_line 笔数
 *   repayRate    ← 回款率·分数(0-1)：base / 批次应收（无应收口径时给 null）
 *   commRate     ← 本单线别比率快照·分数(0-1)：IN=batch.comm_in_rate / OUT=batch.pay_out_rate（NUMERIC(6,4) 原样，不×100）
 *   dueCents     ← 应结佣金=round(baseCents × commRate)（分 Long）
 *   settledCents ← 已纳入 PAID 单的明细佣金（settled=TRUE 部分 × commRate·分 Long）
 *   unsettledCents ← dueCents - settledCents
 *
 * 金额 *_cents 原样以「分」(Long) 返回；Rate 列为分数(0-1)，DB/后端/前端一致不×100（v1.0.3）。
 */
public record ReconRollupM9Dto(
        String batch,
        String batchId,
        String proj,
        String period,
        Long baseCents,
        Integer cnt,
        BigDecimal repayRate,
        BigDecimal commRate,
        Long dueCents,
        Long settledCents,
        Long unsettledCents
) {}
