package com.youzheng.huicui.web.dto;

/**
 * 我的已发缴费链接跟踪单条（对齐契约 components.schemas.MyPayLinkItem）。M4 paylink 组。
 * GET /me/pay-links 专用展示行：发送时间/业主/房号/项目/批次/金额/渠道 + 展示态 status
 * （PayLinkDisplayStatusEnum：PENDING_VIEW/VIEWED_UNPAID/PAID/EXPIRED，区别于内部 pay_link.status）。
 */
public record MyPayLinkItemDto(
        String id,
        String caseId,
        String token,
        String sentAt,
        String ownerName,
        String room,
        String project,
        String batch,
        Long amountCents,
        String channel,
        String status
) {}
