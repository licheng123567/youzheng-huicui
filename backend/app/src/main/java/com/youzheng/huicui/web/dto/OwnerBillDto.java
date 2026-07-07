package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 业主缴费 H5 账单 DTO（对齐契约 components.schemas.OwnerBill）。M7 owner-h5 组。
 *
 * 隐私最小化 BR-M7-07：仅缴费必要信息，不含催收过程/timeline/他案/服务商/holder/org 任何字段；
 * public 端点凭单条 token 定位单案账单，不暴露越权数据。
 * 姓名脱敏后展示（首字+**）；房号为账单归属确认 + 对公转账备注必要信息。
 *
 * 字段映射（见 OwnerH5M7Controller）：
 *   community       ← "case".project_name（小区/项目名）
 *   ownerMasked     ← "case".owner_name 脱敏（首字+**，如 李**）
 *   room            ← "case".room（房号·对公转账备注填此）
 *   payableCents    ← "case".reduce_after_cents ?: due_cents（最终实缴=减免后应收·分 Long·Money）
 *   dueCents        ← "case".due_cents（减免前应收合计=物业费+滞纳金）
 *   penaltyCents    ← "case".penalty_cents（应收中滞纳金拆分；null=导入未拆分）
 *   penaltyPolicy   ← project.penalty（滞纳金政策文字；金额未拆分时的兜底展示）
 *   reductionCents  ← due_cents − payableCents（减免额·分 Long·Money）
 *   feeStd          ← project.fee_rows 摘要（string，复用 CasesM2.summarizeFeeRows；无则 null）
 *   arrearagePeriods← "case".arrearags_periods（jsonb→List<String>，复用 parseStringArray）
 *   installments    ← 承诺分期（BR-M7-06）
 *   payChannels     ← project.pay_info（JSON→{wechatQr,bankAccount}；无则两字段 null）
 *   onlinePay       ← 恒 false（本期线下缴·BR-M7-05）
 *
 * 金额 *_cents 原样以「分」(Long) 返回，契约 Money=integer 分，不转元。
 */
public record OwnerBillDto(
        String community,
        String ownerMasked,
        String room,
        Long payableCents,
        Long dueCents,
        Long penaltyCents,
        String penaltyPolicy,
        Long reductionCents,
        String feeStd,
        List<String> arrearagePeriods,
        List<InstallmentDto> installments,
        PayChannelsDto payChannels,
        boolean onlinePay
) {}
