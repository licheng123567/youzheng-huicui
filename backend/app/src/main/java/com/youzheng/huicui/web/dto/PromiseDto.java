package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 承诺 DTO（对齐契约 components.schemas.Promise）。
 * 列名映射：caseId←case_id, amountCents←amount_cents（金额「分」Long，不转元）,
 *           createdBy←created_by, createdAt←created_at。
 * state（promise.chk_promise_state，PromiseStateEnum）：PENDING/FULFILLED/PARTIAL_FULFILLED/BROKEN。
 * 分期时 state 由 installments 汇总（整体履约状态）；单笔则取 promise.state。
 */
public record PromiseDto(
        String id,
        String caseId,
        String date,
        Long amountCents,
        String state,
        List<PromiseInstallmentDto> installments,
        String createdBy,
        String createdAt
) {}
