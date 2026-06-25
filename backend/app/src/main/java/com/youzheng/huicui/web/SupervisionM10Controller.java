package com.youzheng.huicui.web;

import com.youzheng.huicui.audit.AuditService;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.SupervisionActionDto;
import org.slf4j.MDC;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M10 组织监管：成员督导动作端点（与 {@link MemberM1Controller} 同模块，类后缀隔离）。
 *
 * 端点（基路径 /v1 由 context-path 提供，方法注解写裸路径）：
 *   GET  /members/supervision               listSupervisionActions  | scope=own-org（平台全量；非平台 AND s.org_id=本组织）
 *   POST /members/{id}/supervision-actions  createSupervisionAction  | perm=member.manage | scope=own-org | 201/403/404/422
 *
 * 绝不 5xx：成员不存在/路径非法→404；越组织（含平台对跨组织成员）→403（BR-M1-04a 违规处置归各组织负责人）；
 *   action 空/非枚举→422。督导留痕写 audit_log（BR-M10-10，action=member.supervision）。
 */
@RestController
public class SupervisionM10Controller {

    private static final Set<String> ACTION_ENUM = Set.of("REMIND", "TALK", "TRAINING", "NOTE");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SupervisionM10Controller(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ── [7] GET /members/supervision ──────────────────────────────────────────
    // 无 x-permission（列表靠 scope 控可见性）。own-org：平台全量；非平台 AND s.org_id=本组织。
    @GetMapping("/members/supervision")
    public Page<SupervisionActionDto> listSupervisionActions(
            @RequestParam(required = false) String memberId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (memberId != null && !memberId.isBlank()) {
            where.append(" AND s.member_id = ?");
            args.add(parseLongOr422(memberId, "memberId"));
        }
        if (action != null && !action.isBlank()) {
            if (!ACTION_ENUM.contains(action)) {
                throw new ApiException(BizError.VALIDATION_422, "action 非法枚举: " + action);
            }
            where.append(" AND s.action = ?");
            args.add(action);
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND s.created_at >= ?");
            args.add(from);
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND s.created_at <= ?");
            args.add(to);
        }
        // own-org：平台全量；非平台限本组织。
        if (!s.isPlatform()) {
            where.append(" AND s.org_id = ?");
            args.add(orgIdLong(s));
        }

        String base = "FROM supervision_action s"
                + " JOIN account m ON s.member_id = m.id"
                + " JOIN account o ON s.operator_id = o.id"
                + where;

        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        String listSql = "SELECT s.id, s.member_id, m.name AS member_name, s.action, s.note,"
                + " s.operator_id, o.name AS operator_name, s.created_at "
                + base + " ORDER BY s.created_at DESC, s.id DESC LIMIT ? OFFSET ?";
        List<SupervisionActionDto> items = jdbc.query(listSql, (rs, i) -> new SupervisionActionDto(
                String.valueOf(rs.getLong("id")),
                String.valueOf(rs.getLong("member_id")),
                rs.getString("member_name"),
                rs.getString("action"),
                rs.getString("note"),
                String.valueOf(rs.getLong("operator_id")),
                rs.getString("operator_name"),
                ts(rs.getTimestamp("created_at"))), pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [8] POST /members/{id}/supervision-actions ────────────────────────────
    @PostMapping("/members/{id}/supervision-actions")
    @RequirePermission("member.manage")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public SupervisionActionDto createSupervisionAction(
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long memberId = parseId(id);

        String action = requireStr(body, "action");
        if (!ACTION_ENUM.contains(action)) {
            throw new ApiException(BizError.VALIDATION_422, "action 非法枚举: " + action);
        }
        String note = optStr(body, "note");

        // 目标成员不存在→404。
        long targetOrgId;
        try {
            targetOrgId = jdbc.queryForObject(
                    "SELECT org_id FROM account WHERE id = ?", Long.class, memberId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "成员不存在: " + memberId);
        }

        // own-org：非平台且 target.org_id≠subject.orgId→403；平台对跨组织成员→403（BR-M1-04a）。
        long subjOrg = orgIdLong(s);
        if (targetOrgId != subjOrg) {
            throw new ApiException(BizError.PERM_403, "只可督导本组织成员: " + memberId);
        }

        long operatorId = parseLongOr422(s.accountId(), "operatorId");
        String traceId = MDC.get("traceId");

        Long supId = jdbc.queryForObject(
                "INSERT INTO supervision_action(org_id, member_id, action, note, operator_id, trace_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                targetOrgId, memberId, action, note, operatorId, traceId);
        if (supId == null) {
            throw new ApiException(BizError.STATE_409, "督导记录创建失败");
        }

        // 审计（BR-M10-10）。
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("action", action);
        after.put("note", note);
        audit.write(s, "member.supervision", "account", String.valueOf(memberId), null, null, after);

        // JOIN 回填 memberName/operatorName + createdAt。
        return jdbc.queryForObject(
                "SELECT s.id, s.member_id, m.name AS member_name, s.action, s.note,"
                        + " s.operator_id, o.name AS operator_name, s.created_at"
                        + " FROM supervision_action s"
                        + " JOIN account m ON s.member_id = m.id"
                        + " JOIN account o ON s.operator_id = o.id"
                        + " WHERE s.id = ?",
                (rs, i) -> new SupervisionActionDto(
                        String.valueOf(rs.getLong("id")),
                        String.valueOf(rs.getLong("member_id")),
                        rs.getString("member_name"),
                        rs.getString("action"),
                        rs.getString("note"),
                        String.valueOf(rs.getLong("operator_id")),
                        rs.getString("operator_name"),
                        ts(rs.getTimestamp("created_at"))),
                supId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    private static String requireStr(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 " + key);
        }
        return String.valueOf(v).trim();
    }

    private static String optStr(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        return String.valueOf(v).trim();
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "成员不存在: " + id);
        }
    }

    private static long parseLongOr422(String v, String field) {
        try {
            return Long.parseLong(v.trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, field + " 非法: " + v);
        }
    }

    private static long orgIdLong(CurrentSubject s) {
        try {
            return Long.parseLong(s.orgId());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.PERM_403, "无组织上下文");
        }
    }
}
