package com.youzheng.huicui.web;

import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * M3 服务商（providers）读端点（公海/派单桶·释放记录）。只读，无状态转移。
 * 类名独立 ProvidersController，与 M1/M2/M3 既有 controller 物理隔离；只承载释放记录读端点。
 *
 * 端点（基路径 /v1 由 context-path 提供，注解写裸路径）：
 *   GET /providers/{id}/release-records  listReleaseRecords  | scope=own-org | ReleaseRecordPage
 *
 * 契约口径（openapi-core.yaml listReleaseRecords + ReleaseRecord/ReleaseRecordPage·BR-M3-27）：
 *   - 无 x-permission（靠 x-data-scope=own-org 控可见性：VL 仅本服务商）。
 *   - own-org：非平台主体仅能看本组织({id}=本 org)；他商/无关池 → 403。平台主体可看任意服务商。
 *   - 地基期降级实现：由 audit_log(action='case.release') 派生释放记录（无独立释放记录表）。
 *       collectorId = 被释放案件的持有催收员（before_snap->>'holderId'）。
 *       kind = MANUAL（催收员本人主动释放：actor_id = holderId）/ AUTO（系统或管理员触发回流）。
 *       注：CFG-TC 定时自动释放(ExpiryService.expireTC)地基期仅写 activity 不写 audit_log，
 *           故此处覆盖「主动释放 + 成员停用自动释放」两类 audit_log 留痕；纯定时自动释放待接独立表后补全。
 *   - 频次：按 collectorId 聚合的统计由前端在 items 上汇总（契约 ReleaseRecord 为逐条记录，分页返回）。
 *
 * 健壮性（绝不 5xx）：路径 id 非法→404；越权→403；page/size 越界由 Pageable 规整；空集合法返回 200 空页。
 */
@RestController
public class ProvidersController {

    private final JdbcTemplate jdbc;

    public ProvidersController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    /** 释放记录（契约 ReleaseRecord）。kind=MANUAL/AUTO。 */
    public record ReleaseRecord(String collectorId, String collectorName, String caseId,
                                String reason, String kind, String at) {}

    // ── GET /providers/{id}/release-records ──────────────────────────────────
    @GetMapping("/providers/{id}/release-records")
    public Page<ReleaseRecord> listReleaseRecords(
            @PathVariable("id") String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        long providerId = parseId(id);
        // own-org：非平台主体只可看本组织；他商 → 403（不泄露他商释放频次 BR-M3-27）。
        if (!s.isPlatform()) {
            Long org = orgIdOrNull(s);
            if (org == null || org != providerId) {
                throw new ApiException(BizError.PERM_403, "无权查看该服务商释放记录");
            }
        }
        // 目标服务商须存在且为 PROVIDER，否则 404。
        if (!providerExists(providerId)) {
            throw new ApiException(BizError.NOT_FOUND_404, "服务商不存在: " + id);
        }

        Pageable pg = Pageable.of(page, size);

        // 释放轨迹来源：audit_log(action='case.release')，按【事件发生时】案件承接商裁剪。
        // 修 codex HIGH：原用当前 batch.provider_id 过滤，批次/案件再派后旧 provider 的释放历史会丢失或串到新 provider。
        // 改按释放当时快照 before_snap->>'providerId'（=释放前案件承接 org）过滤，历史归属稳定不随再派漂移。
        // collector = 被释放持有人（before_snap->>'holderId'）；kind 由 actor_id 与 holderId 比对判定。
        String base =
                "FROM audit_log al"
                        + " LEFT JOIN account acc ON acc.id = (al.before_snap ->> 'holderId')::bigint"
                        + " WHERE al.action = 'case.release'"
                        + " AND al.target_type = 'case'"
                        + " AND al.before_snap ->> 'holderId' IS NOT NULL"
                        + " AND (al.before_snap ->> 'providerId')::bigint = ?";

        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, providerId);

        List<Object> args = new ArrayList<>();
        args.add(providerId);
        args.add(pg.size);
        args.add(pg.offset);
        String listSql =
                "SELECT al.before_snap ->> 'holderId' AS collector_id,"
                        + " acc.name AS collector_name,"
                        + " al.target_id AS case_id,"
                        + " al.reason AS reason,"
                        + " al.actor_id AS actor_id,"
                        + " al.tm AS at "
                        + base
                        + " ORDER BY al.tm DESC, al.id DESC LIMIT ? OFFSET ?";
        List<ReleaseRecord> items = jdbc.query(listSql, this::mapReleaseRecord, args.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ReleaseRecord mapReleaseRecord(ResultSet rs, int i) throws SQLException {
        String collectorId = rs.getString("collector_id");
        Long actorId = (Long) rs.getObject("actor_id");
        // kind：actor 即持有人本人 → MANUAL（催收员主动释放）；否则系统/管理员触发回流 → AUTO。
        boolean manual = actorId != null && collectorId != null
                && String.valueOf(actorId).equals(collectorId);
        return new ReleaseRecord(
                collectorId,
                rs.getString("collector_name"),
                rs.getString("case_id"),
                rs.getString("reason"),
                manual ? "MANUAL" : "AUTO",
                ts(rs.getTimestamp("at")));
    }

    /** org 须存在且为 PROVIDER（不限 status，停用商历史释放仍可查）。 */
    private boolean providerExists(long providerId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM org WHERE id = ? AND type = 'PROVIDER'",
                Integer.class, providerId);
        return n != null && n > 0;
    }

    /** 路径 id 非法形态统一 404，避免存在性泄漏 / 防 5xx。 */
    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "服务商不存在: " + id);
        }
    }

    private static Long orgIdOrNull(CurrentSubject s) {
        try {
            return s.orgId() == null ? null : Long.valueOf(s.orgId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }
}
