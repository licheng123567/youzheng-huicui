package com.youzheng.huicui.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.common.RoleResponse;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.CaseActivityDto;
import com.youzheng.huicui.web.dto.CaseContactDto;
import com.youzheng.huicui.web.dto.CaseDetailDto;
import com.youzheng.huicui.web.dto.CaseDto;
import com.youzheng.huicui.web.dto.CaseProjectRefDto;
import com.youzheng.huicui.web.dto.CaseReduceTierDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
 * M2 资源 cases 读端点（横切层范式 + scaffold 共享助手）。
 * 类名带 M2 后缀，避免与既有 ProjectsController 风格的 demo/占位类冲突；只承载本资源读端点。
 *
 * 端点（基路径 /v1 由 server.servlet.context-path 提供，方法注解写裸路径）：
 *   GET /cases       listCases  —— 案件列表，x-data-scope=range，CasePage。
 *   GET /cases/{id}  getCase    —— 案件详情聚合端点，x-data-scope=range，CaseDetail（403/404）。
 *
 * x-data-scope=range（M2 读阶段三分支组织级裁剪）：
 *   平台(PLATFORM)   → 全量；
 *   物业(PROPERTY)   → c.project_id 所属项目 p.org_id = 本组织（own-org on project）；
 *   服务商(PROVIDER) → c.batch_id 所属批次 b.provider_id = 本组织。
 *   case-holder/case-actor 细粒度（CO 仅本案持有 / 关联 PL/PC / SA 代）留待写端点接入，
 *   读阶段以组织级裁剪为底线（见 BR-M4-01/M1-15，TODO）。
 *
 * 脱敏 BR-M8-09：非平台且非物业主体，对结案态(SETTLED/WITHDRAWN/BAD_DEBT/VOIDED)案件
 *   置 redacted=true 并脱敏 ownerName / contacts.phone（统一走 RoleResponse.caseRedacted）。
 *
 * 金额：*_cents 列原样以「分」(Long) 返回，对齐契约 Money=integer 分，不转元。
 */
