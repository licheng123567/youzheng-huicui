package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 支付申请单 DTO（对齐契约 components.schemas.PaymentRequest）。M9 组1·资金双线·最多审计模型。
 * 类名带 M9 后缀，与 M1-M4 DTO 物理隔离。
 *
 * 字段与契约 1:1（列名映射见 PaymentRequestM9Controller）：
 *   id←payment_request.id, code←payment_request.no（单号 PR-IN/OUT-batch-seq，避 YAML1.1 布尔陷阱故契约改名 code）,
 *   side←side(IN/OUT), batchId←batch_id, generatedBy←generated_by（服务端派生·不接受前端传）,
 *   commRate←comm_rate（NUMERIC(6,4) 分数 0-1，v1.0.3 一致不×100）,
 *   lines←lines JSONB 快照 [{lineId,caseId,ownerName,room,repayCents,commCents}],
 *   feeLines←计费明细（仅 IN 线含存证/法律按次；OUT 线一般空 BR-M9-10/07/08），
 *   baseCents←base_cents（Σ明细 amount_cents·分）, commCents←comm_cents（Σ round(amount×rate)·分）,
 *   status←status(PENDING/PAID/VOIDED), voucher←PAID 必有, documentUrl 占位 null, sealed 占位 false,
 *   createdBy←generated_by 展示, createdAt←created_at, completedBy←completed_by, completedAt←completed_at,
 *   voidedAt←voided_at, voidReason←void_reason, version←version（乐观锁）。
 *
 * 所有金额 *_cents 原样以「分」(Long) 返回；commRate 为分数（0-1）DB/后端/前端一致不×100。
 */
public record PaymentRequestM9Dto(
        String id,
        String code,
        String side,
        String batchId,
        String generatedBy,
        java.math.BigDecimal commRate,
        List<LineSnapshot> lines,
        List<FeeLineItemM9Dto> feeLines,
        Long baseCents,
        Long commCents,
        String status,
        VoucherM9Dto voucher,
        String documentUrl,
        boolean sealed,
        String createdBy,
        String createdAt,
        String completedBy,
        String completedAt,
        String voidedAt,
        String voidReason,
        Integer version
) {
    /** lines JSONB 明细快照单元（对齐契约 PaymentRequest.lines.items）。repayCents/commCents 为分(Long)。 */
    public record LineSnapshot(
            String lineId,
            String caseId,
            String ownerName,
            String room,
            Long repayCents,
            Long commCents
    ) {}
}
