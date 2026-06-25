package com.youzheng.huicui.web.dto;

/**
 * 计费明细 DTO（对齐契约 components.schemas.FeeLineItem）。M9 组1。
 * 仅收佣线(IN)含存证/法律按次计入对账的计费构成；付佣线(OUT)一般空（BR-M9-10/07/08）。
 * type←BillingTypeEnum, qty 数量(次/件), unitCents 单价(分), amountCents 小计(分), refId 关联文书 id,
 * ownerName/room 业主/房号。金额 *_cents 原样以「分」返回。
 *
 * 地基期 feeLines 由存证(evidence)/法律(legal_doc)按次计费聚合；M9 组1 读端点先返回空列表占位，
 * 待计费明细聚合接入（与 recon/rollup 同源）后填充。TODO(M9-fee)。
 */
public record FeeLineItemM9Dto(
        String type,
        Double qty,
        Long unitCents,
        Long amountCents,
        String refId,
        String ownerName,
        String room
) {}