@RestController
public class CasesM2Controller {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CasesM2Controller(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final String REDACTED_NAME = "***";
    private static final String REDACTED_PHONE = "***";

    // ── [1] GET /cases ───────────────────────────────────────────────────────
    // 无 x-permission（列表靠 scope 控可见性）。
    @GetMapping("/cases")
    public Page<CaseDto> listCases(
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (batchId != null && !batchId.isBlank()) {
            where.append(" AND c.batch_id = ?");
            args.add(Long.valueOf(batchId));
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND c.status = ?");
            args.add(status);
        }
        appendRangeScope(s, where, args);

        // 列表 SQL：JOIN project 取 org_id（物业 scope）、JOIN batch 取 provider_id（服务商 scope）。
        // 注意表名 "case" 须双引号。
        String base = "FROM \"case\" c"
                + " JOIN project p ON p.id = c.project_id"
                + " JOIN batch b ON b.id = c.batch_id"
                + where;

        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        String listSql = "SELECT c.* " + base + " ORDER BY c.id DESC LIMIT ? OFFSET ?";
        List<CaseDto> items = jdbc.query(listSql, caseRowMapper(s), pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [2] GET /cases/{id} （聚合端点） ──────────────────────────────────────
    // 无 x-permission（聚合详情靠 scope 控可见性）：越范围→403，不存在→404。
    @GetMapping("/cases/{id}")
    public CaseDetailDto getCase(@PathVariable String id) {
        CurrentSubject s = SubjectContext.get();
        long caseId = Long.parseLong(id);

        // case 主体：先无 scope 取出判存在性，再按 scope 校验可见性，区分 404/403。
        String base = "FROM \"case\" c"
                + " JOIN project p ON p.id = c.project_id"
                + " JOIN batch b ON b.id = c.batch_id"
                + " WHERE c.id = ?";
        List<CaseDto> found = jdbc.query("SELECT c.* " + base, caseRowMapper(s), caseId);
        if (found.isEmpty()) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + id);
        }
        if (!visibleByScope(s, caseId)) {
            throw new ApiException(BizError.PERM_403, "无权查看该案件");
        }
        CaseDto caseDto = found.get(0);
        boolean redacted = caseDto.redacted();

        // contacts
        List<CaseContactDto> contacts = jdbc.query(
                "SELECT * FROM contact WHERE case_id = ? ORDER BY is_primary DESC, id",
                (rs, i) -> new CaseContactDto(
                        String.valueOf(rs.getLong("id")),
                        String.valueOf(rs.getLong("case_id")),
                        redacted ? REDACTED_PHONE : rs.getString("phone"),
                        rs.getString("label"),
                        rs.getBoolean("is_primary"),
                        rs.getBoolean("invalid")),
                caseId);

        // timeline（LEFT JOIN account 取展示名）
        List<CaseActivityDto> timeline = jdbc.query(
                "SELECT a.*, acc.name AS actor_name FROM activity a"
                        + " LEFT JOIN account acc ON acc.id = a.actor_id"
                        + " WHERE a.case_id = ? ORDER BY a.created_at DESC, a.id DESC",
                (rs, i) -> new CaseActivityDto(
                        String.valueOf(rs.getLong("id")),
                        String.valueOf(rs.getLong("case_id")),
                        rs.getString("type"),
                        rs.getString("actor_name"),
                        idOrNull(rs, "actor_id"),
                        rs.getString("content"),
                        rs.getString("ref_type"),
                        idOrNull(rs, "ref_id"),
                        ts(rs.getTimestamp("created_at"))),
                caseId);

        // projectRef：项目合同/收费/缴费信息 + 项目级减免阶梯（batch_id IS NULL）。
        CaseProjectRefDto projectRef = jdbc.query(
                "SELECT contract_type, fee_rows, pay_info FROM project WHERE id = ?",
                rs -> {
                    if (!rs.next()) return new CaseProjectRefDto(null, null, null, List.of());
                    String contractType = rs.getString("contract_type");
                    String feeStd = summarizeFeeRows(rs.getString("fee_rows"));
                    String payInfo = rs.getString("pay_info");
                    return new CaseProjectRefDto(contractType, feeStd, payInfo, List.of());
                },
                Long.parseLong(caseDto.projectId()));

        List<CaseReduceTierDto> tiers = jdbc.query(
                "SELECT discount, cap_cents, waive_penalty, decide FROM reduce_tier"
                        + " WHERE project_id = ? AND batch_id IS NULL ORDER BY id",
                (rs, i) -> new CaseReduceTierDto(
                        rs.getString("discount"),
                        longOrNull(rs, "cap_cents"),
                        rs.getBoolean("waive_penalty"),
                        rs.getString("decide")),
                Long.parseLong(caseDto.projectId()));
        projectRef = new CaseProjectRefDto(
                projectRef.contractType(), projectRef.feeStd(), projectRef.payInfo(), tiers);

        // playbook / preCallStrategy：M2 读阶段返回 null（M5 接入作战手册/AI）。TODO(M5)。
        Object playbook = null;
        Object preCallStrategy = null;

        // availableActions：M2 先按 permissions × status 映射基础动作，驱动前端操作区显隐。
        // TODO：与状态机/case-holder 精细化对齐（写端点接入时收敛）。
        List<String> availableActions = computeAvailableActions(s, caseDto.status());

        return new CaseDetailDto(caseDto, contacts, timeline, projectRef,
                playbook, preCallStrategy, availableActions);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** x-data-scope=range 追加到 WHERE（含前导 AND）。平台不限；物业按 p.org_id；服务商按 b.provider_id。 */
    private void appendRangeScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        if (s.isPlatform()) return;                       // 平台全量
        if ("PROVIDER".equals(s.orgType())) {
            where.append(" AND b.provider_id = ?");
            args.add(Long.valueOf(s.orgId()));
        } else {                                          // PROPERTY（及兜底非平台/非服务商）按项目归属
            where.append(" AND p.org_id = ?");
            args.add(Long.valueOf(s.orgId()));
        }
    }

    /** 详情可见性：按 range scope 判断该案件对当前主体是否可见。 */
    private boolean visibleByScope(CurrentSubject s, long caseId) {
        StringBuilder where = new StringBuilder(" WHERE c.id = ?");
        List<Object> args = new ArrayList<>();
        args.add(caseId);
        appendRangeScope(s, where, args);
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM \"case\" c"
                        + " JOIN project p ON p.id = c.project_id"
                        + " JOIN batch b ON b.id = c.batch_id" + where,
                Long.class, args.toArray());
        return n != null && n > 0;
    }

