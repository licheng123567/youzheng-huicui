package com.youzheng.huicui.web.dto;

/**
 * 契约 CoCommissionBatchRow（催收员佣金按批次下钻行·M-05 穿透·服务商内部 BR-M9-19）。
 * 从 listCoCommissions 按人聚合下钻到批次级；dueCents/unsettledCents 由
 * repay_line × co_commission.rate 实时汇总（非存储），settled 判定走 co_pay_doc.status=SETTLED。
 * 金额一律 *_cents（Long，分）原样返回；rate 为 NUMERIC(6,4) 分数（0-1），不×100。
 */
public record CoCommissionBatchRowM9Dto(
        String batchId,
        String batchName,
        java.math.BigDecimal rate,
        Long dueCents,
        Long unsettledCents,
        Integer unsettledLineCount) {}
