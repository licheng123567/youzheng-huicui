package com.youzheng.huicui.web;

import com.youzheng.huicui.common.DataScope;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.SettingsDtos.SmsSendRecordDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * settings 组 短信明细端点（契约 listSmsRecords；基路径 /v1 由 context-path 提供）。
 * 类名 SmsRecordsController（本批新建）。依赖 V5__sms_record.sql 真表（已建）。
 *
 * 横切落地：
 *   - GET /sms-records   listSmsRecords：x-data-scope=range，无 x-permission。
 *       range：平台全量；物业/服务商仅本组织（DataScope.ownOrg on sms_record.org_id）。
 *
 * 业务口径：
 *   - 失败不退条数（BR-M9-08）：FAILED 行照常返回，带 failure_reason（不从结果集剔除）。
 *   - 空集返回 items:[]（无错误码）。
 *   - 注：导出端点 TBD（契约 x-exportable 提示），本端点仅提供分页明细。
 *
 * 列名核对见类尾注释。
 */
@RestController
public class SmsRecordsController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    /** 契约 SmsSendStatusEnum 合法值；非法 status 过滤值 → 422（绝不 5xx）。 */
    private static final Set<String> STATUS_WHITELIST = Set.of("SENT", "FAILED", "DELIVERED");

    private final JdbcTemplate jdbc;

    public SmsRecordsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── [16] GET /sms-records ────────────────────────────────────────────────────
    // x-data-scope=range，无 x-permission。projectId/caseId/status/from/to 过滤 + 分页。
    @GetMapping("/sms-records")
    public Page<SmsSendRecordDto> listSmsRecords(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String caseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();

        // 过滤值非法 → 422（不放进 SQL）。id 类入参非数字也 422（避免 Long.parseLong 抛 5xx）。
        if (notBlank(projectId)) {
            where.append(" AND project_id = ?");
            args.add(parseIdOr422(projectId, "projectId"));
        }
        if (notBlank(caseId)) {
            where.append(" AND case_id = ?");
            args.add(parseIdOr422(caseId, "caseId"));
        }
        if (notBlank(status)) {
            if (!STATUS_WHITELIST.contains(status)) {
                throw new ApiException(BizError.VALIDATION_422, "status 非法: " + status);
            }
            where.append(" AND status = ?");
            args.add(status);
        }
        // from/to 为 date（含 from，不含 to+1d）。交给 PG ?::date 解析；非法日期 → 422。
        if (notBlank(from)) {
            assertDate(from, "from");
            where.append(" AND sent_at >= ?::date");
            args.add(from);
        }
        if (notBlank(to)) {
            assertDate(to, "to");
            where.append(" AND sent_at < (?::date + INTERVAL '1 day')");
            args.add(to);
        }

        // range：平台全量；物业/服务商 → 本组织（sms_record.org_id 为裁剪锚点 V5）。
        DataScope.Fragment scope = DataScope.ownOrg(s, "org_id");
        where.append(scope.sql());
        for (Object p : scope.params()) args.add(p);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM sms_record" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        // FAILED 行不剔除（失败不退条数 BR-M9-08），failure_reason 随行返回。
        List<SmsSendRecordDto> items = jdbc.query(
                "SELECT id, case_id, project_id, template, status, failure_reason, sent_at"
                        + " FROM sms_record" + where + " ORDER BY sent_at DESC LIMIT ? OFFSET ?",
                SMS_MAPPER, pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static final RowMapper<SmsSendRecordDto> SMS_MAPPER = (rs, i) -> new SmsSendRecordDto(
            String.valueOf(rs.getLong("id")),
            idOrNull(rs, "case_id"),
            idOrNull(rs, "project_id"),
            rs.getString("template"),
            rs.getString("status"),
            rs.getString("failure_reason"),
            ts(rs.getTimestamp("sent_at")));

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static Long parseIdOr422(String v, String field) {
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            throw new ApiException(BizError.VALIDATION_422, field + " 非法（须为数字 id）: " + v);
        }
    }

    /** 校验 yyyy-MM-dd（contract date 格式）；非法 → 422，避免 PG 解析报错 5xx。 */
    private static void assertDate(String v, String field) {
        try {
            java.time.LocalDate.parse(v);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, field + " 日期格式非法（yyyy-MM-dd）: " + v);
        }
    }

    private static String idOrNull(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : String.valueOf(v);
    }

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    // ===== SQL 列名 ↔ 契约字段核对（DDL V5 sms_record）=====
    // sms_record.id             -> id            (BIGINT IDENTITY；应用层 String.valueOf)
    // sms_record.case_id        -> caseId        (nullable)
    // sms_record.project_id     -> projectId     (nullable)
    // sms_record.template       -> template      (nullable)
    // sms_record.status         -> status        (SmsSendStatusEnum SENT/FAILED/DELIVERED；chk_sms_status)
    // sms_record.failure_reason -> failureReason (nullable；FAILED 行带原因 BR-M9-08)
    // sms_record.sent_at        -> sentAt        (TIMESTAMPTZ；ORDER BY sent_at DESC)
    // sms_record.org_id         <- range 裁剪锚点（DataScope.ownOrg，平台全量/非平台本 org）
    // from/to=date：sent_at >= from::date AND sent_at < to::date + 1d（含 from 当天，含 to 当天）
}
