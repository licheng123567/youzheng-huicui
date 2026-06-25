package com.youzheng.huicui.web.dto;

/**
 * 经营报表 KPI 项 DTO（对齐契约 components.schemas.ReportKpi）。
 * kind 判别(MONEY/RATE/COUNT)只填对应值字段，其余 null：
 *   kind=MONEY → amountCents(分 Long)，rate/count=null；
 *   kind=RATE  → rate(0-1 分数)，amountCents/count=null；
 *   kind=COUNT → count(integer)，amountCents/rate=null。
 * 金额 amountCents 原样以「分」(Long) 返回，契约 Money=integer 分。
 */
public record ReportKpiDto(
        String label,
        String kind,
        Long amountCents,
        Double rate,
        Long count
) {
    public static ReportKpiDto money(String label, long amountCents) {
        return new ReportKpiDto(label, "MONEY", amountCents, null, null);
    }

    public static ReportKpiDto rate(String label, double rate) {
        return new ReportKpiDto(label, "RATE", null, rate, null);
    }

    public static ReportKpiDto count(String label, long count) {
        return new ReportKpiDto(label, "COUNT", null, null, count);
    }
}
