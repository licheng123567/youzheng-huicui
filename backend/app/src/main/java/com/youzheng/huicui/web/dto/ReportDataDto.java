package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 经营报表 DTO（对齐契约 components.schemas.ReportData）。M10 reports 模块。
 * 角色口径(scope)：平台 'PLATFORM_ALL'；物业 'PROPERTY:{orgId}'；服务商 'PROVIDER:{orgId}'。
 *   kpis            聚合 KPI 列表（应收/回款/回款率/案件数）；
 *   rows            按 dimension(project/batch/month) 聚合行；
 *   capabilityUsage 能力用量（只量不金额 US-M10-02）。
 * 空结果合法：返回空数组而非 null。
 */
public record ReportDataDto(
        String scope,
        List<ReportKpiDto> kpis,
        List<ReportRowDto> rows,
        List<BillingUsageDto> capabilityUsage
) {}
