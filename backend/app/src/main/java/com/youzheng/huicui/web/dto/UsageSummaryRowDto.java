package com.youzheng.huicui.web.dto;

/**
 * 用量聚合行（契约 UsageSummaryRow·v1.19.0）：按 组织 × 类型 × 时间桶（月/日）聚合。
 * bucket = YYYY-MM（groupBy=month）或 YYYY-MM-DD（groupBy=day）；qty 累计用量、count 笔数。
 */
public record UsageSummaryRowDto(
        String bucket,
        String orgId,
        String orgName,
        String type,
        String unit,
        Double qty,
        Integer count
) {}
