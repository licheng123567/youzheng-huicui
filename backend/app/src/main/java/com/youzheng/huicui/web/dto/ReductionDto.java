package com.youzheng.huicui.web.dto;

/**
 * 减免记录 DTO（对齐契约 components.schemas.Reduction）。M4 reduce 组。
 * 字段与契约 1:1：id/decide/state/amountCents。
 * 列名映射见 PayReduceRepayM4Controller：
 *   id←reduction.id, decide←reduction.decide(ReduceDecideEnum: COLLECTOR_SELF/OFFLINE_INTERNAL/PL_APPROVE),
 *   state←reduction.state(ReduceStateEnum: EFFECTIVE/OFFLINE_TRACE), amountCents←reduction.amount_cents(分 Long)。
 * 金额 amountCents 原样以「分」(Long) 返回，契约 Money=integer 分。
 */
public record ReductionDto(
        String id,
        String decide,
        String state,
        Long amountCents
) {}
