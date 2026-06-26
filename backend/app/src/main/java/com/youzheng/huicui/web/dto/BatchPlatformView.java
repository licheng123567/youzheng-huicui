package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 契约 Batch（平台全视角，BatchView discriminator viewRole=PLATFORM）。
 * 资金双线均含：commInRate(收佣线) + commInInherited + payOutRate(付佣线)。
 * 字段顺序对齐契约 BatchBase + Batch：id/projectId/code/providerId/coordinators/status/reduceMode/playbookMode/viewRole/commInRate/commInInherited/payOutRate。
 * 比率(commInRate/payOutRate)按契约 Rate=分数(0-1，如 0.08=8% · v1.0.3)原样返回，展示层 ×100。
 */
public record BatchPlatformView(
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
        java.math.BigDecimal payOutRate,
        Boolean reduceDrift,
        Boolean playbookDrift
) {}
