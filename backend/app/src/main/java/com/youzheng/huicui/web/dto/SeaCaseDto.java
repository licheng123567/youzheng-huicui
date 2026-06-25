package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 公海池案件视图（GET /sea 专用），对齐契约 components.schemas.SeaCase = Case + 竞争态字段。
 * allOf(Case) 部分以内联字段平铺（与 CaseDto 1:1，金额 *_cents 以「分」返回），
 * 附 BR-M3-20/21/21a 竞争态：
 *   viewerCount       正在查看人数（M3 暂置 0，SSE/轮询接入后回填）
 *   sourceBadge       入池来源徽标 = PoolEnum（取 origin_pool/pool 痕迹）
 *   competitionState  AVAILABLE/VIEWING/CLAIMED
 *   contactMasked     未持有时 true（BR-M3-21a 电话脱敏）
 *   eventCursor       实时事件游标（M3 置 null）
 *   capacityHint      距持有上限余量（按当前主体 CO 持有数推算，可空）
 */
public record SeaCaseDto(
        // ── allOf(Case) ──
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
        boolean redacted,
        // ── 竞争态扩展 ──
        Integer viewerCount,
        String sourceBadge,
        String competitionState,
        boolean contactMasked,
        String eventCursor,
        Integer capacityHint
) {}
