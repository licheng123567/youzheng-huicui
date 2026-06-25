package com.youzheng.huicui.web;

import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.dispatch.RiskQcService;
import com.youzheng.huicui.dispatch.RiskQcService.RiskSnapshot;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.QcDisposeTaskDto;
import com.youzheng.huicui.web.dto.RiskRecordDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M5「qc」组质检/风控端点（横切层范式 + scaffold；读照 CasesM2Controller，写+审计照 CaseStateService→RiskQcService）。
 * 类名带 M5 后缀，物理隔离，不碰 M1-M4/M9 controller 与共享件。
 *
 * 端点（基路径 /v1 由 server.servlet.context-path 提供，注解写裸路径）：
 *   GET  /risks                listRisks       | scope=range            | 200 RiskPage（无 x-permission，靠 scope 控可见）
 *   POST /risks/{id}/dispose   disposeRisk      | perm=qc.dispose  scope=own-org | 200（403 非本组织）
 *   POST /risks/{id}/escalate  escalateRisk     | perm=qc.escalate scope=own-org | 200（对非本组织风险只读上报）
 *   POST /risks/{id}/review    reviewRisk       | perm=qc.review   scope=platform| 200（403 非平台 / 409 已撤销 / 422）
 *   GET  /dispose-tasks        listDisposeTasks | scope=platform         | 200 DisposeTaskPage（403 非平台）
 *
 * 优雅降级（绝不 5xx）：风险/资源不存在或越 scope→404；非本组织 dispose / 非平台 review·dispose-tasks→403 PERM_403；
 *   reviewRisk 对已 FALSE_POSITIVE 风险再判→409 STATE_409；缺/非枚举入参→422 VALIDATION_422。
 * 幂等：写端点 Idempotency-Key 由 IdempotencyInterceptor 在 header 层兜底（同键重放→409），控制器无需声明参数；
 *   reviewRisk 建 dispose_task 另以「先查后插」表级幂等兜底（V1 无 uq_dispose_task_risk）。
 * 金额：本模块不涉 *_cents。时间 ISO-8601。
 */
@RestController
public class QcM5Controller {

    private final JdbcTemplate jdbc;
    private final RiskQcService riskQc;

