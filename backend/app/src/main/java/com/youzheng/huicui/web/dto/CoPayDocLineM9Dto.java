package com.youzheng.huicui.web.dto;

/**
 * 契约 CoPayDoc.lines[] 穿透明细快照（催收员→批次→案件回款明细 BR-M9-19/US-M9-10）。
 * repayCents=该笔回款（基数·减免后实收·不含税）；commCents=round(repayCents × co_commission.rate)。
 */
public record CoPayDocLineM9Dto(
        String lineId,
        String caseId,
        String ownerName,
        String room,
        Long repayCents,
        Long commCents) {}
