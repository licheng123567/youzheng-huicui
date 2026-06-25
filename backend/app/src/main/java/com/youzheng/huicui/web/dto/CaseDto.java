package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 案件 DTO（对齐契约 components.schemas.Case）。
 * 字段与契约 1:1；金额 *_cents 原样以「分」返回（契约 Money = integer 分，不转元）。
 * 列名映射见 CasesM2Controller 的 RowMapper：
 *   acctNo←acct_no, projectName←project_name(冗余直读免 JOIN),
 *   dueCents←due_cents, reduceAfterCents←reduce_after_cents,
 *   arrearagePeriods←arrearags_periods(jsonb), litigationFields←litigation_fields(jsonb|null),
 *   legalStage←legal_stage, holderId←holder_id, t2DeadlineAt←t2_deadline,
 *   tCollectorDeadlineAt←t_collector_deadline, closedKind←closed_kind, closedAt←closed_at。
 * redacted=true 时（BR-M8-09）ownerName 为脱敏占位、明细置占位/空。
 */
public record CaseDto(
        String id,
        String acctNo,
        String batchId,
        String projectId,
        String projectName,
        String ownerName,
        String room,
        Long dueCents,
        Long reduceAfterCents,
        List<String> arrearagePeriods,
        Object litigationFields,
        String status,
        String legalStage,
        String holderId,
        String pool,
        String source,
        String t2DeadlineAt,
        String tCollectorDeadlineAt,
        String closedKind,
        String closedAt,
        boolean redacted
) {}