    public QcM5Controller(JdbcTemplate jdbc, RiskQcService riskQc) {
        this.jdbc = jdbc;
        this.riskQc = riskQc;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private static final Set<String> DISPOSE_ACTIONS = Set.of("mark", "to_qc", "notify");
    private static final Set<String> REVIEW_VERDICTS = Set.of("CONFIRMED", "FALSE_POSITIVE", "ESCALATED");
    private static final Set<String> RISK_LEVELS = Set.of("HIGH", "MID", "LOW");

    private static final String DEFAULT_TASK_TYPE = "整改培训";

    // ── [1] GET /risks  listRisks ────────────────────────────────────────────────
    // scope=range（三方隔离 BR-M5-07）。无 x-permission：列表靠 scope 控可见，仅裁剪不抛 403。
    @GetMapping("/risks")
    public Page<RiskRecordDto> listRisks(@RequestParam(required = false) String level,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (level != null && !level.isBlank()) {
            if (!RISK_LEVELS.contains(level)) {
                throw new ApiException(BizError.VALIDATION_422, "level 非法（HIGH/MID/LOW）");
            }
            where.append(" AND r.level = ?");
            args.add(level);
        }
        appendRangeScope(s, where, args);

        // JOIN case→project(物业 scope) / batch(服务商 scope)，JOIN account 取违规人展示名(collector)。
        String base = "FROM risk_record r"
                + " JOIN \"case\" c ON c.id = r.case_id"
                + " JOIN project p ON p.id = c.project_id"
                + " JOIN batch b ON b.id = c.batch_id"
                + " LEFT JOIN account acc ON acc.id = r.collector_id"
                + where;

        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<RiskRecordDto> items = jdbc.query(
                "SELECT r.id, r.case_id, acc.name AS collector_name, r.type, r.level,"
                        + " r.segment_ts, r.reviewed " + base + " ORDER BY r.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new RiskRecordDto(
                        String.valueOf(rs.getLong("id")),
                        String.valueOf(rs.getLong("case_id")),
                        rs.getString("collector_name"),
                        rs.getString("type"),
                        rs.getString("level"),
                        rs.getString("segment_ts"),
                        rs.getString("reviewed")),     // NULL→null（契约 reviewed oneOf null）
                pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [2] POST /risks/{id}/dispose  disposeRisk（BR-M5-07a 谁的员工谁处理）──────
    // perm=qc.dispose scope=own-org。仅违规人所属组织负责人可实质处置；非本组织→403 PERM_403。
    @PostMapping("/risks/{id}/dispose")
    @RequirePermission("qc.dispose")
    @Transactional
    public Map<String, Object> disposeRisk(@PathVariable("id") String id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long riskId = parseId(id);

        String action = requireEnum(body, "action", DISPOSE_ACTIONS,
                "action 非法（mark/to_qc/notify）");          // 缺/非枚举→422
        String note = optStr(body, "note");

        // 先存在后可见（越 range scope→404）。
        RiskSnapshot risk = riskQc.loadVisibleRisk(s, riskId);

        // 已撤销（FALSE_POSITIVE）→409，不可再处置。
        if ("FALSE_POSITIVE".equals(risk.reviewed())) {
            throw new ApiException(BizError.STATE_409, "风险已被平台判误报撤销，不可处置: " + riskId);
        }
        // 处置归属：非本组织员工风险只能 escalate，dispose 一律 403。
        if (!riskQc.canDispose(s, risk)) {
            throw new ApiException(BizError.PERM_403, "非本组织员工风险，不可处置（仅可上报）: " + riskId);
        }

        // V1 无 disposed/留痕列：处置动作仅写 audit_log 留痕，不改 risk_record 行（幂等天然——重复同 action 仅多一条审计）。
        riskQc.audit(s, "qc.dispose", riskId,
                "action=" + action + (note == null ? "" : ("; note=" + note)),
                riskQc.snapMap(risk), riskQc.snapMap(risk));

        return Map.of("ok", true, "action", action);
    }

    // ── [3] POST /risks/{id}/escalate  escalateRisk（BR-M5-07a 上报）──────────────
    // perm=qc.escalate scope=own-org。对「非本组织员工」风险只读+上报（物业 PL/PC 见他商催收员风险→上报）。
    // 契约无 requestBody；range 可见即可上报（不要求 own-org 匹配 actorOrg——上报恰用于非本组织风险）。
    @PostMapping("/risks/{id}/escalate")
    @RequirePermission("qc.escalate")
    @Transactional
    public Map<String, Object> escalateRisk(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long riskId = parseId(id);

        // 先存在后可见（越 range scope→404）。
        RiskSnapshot risk = riskQc.loadVisibleRisk(s, riskId);

        // 不改 actor 组织处置态、不建 dispose_task（建任务是平台 review 的事）；仅写审计留上报痕迹。
        riskQc.audit(s, "qc.escalate", riskId, "上报平台复核",
                riskQc.snapMap(risk), riskQc.snapMap(risk));

        return Map.of("ok", true);
    }

    // ── [4] POST /risks/{id}/review  reviewRisk（BR-M5-07c 平台只复核）────────────
    // perm=qc.review scope=platform。CONFIRMED/ESCALATED→建处置任务（先查后插幂等）；FALSE_POSITIVE→撤销。
    @PostMapping("/risks/{id}/review")
    @RequirePermission("qc.review")
    @Transactional
    public Map<String, Object> reviewRisk(@PathVariable("id") String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long riskId = parseId(id);

        // 必平台主体（非平台→403）。
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台可复核风险");
        }
        String verdict = requireEnum(body, "verdict", REVIEW_VERDICTS,
                "verdict 非法（CONFIRMED/FALSE_POSITIVE/ESCALATED）");  // 缺/非枚举→422
        String note = optStr(body, "note");

        RiskSnapshot before = riskQc.loadRisk(riskId);   // 平台全量，不存在→404
        // 已 FALSE_POSITIVE（已撤销）再判→409。
        if ("FALSE_POSITIVE".equals(before.reviewed())) {
            throw new ApiException(BizError.STATE_409, "风险已判误报撤销，不可再复核: " + riskId);
        }

        long actorId = parseAccountId(s);
        jdbc.update(
                "UPDATE risk_record SET reviewed = ?, reviewed_by = ?, reviewed_at = now(),"
                        + " updated_at = now() WHERE id = ?",
                verdict, actorId, riskId);

        if ("CONFIRMED".equals(verdict) || "ESCALATED".equals(verdict)) {
            // 建 dispose_task（责任 org = 违规人所属组织 actorOrgId）；先查后插表级幂等。
            Long existing = jdbc.query(
                    "SELECT id FROM dispose_task WHERE risk_id = ? ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getLong("id") : null, riskId);
            if (existing == null) {
                jdbc.update(
                        "INSERT INTO dispose_task(risk_id, provider, task_type, status, tm)"
                                + " VALUES (?, ?, ?, 'PENDING', now())",
                        riskId, before.actorOrgId(), DEFAULT_TASK_TYPE);
            }
        } else {  // FALSE_POSITIVE → 撤销：不建任务；已存 PENDING 任务置 DONE 留痕。
            jdbc.update(
                    "UPDATE dispose_task SET status = 'DONE', updated_at = now()"
                            + " WHERE risk_id = ? AND status = 'PENDING'",
                    riskId);
        }

        RiskSnapshot after = riskQc.loadRisk(riskId);
        riskQc.audit(s, "qc.review", riskId,
                "verdict=" + verdict + (note == null ? "" : ("; note=" + note)),
                riskQc.snapMap(before), riskQc.snapMap(after));

        return Map.of("ok", true, "verdict", verdict);
    }

    // ── [5] GET /dispose-tasks  listDisposeTasks（BR-M5-07b 仅平台监管视图）────────
    // scope=platform，无 x-permission。非平台（物业/服务商两侧）→403 Forbidden。
    @GetMapping("/dispose-tasks")
    public Page<QcDisposeTaskDto> listDisposeTasks(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "处置任务跟踪仅平台可见");
        }
        Pageable pg = Pageable.of(page, size);

        Long total = jdbc.queryForObject("SELECT count(*) FROM dispose_task", Long.class);
        List<QcDisposeTaskDto> items = jdbc.query(
                "SELECT d.id, d.risk_id, o.name AS provider_name, d.task_type, d.status, d.tm"
                        + " FROM dispose_task d"
                        + " LEFT JOIN org o ON o.id = d.provider"
                        + " ORDER BY d.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new QcDisposeTaskDto(
                        String.valueOf(rs.getLong("id")),
                        String.valueOf(rs.getLong("risk_id")),
                        rs.getString("provider_name"),
                        rs.getString("task_type"),
                        rs.getString("status"),
                        ts(rs.getTimestamp("tm"))),
                pg.size, pg.offset);

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── scope 助手（与 CasesM2Controller.appendRangeScope 同口径，落到 risk 所属案件）────
    /** range scope 追加到 WHERE（含前导 AND）。平台全量 / 服务商 b.provider_id / 物业(兜底) p.org_id。
     *  调用方须已 JOIN \"case\" c / project p / batch b。 */
    private void appendRangeScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        if (s.isPlatform()) return;
        Long org = parseOrgIdOrNull(s);
        if (org == null) {
            // 无有效组织上下文：裁剪到空集（不抛 5xx，列表返回空）。
            where.append(" AND 1=0");
            return;
        }
        if ("PROVIDER".equals(s.orgType())) {
            where.append(" AND b.provider_id = ?");
            args.add(org);
        } else {
            where.append(" AND p.org_id = ?");
            args.add(org);
        }
    }

    // ── 入参解析 / 校验（非法→422/404，绝不 5xx）──────────────────────────────────
    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "风险记录不存在: " + id);
        }
    }

    private static long parseAccountId(CurrentSubject s) {
        try {
            return Long.parseLong(s.accountId());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.PERM_403, "无效主体上下文");
        }
    }

    private static Long parseOrgIdOrNull(CurrentSubject s) {
        try {
            return s.orgId() == null ? null : Long.valueOf(s.orgId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String requireEnum(Map<String, Object> body, String key, Set<String> allowed, String msg) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少必填字段: " + key);
        }
        String val = String.valueOf(v).trim();
        if (!allowed.contains(val)) {
            throw new ApiException(BizError.VALIDATION_422, msg);
        }
        return val;
    }

    private static String optStr(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        String str = String.valueOf(v);
        return str.isBlank() ? null : str.trim();
    }

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }
}
