package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 契约 BatchForProvider（服务商视角，BatchView discriminator viewRole=PROVIDER）。
 * 资金双线字段级隔离 BR-M9-11：**含 payOutRate（付佣线）；物理不含 commInRate/commInInherited（收佣线）**。
 * 比率按契约 Rate=number 原样返回百分比，不转分。
 */
public record BatchProviderView(
        String id,
        String projectId,
        String code,
        String providerId,
        List<BatchCoordinatorRef> coordinators,
        String status,
        String reduceMode,
        String playbookMode,
        String viewRole,
        java.math.BigDecimal payOutRate
) {}
