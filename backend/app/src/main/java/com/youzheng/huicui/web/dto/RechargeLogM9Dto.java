package com.youzheng.huicui.web.dto;

/**
 * 充值/扣减流水 DTO（对齐契约 components.schemas.RechargeLog）。
 * **只量不金额**（US-M10-02/BR-M9-06a）：delta/balance 为用量单位（分钟/条/次/件），非金额，无 *_cents。
 * 列名映射见 BillingM9Controller：
 *   id←recharge_log.id，type←recharge_log.type(BillingTypeEnum STT/SMS/EVIDENCE/LEGAL)，
 *   delta←recharge_log.delta(+充/-扣 NUMERIC→number)，balance←recharge_log.balance(操作后余额快照 NUMERIC→number)，
 *   ref←recharge_log.ref(关联单据号)，tm←recharge_log.tm。
 */
public record RechargeLogM9Dto(
        String id,
        String type,
        Double delta,
        Double balance,
        String ref,
        String tm
) {}
