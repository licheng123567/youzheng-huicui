package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 契约 BatchForProperty（物业视角，BatchView discriminator viewRole=PROPERTY）。
 * 资金双线字段级隔离 BR-M9-11：**含 commInRate + commInInherited（收佣线）；物理不含 payOutRate（付佣线）**。
 * 比率按契约 Rate=分数(0-1，如 0.08=8% · v1.0.3)原样返回，展示层 ×100。
 */
public record BatchPropertyView(
        String id,
        String projectId,
        String code,
        String providerId,
        List<BatchCoordinatorRef> coordinators,
        String status,
        String reduceMode,
        String playbookMode,
        String viewRole,
        java.math.BigDecimal commInRate,
        Boolean commInInherited,
        Boolean reduceDrift,
        Boolean playbookDrift
) {}
