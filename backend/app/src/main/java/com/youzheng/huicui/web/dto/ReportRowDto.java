package com.youzheng.huicui.web.dto;

/**
 * 经营报表聚合行 DTO（对齐契约 components.schemas.ReportRow）。
 * 按 dimension(project/batch/month) 分组聚合：
 *   dimKey/dimName ← project→p.id/p.name；batch→b.id/b.no；month→to_char(c.created_at,'YYYY-MM')；
 *   dueCents=SUM(c.due_cents)（分 Long）；repayCents=COALESCE(SUM(r.amount_cents),0)（仅 reversed=false 明细）；
 *   repayRate=repayCents/dueCents（0 分母→0，0-1 分数）；caseCount=COUNT(DISTINCT c.id)。
 * 金额 *_cents 原样以「分」(Long) 返回，契约 Money=integer 分。
 */
public record ReportRowDto(
        String dimKey,
        String dimName,
        Long dueCents,
        Long repayCents,
        Double repayRate,
        Long caseCount
) {}
