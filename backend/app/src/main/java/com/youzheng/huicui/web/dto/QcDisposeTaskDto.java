package com.youzheng.huicui.web.dto;

/**
 * 契约 DisposeTask（qc tag）映射：平台监管全量 + 归属方(VL/PL)见本组织任务。
 *   id/riskId/provider(org.name)/taskType/status(PENDING|IN_PROGRESS|DONE)/tm。
 * 处置闭环补充（可空）：decision(平台处理决定)/decisionNote/targetAccount(当事人名)/receiptNote(整改回执)/receiptedAt。
 */
public record QcDisposeTaskDto(
        String id,
        String riskId,
        String provider,
        String taskType,
        String status,
        String tm,
        String decision,
        String decisionNote,
        String targetAccount,
        String receiptNote,
        String receiptedAt
) {}
