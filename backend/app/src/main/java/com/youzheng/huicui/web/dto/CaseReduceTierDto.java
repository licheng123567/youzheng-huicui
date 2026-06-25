package com.youzheng.huicui.web.dto;

/**
 * 减免阶梯 DTO（对齐契约 components.schemas.ReduceTier，用于 CaseDetail.projectRef.reduceTiers）。
 * 列名映射：capCents←cap_cents, waivePenalty←waive_penalty, decide←decide。
 */
public record CaseReduceTierDto(
        String discount,
        Long capCents,
        boolean waivePenalty,
        String decide
) {}
