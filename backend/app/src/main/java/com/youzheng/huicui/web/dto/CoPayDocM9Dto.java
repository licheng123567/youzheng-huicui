package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 契约 CoPayDoc（佣金支付单据，服务商内部 BR-M9-19）。
 * status ∈ CoPayDocStatusEnum {PENDING_PAY, SETTLED}。
 * list 端点 lines 可为 null（不穿透）；get 端点带 lines 快照。
 */
public record CoPayDocM9Dto(
        String id,
        String collectorId,
        String collectorName,
        Integer count,
        Long amountCents,
        String status,
        String createdAt,
        List<CoPayDocLineM9Dto> lines) {}
