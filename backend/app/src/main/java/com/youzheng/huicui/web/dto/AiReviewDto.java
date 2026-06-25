package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 对齐契约 schema AiReview（getAiReview 出参 BR-M5-04a）。
 *   callId, summary,
 *   dialogue: [{speaker, text}]（ai_review.dialogue jsonb 解析），
 *   risks:    [{level(RiskLevelEnum), desc, segmentTs}]（ai_review.risks jsonb 解析），
 *   suggestions: [StrategyCard]（ai_review.suggestions jsonb 解析；地基期原样透传 jsonb 元素）。
 * dialogue/risks/suggestions 用 Object 承接 jsonb 解析结果，避免与其它组的 StrategyCard 等 DTO 物理耦合。
 */
public record AiReviewDto(
        String callId,
        String summary,
        List<Object> dialogue,
        List<Object> risks,
        List<Object> suggestions) {
}
