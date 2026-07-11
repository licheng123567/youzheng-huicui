package com.youzheng.huicui.web.dto;

/**
 * 回款明细 DTO（对齐契约 components.schemas.RepayLine）。M4 repay 组。
 * 基础字段与契约 1:1：id/caseId/ownerName/room/amountCents/channel/paidAt/settled/paymentRequestId。
 * 列名映射见 PayReduceRepayM4Controller：
 *   id←repay_line.id, caseId←repay_line.case_id, ownerName←case.owner_name(JOIN), room←case.room(JOIN),
 *   amountCents←repay_line.amount_cents(分 Long), channel←repay_line.channel(ChannelEnum: WECHAT_QR/BANK_TRANSFER/CASH),
 *   paidAt←repay_line.paid_at(date 仅日期)。
 * 金额 amountCents 原样以「分」(Long) 返回，契约 Money=integer 分；paidAt 为 date（yyyy-MM-dd）。
 *
 * 【V929 双线扩展（契约 v1.16.0·全部可空·按视角字段级省略，jackson non_null 全局丢 null）】
 *   legacy settled/paymentRequestId 已废弃为「按视角映射」：PROVIDER 视角=OUT 线值；其余（平台/物业）=IN 线值——
 *     保证旧前端（SettlementView 未占用过滤）对 PL/PC(IN) 与 VL(OUT) 行为逐位不变。
 *   收佣线（平台+物业可见）：commInCents=round(amount×comm_in_rate)、settledIn、paymentRequestIdIn、prNoIn。
 *   付佣线（平台+服务商可见）：commOutCents=round(amount×pay_out_rate)（v1.18.0 去 open_rate 兜底）、settledOut、
 *     paymentRequestIdOut、prNoOut；批次无付佣生效率时 commOutCents=null。
 *   仅平台可见：providerIdAtRepay/providerName（到账归属快照，支撑 OUT 组单「一单一家」的前端分组勾选）。
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
        String paymentRequestId,
        Long commInCents,
        Boolean settledIn,
        String paymentRequestIdIn,
        String prNoIn,
        Long commOutCents,
        Boolean settledOut,
        String paymentRequestIdOut,
        String prNoOut,
        String providerIdAtRepay,
        String providerName
) {}
