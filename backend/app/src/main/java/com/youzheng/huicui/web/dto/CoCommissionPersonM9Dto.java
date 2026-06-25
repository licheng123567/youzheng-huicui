package com.youzheng.huicui.web.dto;

/**
 * 契约 CoCommissionPerson（按催收员聚合的佣金视图，服务商内部 BR-M9-19）。
 * dueCents/settledCents/unsettledCents 由 repay_line × co_commission.rate 实时汇总（非存储）。
 * 金额一律 *_cents（Long，分）原样返回。
 */
public record CoCommissionPersonM9Dto(
        String collectorId,
        String name,
        Integer batchCount,
        Long dueCents,
        Long settledCents,
        Long unsettledCents) {}
