package com.youzheng.huicui.web.dto;

/**
 * 组织额度行（契约 OrgQuota·v1.19.0）。**一行 = 一个组织 × 一个额度类型**（扁平，前端表格直接吃）。
 * balance 可为负（EVIDENCE/LEGAL 后付费=欠用记账）。
 * rechargeable 来自后端 BillingUnits.rechargeable（org×type 矩阵唯一真源）——前端据此渲染/禁用充值按钮，不复刻规则。
 */
public record OrgQuotaDto(
        String orgId,
        String orgName,
        String orgType,
        String type,
        String unit,
        Double balance,
        Double usedThisMonth,
        Double usedLastMonth,
        Boolean rechargeable
) {}
