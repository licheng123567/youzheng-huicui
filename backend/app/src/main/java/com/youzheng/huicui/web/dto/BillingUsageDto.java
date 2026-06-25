package com.youzheng.huicui.web.dto;

/**
 * 能力用量 DTO（对齐契约 components.schemas.BillingUsage）。**只量不金额**（US-M10-02/BR-M10-01）。
 * 列名映射见 ReportsM10Controller：
 *   id←聚合键(type)，type←recharge_log.type(BillingTypeEnum STT/SMS/EVIDENCE/LEGAL)，
 *   qty←SUM(扣减用量)，unit←按 type 取('分钟/条/次/件')，caseId=null（报表口径不下钻到案），
 *   occurredAt←MAX(recharge_log.tm)。
 * 绝不携带金额字段——本模块只透出用量。
 */
public record BillingUsageDto(
        String id,
        String type,
        Double qty,
        String unit,
        String caseId,
        String occurredAt
) {}
