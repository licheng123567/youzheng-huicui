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
import com.youzheng.huicui.security.RequirePermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * M2 资源 cases 读端点（横切层范式 + scaffold 共享助手）。
 * 类名带 M2 后缀（历史：曾与横切层验证的 demo 类并存，demo 已删）；只承载本资源读端点。
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
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String holderId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (projectId != null && !projectId.isBlank()) {
            // 与 batchId 同范式：非数字 projectId 不抛 NumberFormatException，置为不可命中条件。
            try { args.add(Long.valueOf(projectId.trim())); where.append(" AND c.project_id = ?"); }
            catch (NumberFormatException e) { where.append(" AND 1 = 0"); }
        }
        if (batchId != null && !batchId.isBlank()) {
            // 安全解析：非数字 batchId 不抛 NumberFormatException(避免 5xx/非契约错误)，置为不可命中条件。
            try { args.add(Long.valueOf(batchId.trim())); where.append(" AND c.batch_id = ?"); }
            catch (NumberFormatException e) { where.append(" AND 1 = 0"); }
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND c.status = ?");
            args.add(status);
        }
        // holderId：按持有催收员过滤（私海 BR-M3-04）。
        // App 催收员端的「我持有的」靠它 —— 不传时 range scope 对催收员返回的是**本服务商全部案件**
        // （含他人持有、待派单），并不是本人持有的那几件。
        // 它只是叠加一个过滤条件，**不放宽 scope**：scope 裁剪仍在 WHERE 末尾无条件追加，
        // 传别人的 holderId 也只能看到自己 scope 内本就可见的案件。
        if (holderId != null && !holderId.isBlank()) {
            // 与 projectId/batchId 同范式：非数字不抛 NumberFormatException（避免 5xx/非契约错误），置为不可命中条件。
            try { args.add(Long.valueOf(holderId.trim())); where.append(" AND c.holder_id = ?"); }
            catch (NumberFormatException e) { where.append(" AND 1 = 0"); }
        }
        // q 关键字：ILIKE 命中 手机号/户号(acct_no)/业主名(owner_name)。
        // 防侧信道(BR-M8-09)：结案脱敏行(非平台/非物业看 SETTLED/WITHDRAWN/BAD_DEBT/VOIDED)不得被明文 q 命中，
        //   故对会被脱敏的主体在 q 子句内额外排除结案态，使脱敏案件无法被业主名/手机号探测。
        appendKeyword(s, where, args, q);
        // scope 裁剪始终在 WHERE 末尾追加(range 范式·不可被其他条件绕过)。
        appendRangeScope(s, where, args);

        // 列表 SQL：JOIN project 取 org_id（物业 scope）、JOIN batch 取 provider_id（服务商 scope）。
        // 注意表名 "case" 须双引号。
        String base = "FROM \"case\" c"
                + " JOIN project p ON p.id = c.project_id"
                + " JOIN batch b ON b.id = c.batch_id"
                + " LEFT JOIN account ha ON ha.id = c.holder_id"
                + " LEFT JOIN (SELECT DISTINCT ON (case_id) case_id, phone FROM contact"
                + "            ORDER BY case_id, is_primary DESC, id) ct ON ct.case_id = c.id"
                + where;

        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        String listSql = "SELECT c.*, ha.name AS holder_name, ct.phone AS contact_phone " + base + " ORDER BY c.id DESC LIMIT ? OFFSET ?";
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
                + " LEFT JOIN account ha ON ha.id = c.holder_id"
                + " LEFT JOIN (SELECT DISTINCT ON (case_id) case_id, phone FROM contact"
                + "            ORDER BY case_id, is_primary DESC, id) ct ON ct.case_id = c.id"
                + " WHERE c.id = ?";
        List<CaseDto> found = jdbc.query("SELECT c.*, ha.name AS holder_name, ct.phone AS contact_phone " + base, caseRowMapper(s), caseId);
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

        // projectRef：项目档案/收费标准/收款信息/减免规则 + 批次信息（高保真§项目资料 Tab）。
        // JOIN batch 获取批次号与收佣比例。
        CaseProjectRefDto projectRef = jdbc.query(
                "SELECT p.contract_type, p.contract_name, p.service_period,"
                        + " p.fee_cycle, p.fee_rows, p.penalty,"
                        + " p.corp_account, p.wx_qr_url, p.pay_info, p.reduce_policy,"
                        + " b.no AS batch_no, b.comm_in_rate, b.pay_out_rate, b.comm_in_confirmed"
                        + " FROM project p"
                        + " JOIN batch b ON b.id = ?::bigint"
                        + " WHERE p.id = ?",
                rs -> {
                    if (!rs.next()) return new CaseProjectRefDto(null, null, null, null, null, null, null, null, null, null, List.of(), null, null, null, null);
                    return new CaseProjectRefDto(
                            rs.getString("contract_type"),
                            rs.getString("contract_name"),
                            rs.getString("service_period"),
                            rs.getString("fee_cycle"),
                            summarizeFeeRows(rs.getString("fee_rows")),
                            rs.getString("penalty"),
                            rs.getString("corp_account"),
                            rs.getString("wx_qr_url"),
                            rs.getString("pay_info"),
                            rs.getString("reduce_policy"),
                            List.of(),
                            rs.getString("batch_no"),
                            formatRate(rs.getBigDecimal("comm_in_rate")),
                            formatRate(rs.getBigDecimal("pay_out_rate")),
                            (Boolean) rs.getObject("comm_in_confirmed"));
                },
                Long.parseLong(caseDto.batchId()),
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
        // 资金双线隔离 BR-M9-11（字段级脱敏，非仅前端隐藏）：
        //   收佣线(commInRate·物业↔平台) 不下发服务商(PROVIDER)；付佣线(payOutRate·平台↔服务商) 不下发物业(PROPERTY)。
        //   平台(PLATFORM)双线全见。commInConfirmed 仅对能见收佣线的一侧有意义，物业侧一并下发（服务商侧连同 commInRate 省略）。
        boolean isProvider = "PROVIDER".equals(s.orgType());
        boolean isProperty = "PROPERTY".equals(s.orgType());
        String commInRate = isProvider ? null : projectRef.commInRate();
        String payOutRate = isProperty ? null : projectRef.payOutRate();
        Boolean commInConfirmed = isProvider ? null : projectRef.commInConfirmed();
        projectRef = new CaseProjectRefDto(
                projectRef.contractType(), projectRef.contractName(), projectRef.servicePeriod(),
                projectRef.feeCycle(), projectRef.feeStd(), projectRef.feeItems(),
                projectRef.corpAccount(), projectRef.wxQrUrl(), projectRef.payInfo(),
                projectRef.reducePolicy(), tiers,
                projectRef.batchNo(), commInRate, payOutRate, commInConfirmed);

        // playbook：M2 读阶段返回 null（案件页经 /projects/{id}/playbook 容错取底稿）。
        Object playbook = null;
        // preCallStrategy：通话前策略(BR-M5-04)——按本案真实事实(欠费/承诺履约/通话/工单/减免)
        //   + 话术库效果排序(Wilson BR-M5-12a)组装；生成失败绝不拖垮详情读取(返 null)。
        Object preCallStrategy = buildPreCallStrategy(caseId, caseDto);

        // availableActions：M2 先按 permissions × status 映射基础动作，驱动前端操作区显隐。
        // TODO：与状态机/case-holder 精细化对齐（写端点接入时收敛）。
        List<String> availableActions = computeAvailableActions(s, caseDto.status());

        // markCodes：从 settings 的 MARK_CODES 域读最新版 mark_codes，仅取 enabled=true 项放入详情，
        //   使 case-actor(CO/VL) 绕开 platform-scoped /settings 即取 CFG-MARK-CODES(M-01/BR-M4-12)。
        List<Object> markCodes = loadEnabledMarkCodes();

        return new CaseDetailDto(caseDto, contacts, timeline, projectRef,
                playbook, preCallStrategy, availableActions, markCodes);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** x-data-scope=range 追加到 WHERE（含前导 AND）。平台不限；物业按 p.org_id；服务商按案件级唯一权威 c.provider_id。 */
    // ── PATCH /cases/{id} patchCase（case.follow, range）：补充诉讼要素/联系方式/说明,不改案件状态(BR-M2-14/M4-18a）──
    @PatchMapping("/cases/{id}")
    @RequirePermission("case.follow")
    @Transactional
    @SuppressWarnings("unchecked")
    public CaseDto patchCase(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId;
        try { caseId = Long.parseLong(id); } catch (NumberFormatException e) { throw new ApiException(BizError.NOT_FOUND_404, "案件不存在"); }
        // 存在性(404)优先于可见性(403)
        List<CaseDto> found = jdbc.query(
                "SELECT c.* FROM \"case\" c JOIN project p ON p.id=c.project_id JOIN batch b ON b.id=c.batch_id WHERE c.id = ?",
                caseRowMapper(s), caseId);
        if (found.isEmpty()) throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + id);
        if (!visibleByScope(s, caseId)) throw new ApiException(BizError.PERM_403, "无权操作该案件");

        if (body != null) {
            Object lit = body.get("litigationFields");
            if (lit != null) {
                String litJson;
                try { litJson = json.writeValueAsString(lit); } catch (Exception e) { throw new ApiException(BizError.VALIDATION_422, "litigationFields 非法"); }
                jdbc.update("UPDATE \"case\" SET litigation_fields = ?::jsonb, updated_at = now() WHERE id = ?", litJson, caseId);
            }
            Object contacts = body.get("contacts");
            if (contacts instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> c)) continue;
                    Object phone = c.get("phone");
                    if (phone == null || String.valueOf(phone).isBlank()) throw new ApiException(BizError.VALIDATION_422, "联系方式 phone 必填");
                    jdbc.update("INSERT INTO contact(case_id, phone, label, is_primary, invalid) VALUES (?, ?, ?, ?, ?)",
                            caseId, String.valueOf(phone), c.get("label") == null ? null : String.valueOf(c.get("label")),
                            Boolean.TRUE.equals(c.get("isPrimary")), Boolean.TRUE.equals(c.get("invalid")));
                }
            }
            Object note = body.get("note");
            if (note != null && !String.valueOf(note).isBlank()) {
                // 不改状态：补充说明落 activity NOTE 留痕。
                Long actorId = null;
                try { actorId = Long.parseLong(s.accountId()); } catch (Exception ignore) {}
                jdbc.update("INSERT INTO activity(case_id, type, actor_id, content) VALUES (?, 'NOTE', ?, ?)",
                        caseId, actorId, String.valueOf(note));
            }
        }
        // 重取(契约 PATCH 返 Case)
        return jdbc.query(
                "SELECT c.* FROM \"case\" c JOIN project p ON p.id=c.project_id JOIN batch b ON b.id=c.batch_id WHERE c.id = ?",
                caseRowMapper(s), caseId).get(0);
    }

    /**
     * q 关键字过滤：ILIKE 命中 contact.phone / c.acct_no / c.owner_name。
     * 防侧信道(BR-M8-09)：对会触发脱敏的主体(非平台/非物业)，q 命中前先排除结案脱敏态，
     *   使被脱敏案件无法被业主名/手机号关键字探测出来。空白 q 不追加任何条件。
     */
    private void appendKeyword(CurrentSubject s, StringBuilder where, List<Object> args, String q) {
        if (q == null || q.isBlank()) return;
        // 会被脱敏的主体：非平台 且 非物业(= PROVIDER 视角，与 RoleResponse.caseRedacted 同口径)。
        boolean redacting = !s.isPlatform() && "PROVIDER".equals(s.orgType());
        if (redacting) {
            // 结案脱敏行排除在 q 命中范围外(明文姓名/手机号不得命中脱敏案件)。
            where.append(" AND c.status NOT IN ('SETTLED','WITHDRAWN','BAD_DEBT','VOIDED')");
        }
        String like = "%" + q.trim() + "%";
        where.append(" AND (c.acct_no ILIKE ? OR c.owner_name ILIKE ?"
                + " OR EXISTS (SELECT 1 FROM contact ct WHERE ct.case_id = c.id AND ct.phone ILIKE ?))");
        args.add(like);
        args.add(like);
        args.add(like);
    }

    private void appendRangeScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        // 统一收口：SA 全量 / SE 三维 data_range / PROVIDER c.provider_id / PL p.org_id / PC 行级协调集（issue#3）。
        com.youzheng.huicui.common.DataScope.appendRange(
                s, where, args, "c.provider_id", "p.org_id", "p.area", "c.project_id", "c.batch_id");
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
                    longOrNull(rs, "penalty_cents"),
                    longOrNull(rs, "reduce_after_cents"),
                    parseStringArray(rs.getString("arrearags_periods")),
                    parseJsonObject(rs.getString("litigation_fields")),
                    status,
                    rs.getString("legal_stage"),
                    idOrNull(rs, "holder_id"),
                    rs.getString("holder_name"),
                    redacted ? null : rs.getString("contact_phone"),
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
                // 修：原集漏发以下核心作业动作，前端 canAct 以 availableActions 为 SSOT
                // → 进行中案件即使有权限，登记还款/发起存证/申请法务/结案/退回按钮被静默隐藏。
                addIf(actions, s, "case.repay.mark", "repay");
                addIf(actions, s, "evidence.create", "evidence");
                addIf(actions, s, "legal.create", "legal");
                addIf(actions, s, "case.close", "close");
                addIf(actions, s, "case.return", "return");
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

    /** NUMERIC(6,4) 分数(0.12) → 展示串 "12%"。null 安全。（此前漏 ×100 会显 "0.12%"，修正。） */
    private static String formatRate(java.math.BigDecimal rate) {
        if (rate == null) return null;
        return rate.multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
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

    /**
     * 通话前策略（契约 PreCallStrategy · BR-M5-04）：AI 动态「沟通策略与注意事项」。
     * 全部字段取自真实数据组装——案件事实（欠费/承诺履约/历史通话/待处理工单/减免）+
     * 话术库现行条目按效果排序推荐（Wilson 置信下界 BR-M5-12a），不编造事实。
     * points[0] 固定为背景摘要（前端渲染为 bgbox），其余为警示/提示条（riskbar）；
     * objections 为 StrategyCard[]（前端 aicard，可采纳联动动作 actionRef）。
     * 已结案/异常一律返 null——策略生成失败绝不拖垮详情读取。
     */
    private Object buildPreCallStrategy(long caseId, CaseDto c) {
        if (c.closedAt() != null) return null;   // 终态案件不再生成通话前策略
        try {
            long due = c.dueCents() == null ? 0L : c.dueCents();
            int months = c.arrearagePeriods() == null ? 0 : c.arrearagePeriods().size();

            Integer callCnt = jdbc.queryForObject(
                    "SELECT count(*) FROM call_recording WHERE case_id = ?", Integer.class, caseId);
            Timestamp lastCall = jdbc.query(
                    "SELECT max(recorded_at) AS t FROM call_recording WHERE case_id = ?",
                    rs -> rs.next() ? rs.getTimestamp("t") : null, caseId);
            Integer brokenPromises = jdbc.queryForObject(
                    "SELECT count(*) FROM promise WHERE case_id = ? AND state = 'BROKEN'", Integer.class, caseId);
            // 最近一条待兑现承诺（date/amount），无则 null
            Map<String, Object> pendingPromise = jdbc.query(
                    "SELECT \"date\" AS pdate, amount_cents FROM promise WHERE case_id = ? AND state = 'PENDING'"
                            + " ORDER BY created_at DESC, id DESC LIMIT 1",
                    rs -> {
                        if (!rs.next()) return null;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("date", String.valueOf(rs.getDate("pdate")));
                        m.put("amountCents", rs.getLong("amount_cents"));
                        return m;
                    }, caseId);
            Integer pendingTickets = jdbc.queryForObject(
                    "SELECT count(*) FROM ticket WHERE case_id = ? AND status = 'PENDING'", Integer.class, caseId);

            List<String> points = new ArrayList<>();
            StringBuilder bg = new StringBuilder("背景摘要：欠费 " + yuanText(due) + "（" + months + " 个月）· 历史通话 "
                    + (callCnt == null ? 0 : callCnt) + " 次");
            if (lastCall != null) {
                bg.append(" · 上次接触 ").append(new java.text.SimpleDateFormat("MM-dd").format(lastCall));
            }
            points.add(bg.toString());
            if (brokenPromises != null && brokenPromises > 0) {
                points.add("该户有 " + brokenPromises + " 次承诺爽约记录，先共情再谈缴费；本次需锁定书面承诺并当场发催费单");
            }
            if (pendingPromise != null) {
                points.add("已有待兑现承诺：" + pendingPromise.get("date") + " "
                        + yuanText((Long) pendingPromise.get("amountCents")) + "，本次通话优先确认兑现安排");
            }
            if (pendingTickets != null && pendingTickets > 0) {
                points.add(pendingTickets + " 个工单待协调员处理，可先同步工单进展化解异议、再谈缴费");
            }
            if (c.reduceAfterCents() != null && c.reduceAfterCents() < due) {
                points.add("减免后应收 " + yuanText(c.reduceAfterCents()) + "（已减 " + yuanText(due - c.reduceAfterCents())
                        + "），可用减免额度引导一次性结清");
            }

            // 话术库推荐（飞轮环2）：先由案件画像确定性派生 cohort/scene（ScriptMatch），
            // 再按 cohort/scene 命中 + Wilson 降序取前 3；命中空回退全库 Top3（绝不返空卡）。
            // 语义匹配（embedding 向量检索）为后置接缝：script_lib.embedding 仍死列。
            //   TODO(RAG): ... ORDER BY embedding <=> :caseVec LIMIT k
            Integer contactCnt = jdbc.queryForObject(
                    "SELECT count(*) FROM contact WHERE case_id = ?", Integer.class, caseId);
            boolean hasContact = contactCnt != null && contactCnt > 0;
            String lastMark = jdbc.query(
                    "SELECT ar.result_mark FROM ai_review ar JOIN call_recording cr ON cr.id = ar.call_id"
                            + " WHERE cr.case_id = ? ORDER BY ar.updated_at DESC NULLS LAST, ar.id DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : null, caseId);
            boolean lastRefused = "REFUSED".equals(lastMark);
            int bp = brokenPromises == null ? 0 : brokenPromises;
            String coCohort = ScriptMatch.cohort(months, due, callCnt == null ? 0 : callCnt, hasContact, lastRefused);
            String coScene = ScriptMatch.scene(callCnt == null ? 0 : callCnt, hasContact, bp, pendingPromise != null);
            List<Object> cards = jdbc.query(
                    "SELECT id, scene, intent, cohort, promise_rate, repay_rate, wilson, variant->>'text' AS vtext"
                            + " FROM script_lib WHERE status IN ('EFFECTIVE','CANDIDATE')"
                            + " ORDER BY (CASE WHEN cohort = ? OR scene = ? THEN 0 ELSE 1 END),"
                            + "          wilson DESC NULLS LAST, uses DESC, id LIMIT 3",
                    (rs, i) -> {
                        Map<String, Object> card = new LinkedHashMap<>();
                        card.put("id", "script-" + rs.getLong("id"));
                        card.put("type", "SCRIPT");
                        String scene = rs.getString("scene");
                        String intent = rs.getString("intent");
                        card.put("title", scene + (intent == null || intent.isBlank() ? "" : " · " + intent));
                        String vtext = rs.getString("vtext");
                        java.math.BigDecimal pr = rs.getBigDecimal("promise_rate");
                        java.math.BigDecimal rr = rs.getBigDecimal("repay_rate");
                        card.put("body", (vtext != null && !vtext.isBlank()) ? vtext
                                : "适用「" + nzText(rs.getString("cohort")) + "」"
                                        + (pr == null ? "" : " · 承诺率 " + pctText(pr))
                                        + (rr == null ? "" : " · 回款率 " + pctText(rr))
                                        + "（话术库按实际效果排序推荐）");
                        java.math.BigDecimal w = rs.getBigDecimal("wilson");
                        card.put("confidence", w == null ? null
                                : w.doubleValue() >= 0.3 ? "HIGH" : w.doubleValue() >= 0.15 ? "MED" : "LOW");
                        card.put("trigger", rs.getString("cohort"));
                        String it = intent == null ? "" : intent;
                        card.put("actionRef", it.contains("承诺") ? "PROMISE"
                                : (it.contains("缴费") || it.contains("催费")) ? "PAYLINK"
                                : (it.contains("联系") || it.contains("信任")) ? "FOLLOWUP" : "NONE");
                        return (Object) card;
                    }, coCohort, coScene);

            Map<String, Object> strategy = new LinkedHashMap<>();
            strategy.put("points", points);
            strategy.put("objections", cards);
            return strategy;
        } catch (RuntimeException e) {
            return null;   // 策略生成任何异常均降级为无策略，不影响详情主体
        }
    }

    private static String yuanText(long cents) {
        return "¥" + String.format("%,.0f", cents / 100.0);
    }

    private static String nzText(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String pctText(java.math.BigDecimal frac) {
        return String.format("%.0f%%", frac.doubleValue() * 100);
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

    /**
     * 读 settings 的 MARK_CODES 域最新版 mark_codes，仅返回 enabled=true 的项(结构 {code,label,enabled,connected,effectiveFollowUp})。
     * 无配置/解析失败/无启用项 → 空列表(前端可回退兜底)。绕开 /settings platform 限制，仅暴露启用标记码给案作业方(M-01)。
     */
    private List<Object> loadEnabledMarkCodes() {
        String mc = jdbc.query(
                "SELECT mark_codes FROM settings WHERE domain = 'MARK_CODES'"
                        + " ORDER BY version DESC LIMIT 1",
                rs -> rs.next() ? rs.getString("mark_codes") : null);
        if (mc == null || mc.isBlank()) return List.of();
        List<Map<String, Object>> rows;
        try {
            rows = json.readValue(mc, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
        List<Object> enabled = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (r != null && Boolean.TRUE.equals(r.get("enabled"))) enabled.add(r);
        }
        return enabled;
    }
}
