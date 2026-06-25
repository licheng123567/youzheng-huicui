package com.youzheng.huicui.web.dto;

/**
 * 承诺分期明细 DTO（对齐契约 components.schemas.PromiseInstallment）。
 * 列名映射：dueDate←due_date, amountCents←amount_cents（金额「分」Long，不转元）, state←state。
 * state 枚举（promise_installment.chk_installment_state）：PENDING/FULFILLED/BROKEN。
 */
public record PromiseInstallmentDto(
        Integer seq,
        String dueDate,
        Long amountCents,
        String state
) {}
