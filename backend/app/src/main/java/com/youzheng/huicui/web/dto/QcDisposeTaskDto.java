package com.youzheng.huicui.web.dto;

/**
 * 契约 DisposeTask（qc tag，仅平台监管视图）映射：
 *   id       = String(dispose_task.id)
 *   riskId   = String(risk_id)
 *   provider = org.name（JOIN provider 取责任组织展示名；列名 provider 存 org_id）
 *   taskType = task_type（整改/培训/警告/停权）
 *   status   = status（DisposeTaskStatusEnum PENDING|DONE）
 *   tm       = ISO(tm)
 */
public record QcDisposeTaskDto(
        String id,
        String riskId,
        String provider,
        String taskType,
        String status,
        String tm
) {}
