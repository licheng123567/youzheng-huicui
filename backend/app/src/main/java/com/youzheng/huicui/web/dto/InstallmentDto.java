package com.youzheng.huicui.web.dto;

/**
 * 政策分期行 DTO（对齐契约 OwnerBill.installments[]）。M7 owner-h5 组。
 *
 * BR-M7-06：政策分期（来自减免规则分期/政策分期），区别于承诺分期（promise_installment）。
 * 字段与契约 1:1：period/dueDate/amountCents/status。
 * amountCents 原样以「分」(Long) 返回，契约 Money=integer 分，不转元。
 */
public record InstallmentDto(
        String period,
        String dueDate,
        Long amountCents,
        String status
) {}
