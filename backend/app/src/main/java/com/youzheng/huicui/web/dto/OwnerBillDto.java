package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 业主缴费 H5 账单 DTO（对齐契约 components.schemas.OwnerBill）。M7 owner-h5 组。
 *
 * 隐私最小化 BR-M7-07：仅缴费必要信息，不含催收过程/timeline/他案/服务商/holder/org 任何字段；
 * public 端点凭单条 token 定位单案账单，不暴露越权数据。
 *
 * 字段映射（见 OwnerH5M7Controller）：
 *   community       ← "case".project_name（小区/项目名）
 *   payableCents    ← "case".reduce_after_cents ?: due_cents（减免后应收·分 Long·Money）
 *   reductionCents  ← due_cents − payableCents（减免额·分 Long·Money）
 *   feeStd          ← project.fee_rows 摘要（string，复用 CasesM2.summarizeFeeRows；无则 null）
 *   arrearagePeriods← "case".arrearags_periods（jsonb→List<String>，复用 parseStringArray）
 *   installments    ← 政策分期（BR-M7-06；地基期返 null，TODO）
 *   payChannels     ← project.pay_info（JSON→{wechatQr,bankAccount}；无则两字段 null）
 *   onlinePay       ← 恒 false（本期线下缴·BR-M7-05）
 *
 * 金额 *_cents 原样以「分」(Long) 返回，契约 Money=integer 分，不转元。
 */
public record OwnerBillDto(
        String community,
        Long payableCents,
        Long reductionCents,
        String feeStd,
        List<String> arrearagePeriods,
        List<InstallmentDto> installments,
        PayChannelsDto payChannels,
        boolean onlinePay
) {}