    /** Case RowMapper：列名→契约字段映射 + BR-M8-09 脱敏施加。 */
    private RowMapper<CaseDto> caseRowMapper(CurrentSubject s) {
        return (rs, i) -> {
            String status = rs.getString("status");
            boolean redacted = RoleResponse.caseRedacted(s, status);
            String ownerName = redacted ? REDACTED_NAME : rs.getString("owner_name");
            return new CaseDto(
                    String.valueOf(rs.getLong("id")),
                    rs.getString("acct_no"),
                    String.valueOf(rs.getLong("batch_id")),
                    String.valueOf(rs.getLong("project_id")),
                    rs.getString("project_name"),
                    ownerName,
                    rs.getString("room"),
                    longOrNull(rs, "due_cents"),
                    longOrNull(rs, "reduce_after_cents"),
                    parseStringArray(rs.getString("arrearags_periods")),
                    parseJsonObject(rs.getString("litigation_fields")),
                    status,
                    rs.getString("legal_stage"),
                    idOrNull(rs, "holder_id"),
                    rs.getString("pool"),
                    rs.getString("source"),
                    ts(rs.getTimestamp("t2_deadline")),
                    ts(rs.getTimestamp("t_collector_deadline")),
                    rs.getString("closed_kind"),
                    ts(rs.getTimestamp("closed_at")),
                    redacted);
        };
    }

    /** permissions × status → 可用操作点（M2 基础映射，TODO 与状态机对齐）。 */
    private List<String> computeAvailableActions(CurrentSubject s, String status) {
        List<String> actions = new ArrayList<>();
        switch (status == null ? "" : status) {
            case "PENDING_DISPATCH" -> {
                addIf(actions, s, "case.accept", "accept");
                addIf(actions, s, "case.accept", "reject");
                addIf(actions, s, "case.dispatch", "dispatch");
            }
            case "PROVIDER_SEA" -> {
                addIf(actions, s, "case.claim", "claim");
                addIf(actions, s, "case.assign", "assign");
            }
            case "IN_PROGRESS", "PROMISED" -> {
                addIf(actions, s, "case.follow", "follow");
                addIf(actions, s, "case.promise", "promise");
                addIf(actions, s, "case.paylink", "payLink");
                addIf(actions, s, "case.call", "call");
                addIf(actions, s, "case.release", "release");
                addIf(actions, s, "case.ticket", "ticket");
            }
            default -> { /* 结案态：无在线操作 */ }
        }
        return actions;
    }

    private static void addIf(List<String> out, CurrentSubject s, String perm, String action) {
        if (s.has(perm)) out.add(action);
    }

    // ── 低级转换工具 ──────────────────────────────────────────────────────────

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static String idOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : String.valueOf(v);
    }

    /** jsonb 文本 → List<String>（arrearags_periods）。空/异常返回空列表。 */
    private List<String> parseStringArray(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            return json.readValue(jsonText, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** jsonb 文本 → Object（litigation_fields；null 列保持 null）。 */
    private Object parseJsonObject(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return null;
        try {
            return json.readValue(jsonText, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** fee_rows jsonb [{biz,std}] → 展示串 "物业费:1.5元/㎡·月; 停车费:..."。契约 projectRef.feeStd 为 string。 */
    private String summarizeFeeRows(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return null;
        try {
            List<java.util.Map<String, Object>> rows =
                    json.readValue(jsonText, new TypeReference<List<java.util.Map<String, Object>>>() {});
            List<String> parts = new ArrayList<>();
            for (java.util.Map<String, Object> r : rows) {
                Object biz = r.get("biz");
                Object std = r.get("std");
                if (biz == null && std == null) continue;
                parts.add((biz == null ? "" : biz) + ":" + (std == null ? "" : std));
            }
            return String.join("; ", parts);
        } catch (Exception e) {
            return null;
        }
    }
}
