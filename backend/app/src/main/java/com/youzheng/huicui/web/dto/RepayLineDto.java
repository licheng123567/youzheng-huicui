package com.youzheng.huicui.web.dto;

/**
 * 回款明细 DTO（对齐契约 components.schemas.RepayLine）。M4 repay 组。
 * 字段与契约 1:1：id/caseId/ownerName/room/amountCents/channel/paidAt/settled/paymentRequestId。
 * 列名映射见 PayReduceRepayM4Controller：
 *   id←repay_line.id, caseId←repay_line.case_id, ownerName←case.owner_name(JOIN), room←case.room(JOIN),
 *   amountCents←repay_line.amount_cents(分 Long), channel←repay_line.channel(ChannelEnum: WECHAT_QR/BANK_TRANSFER/CASH),
 *   paidAt←repay_line.paid_at(date 仅日期), settled←repay_line.settled, paymentRequestId←repay_line.payment_request_id(未结 null)。
 * 金额 amountCents 原样以「分」(Long) 返回，契约 Money=integer 分；paidAt 为 date（yyyy-MM-dd）。
 */
public record RepayLineDto(
        String id,
        String caseId,
        String ownerName,
        String room,
        Long amountCents,
        String channel,
        String paidAt,
        Boolean settled,
        String paymentRequestId
) {}
