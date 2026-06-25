package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * POST /recordings/batch-parse（batchParseRecordings）DTO 集。
 * 对齐冻结契约：入参 {caseIds[]?, batchId?}；出参 202 {queued:int, skipped:int}。
 */
public final class BatchParseDtos {
    private BatchParseDtos() {}

    /** 入参：caseIds 与 batchId 二选一（都传则 caseIds 优先）；都空=本组织所有 QUOTA_BLOCKED 录音。 */
    public record BatchParseInput(List<String> caseIds, String batchId) {}

    /** 出参：queued=进入解析队列录音数，skipped=余额不足跳过数。 */
    public record BatchParseResult(int queued, int skipped) {}
}
