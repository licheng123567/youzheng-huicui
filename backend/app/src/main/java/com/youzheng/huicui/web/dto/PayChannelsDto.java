package com.youzheng.huicui.web.dto;

/**
 * 项目收款渠道 DTO（对齐契约 OwnerBill.payChannels）。M7 owner-h5 组。
 *
 * 来源 project.pay_info（TEXT 存 JSON 串）。无收款信息时两字段均为 null。
 * 字段与契约 1:1：wechatQr（微信收款码 URL）/bankAccount（对公账户展示串）。
 */
public record PayChannelsDto(
        String wechatQr,
        String bankAccount
) {}
