package com.youzheng.huicui.web.dto;

/**
 * 缴费链接 DTO（对齐契约 components.schemas.PayLink）。M4 paylink 组。
 * 字段与契约 1:1：id/token/amountCents/expiresAt/status。
 * 列名映射见 PayReduceRepayM4Controller：
 *   id←pay_link.id, token←pay_link.token, amountCents←pay_link.amount_cents(分 Long),
 *   expiresAt←pay_link.expires_at(date-time ISO), status←pay_link.status(PayLinkStatusEnum: ACTIVE/EXPIRED)。
 * 金额 amountCents 原样以「分」(Long) 返回，契约 Money=integer 分，不转元。
 */
public record PayLinkDto(
        String id,
        String token,
        Long amountCents,
        String expiresAt,
        String status
) {}
