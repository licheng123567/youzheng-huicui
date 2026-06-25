package com.youzheng.huicui.web.dto;

/**
 * 契约 RiskRecord（qc tag）映射：
 *   id        = String(risk_record.id)
 *   caseId    = String(case_id)
 *   collector = account.name（JOIN collector_id 取违规人展示名）
 *   type      = type（自由文本 CFG-RISK-TYPES）
 *   level     = level（RiskLevelEnum HIGH|MID|LOW）
 *   segmentTs = segment_ts（"mm:ss"）
 *   reviewed  = reviewed（RiskReviewVerdictEnum；未复核 NULL→null，契约 oneOf null）
 */
public record RiskRecordDto(
        String id,
        String caseId,
        String collector,
        String type,
        String level,
        String segmentTs,
        String reviewed
) {}
