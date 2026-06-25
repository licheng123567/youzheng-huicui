package com.youzheng.huicui.web.dto;

/**
 * 对齐契约 schema CallRecording（M4 recordings 组）。
 * 字段顺序/名称严格对齐 openapi-core.yaml#/components/schemas/CallRecording：
 *   id, caseId, collectorId, source(APP_AUTO|MANUAL), status(CallRecStatusEnum),
 *   recordedAt(date-time|null), durationSec(integer|null), phone(|null),
 *   transcript(|null), failureCode(|null), failureMessage(|null)。
 * id/caseId/collectorId 一律以 string 出参（契约 type:string）；durationSec 为分钟外的「秒」整数。
 * 金额无涉（录音无 *_cents 字段）。
 */
public record CallRecordingDto(
        String id,
        String caseId,
        String collectorId,
        String source,
        String status,
        String recordedAt,
        Integer durationSec,
        String phone,
        String transcript,
        String failureCode,
        String failureMessage) {
}
