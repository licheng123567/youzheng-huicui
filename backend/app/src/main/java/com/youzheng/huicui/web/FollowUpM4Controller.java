package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.CaseActivityDto;
import com.youzheng.huicui.web.dto.ContactDto;
import com.youzheng.huicui.web.dto.PromiseDto;
import com.youzheng.huicui.web.dto.PromiseInstallmentDto;
import com.youzheng.huicui.web.dto.TicketDto;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M4「followups」组写/读端点：跟进手记 / 承诺(分期) / 工单(互推闭环) / 联系方式。
 * 横切层范式（对齐 CasesM2Controller 的 range scope 与 HolderM3Controller 的行锁/优雅降级），
 * scaffold 助手内联（settings CFG-TC 读取、range 裁剪、case 可见性复核）。
 * 类名带 M4 后缀，物理隔离，不碰 M1/M2/M3 controller 与共享件/pom。
 *
 * 端点（基路径 /v1 由 context-path 提供，注解写裸路径）：
 *   POST  /cases/{id}/follow-ups  createFollowUp   | perm=case.follow  | scope=case-holder | 201 Activity
 *   GET   /cases/{id}/promises    listCasePromises | scope=range       | 200 PromisePage
 *   POST  /cases/{id}/promises    createPromise    | perm=case.promise | scope=case-actor  | 201 Promise
 *   GET   /cases/{id}/tickets     listCaseTickets  | scope=range       | 200 TicketPage
 *   POST  /cases/{id}/tickets     createTicket     | perm=case.ticket  | scope=case-holder | 201 Ticket
 *   GET   /tickets                listTickets      | scope=range       | 200 TicketPage
 *   POST  /tickets/{id}/handle    handleTicket     | perm=ticket.handle| scope=own-org     | 200 Ticket
 *   POST  /cases/{id}/contacts    createContact    | perm=case.follow  | scope=case-holder | 201 Contact
 *   PATCH /contacts/{id}          updateContact    | perm=case.follow  | scope=case-holder | 200 Contact
 *
 * 优雅降级（Gate1 not_a_server_error 命门）：案件/资源不存在→404；越范围/非本案 holder→403；
 *   状态非法→409 STATE_409；缺必填/枚举非法→422 VALIDATION_422。所有路径绝不 5xx。
 * 金额一律 *_cents（分，Long），不转元；时间 ISO-8601。
 * 幂等：写端点的 Idempotency-Key 由 IdempotencyInterceptor 在 header 层兜底（同键重放→409），
 *   控制器无需重复声明参数。
 */
@RestController
public class FollowUpM4Controller {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public FollowUpM4Controller(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final long DEFAULT_TC_SECONDS = 7L * 24 * 3600;   // CFG-TC 缺省（与 HolderM3 同量级）

    // 通话结果标记缺省码表（settings CFG-MARK-CODES 未配时兜底；与 ai_review.chk 一致）。
    private static final Set<String> DEFAULT_MARK_CODES =
            Set.of("PROMISED", "REFUSED", "NEED_TICKET", "FOLLOW_UP", "NO_ANSWER");

    // ── [1] POST /cases/{id}/follow-ups  createFollowUp ──────────────────────────
    // BR-M4-03a：手记不改案件状态；仅写 activity(type='NOTE')。scope=case-holder（仅 holder 本人）。
    @PostMapping("/cases/{id}/follow-ups")
    @RequirePermission("case.follow")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CaseActivityDto createFollowUp(@PathVariable("id") String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        requireCaseHolder(s, caseId);                         // 不存在→404 / 非本案 holder→403

        String content = requireStr(body, "content");        // 缺→422
        String method = optMethod(body);                      // 非法枚举→422（null 放行）
        String attachmentsJson = attachmentsJson(body);       // [{name,url}] → jsonb 文本（非法→422）

        long actorId = parseAccountId(s);
        Long actId = jdbc.queryForObject(
                "INSERT INTO activity(case_id, type, actor_id, content, method, attachments)"
                        + " VALUES (?, 'NOTE', ?, ?, ?, ?::jsonb) RETURNING id",
                Long.class, caseId, actorId, content, method, attachmentsJson);
        return loadActivity(actId);
    }

    // ── [2] GET /cases/{id}/promises  listCasePromises ───────────────────────────
    // scope=range；承诺 + 子查 promise_installment 组装 installments[]；整体 state 由分期汇总。
    @GetMapping("/cases/{id}/promises")
    public Page<PromiseDto> listCasePromises(@PathVariable("id") String id,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        requireCaseVisible(s, caseId);                        // 不存在→404 / 越范围→403
        Pageable pg = Pageable.of(page, size);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM promise WHERE case_id = ?", Long.class, caseId);
        List<PromiseDto> items = jdbc.query(
                "SELECT * FROM promise WHERE case_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                promiseRowMapper(), caseId, pg.size, pg.offset);
        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [3] POST /cases/{id}/promises  createPromise ─────────────────────────────
    // perm=case.promise scope=case-actor。BR-M4-13 承诺仅履约跟踪，不改应收。
    // 是 holder 本人 → 重置 T_collector（BR-M4-03/12；PL/PC 代登记不重置）。
    @PostMapping("/cases/{id}/promises")
    @RequirePermission("case.promise")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PromiseDto createPromise(@PathVariable("id") String id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        requireCaseActor(s, caseId);                          // 不存在→404 / 越范围(非 actor)→403

        String date = requireStr(body, "date");               // 缺→422
        long amountCents = requireLong(body, "amountCents");   // 缺/非法→422
        List<Map<String, Object>> insts = installmentsInput(body); // 解析校验（非法→422）

        long actorId = parseAccountId(s);
        Long promiseId = jdbc.queryForObject(
                "INSERT INTO promise(case_id, date, amount_cents, state, created_by)"
                        + " VALUES (?, ?::date, ?, 'PENDING', ?) RETURNING id",
                Long.class, caseId, date, amountCents, actorId);

        for (Map<String, Object> inst : insts) {
            jdbc.update(
                    "INSERT INTO promise_installment(promise_id, seq, due_date, amount_cents, state)"
                            + " VALUES (?, ?, ?::date, ?, 'PENDING')",
                    promiseId, instSeq(inst), instStr(inst, "dueDate"), instAmount(inst));
        }

        // activity(PROMISE)
        jdbc.update(
                "INSERT INTO activity(case_id, type, actor_id, content, ref_type, ref_id)"
                        + " VALUES (?, 'PROMISE', ?, ?, 'promise', ?)",
                caseId, actorId, "登记承诺", promiseId);

        // BR-M4-03/12：仅 holder 本人重置 T_collector；PL/PC 代登记不重置。
        if (isHolder(caseId, actorId)) resetTCollector(caseId);

        return loadPromise(promiseId);
    }

    // ── [4] GET /cases/{id}/tickets  listCaseTickets ─────────────────────────────
    @GetMapping("/cases/{id}/tickets")
    public Page<TicketDto> listCaseTickets(@PathVariable("id") String id,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        requireCaseVisible(s, caseId);                        // 不存在→404 / 越范围→403
        Pageable pg = Pageable.of(page, size);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM ticket WHERE case_id = ?", Long.class, caseId);
        List<TicketDto> items = jdbc.query(
                "SELECT * FROM ticket WHERE case_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                ticketRowMapper(), caseId, pg.size, pg.offset);
        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [5] POST /cases/{id}/tickets  createTicket ───────────────────────────────
    // perm=case.ticket scope=case-holder。CO→PC 互推（from_role='CO', to_role='PC', status='PENDING'）。
    @PostMapping("/cases/{id}/tickets")
    @RequirePermission("case.ticket")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TicketDto createTicket(@PathVariable("id") String id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        requireCaseHolder(s, caseId);                         // 不存在→404 / 非本案 holder→403

        String type = requireStr(body, "type");               // 缺→422
        String note = optStr(body, "note");

        long actorId = parseAccountId(s);
        Long ticketId = jdbc.queryForObject(
                "INSERT INTO ticket(case_id, type, note, from_role, to_role, status, created_by)"
                        + " VALUES (?, ?, ?, 'CO', 'PC', 'PENDING', ?) RETURNING id",
                Long.class, caseId, type, note, actorId);

        jdbc.update(
                "INSERT INTO activity(case_id, type, actor_id, content, ref_type, ref_id)"
                        + " VALUES (?, 'TICKET', ?, ?, 'ticket', ?)",
                caseId, actorId, "转工单: " + type, ticketId);

        return loadTicket(ticketId);
    }

    // ── [6] GET /tickets  listTickets ────────────────────────────────────────────
    // scope=range（协调员处理队列）；过滤 status(PENDING/HANDLED)/caseId + 分页。
    @GetMapping("/tickets")
    public Page<TicketDto> listTickets(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String caseId,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            if (!"PENDING".equals(status) && !"HANDLED".equals(status)) {
                throw new ApiException(BizError.VALIDATION_422, "status 非法（仅 PENDING/HANDLED）");
            }
            where.append(" AND t.status = ?");
            args.add(status);
        }
        if (caseId != null && !caseId.isBlank()) {
            where.append(" AND t.case_id = ?");
            args.add(parseId(caseId));
        }
        appendRangeScope(s, where, args);

        String base = "FROM ticket t"
                + " JOIN \"case\" c ON c.id = t.case_id"
                + " JOIN project p ON p.id = c.project_id"
                + " JOIN batch b ON b.id = c.batch_id"
                + where;

        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<TicketDto> items = jdbc.query(
                "SELECT t.* " + base + " ORDER BY t.id DESC LIMIT ? OFFSET ?",
                ticketRowMapper(), pageArgs.toArray());
        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [7] POST /tickets/{id}/handle  handleTicket ──────────────────────────────
    // perm=ticket.handle scope=own-org。仅 PENDING 可处理→否则 409 STATE_409。
    @PostMapping("/tickets/{id}/handle")
    @RequirePermission("ticket.handle")
    @Transactional
    public TicketDto handleTicket(@PathVariable("id") String id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long ticketId = parseId(id);

        // own-org：工单 case 所属物业 org=本组织（平台放行），否则按不可见 404。
        Long caseId = ticketCaseIdForOwnOrg(s, ticketId);     // 不存在/越组织→404
        String result = optStr(body, "result");
        String receipt = optStr(body, "receipt");

        long actorId = parseAccountId(s);
        // 仅 PENDING→HANDLED 的 CAS 更新；落空→409（已处理或被并发处理）。
        int n = jdbc.update(
                "UPDATE ticket SET status = 'HANDLED', result = ?, receipt = ?,"
                        + " handled_by = ?, handled_at = now(), updated_at = now()"
                        + " WHERE id = ? AND status = 'PENDING'",
                result, receipt, actorId, ticketId);
        if (n == 0) {
            throw new ApiException(BizError.STATE_409, "工单非待处理状态，无法处理: " + ticketId);
        }
        jdbc.update(
                "INSERT INTO activity(case_id, type, actor_id, content, ref_type, ref_id)"
                        + " VALUES (?, 'TICKET', ?, ?, 'ticket', ?)",
                caseId, actorId, "工单已处理", ticketId);
        return loadTicket(ticketId);
    }

    // ── [8] POST /cases/{id}/contacts  createContact ─────────────────────────────
    // perm=case.follow scope=case-holder。isPrimary=true → 先清同 case 其他 is_primary（单主号 BR-M4-11）。
    @PostMapping("/cases/{id}/contacts")
    @RequirePermission("case.follow")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ContactDto createContact(@PathVariable("id") String id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        requireCaseHolder(s, caseId);                         // 不存在→404 / 非本案 holder→403

        String phone = requireStr(body, "phone");             // 缺→422
        String label = optStr(body, "label");
        boolean isPrimary = optBool(body, "isPrimary");
        boolean invalid = optBool(body, "invalid");

        if (isPrimary) {
            jdbc.update("UPDATE contact SET is_primary = FALSE, updated_at = now() WHERE case_id = ?", caseId);
        }
        Long contactId = jdbc.queryForObject(
                "INSERT INTO contact(case_id, phone, label, is_primary, invalid)"
                        + " VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, caseId, phone, label, isPrimary, invalid);

        jdbc.update(
                "INSERT INTO activity(case_id, type, actor_id, content)"
                        + " VALUES (?, 'OPLOG', ?, ?)",
                caseId, parseAccountId(s), "新增联系方式");

        return loadContact(contactId);
    }

    // ── [9] PATCH /contacts/{id}  updateContact ──────────────────────────────────
    // perm=case.follow scope=case-holder。部分更新 isPrimary/invalid/label；
    // isPrimary=true → 同 case 其他置 false（单主号 BR-M4-11）。不存在→404 / 非本案 holder→403。
    @PatchMapping("/contacts/{id}")
    @RequirePermission("case.follow")
    @Transactional
    public ContactDto updateContact(@PathVariable("id") String id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long contactId = parseId(id);

        Long caseId = jdbc.query(
                "SELECT case_id FROM contact WHERE id = ?",
                rs -> rs.next() ? rs.getLong("case_id") : null, contactId);
        if (caseId == null) {
            throw new ApiException(BizError.NOT_FOUND_404, "联系方式不存在: " + id);
        }
        requireCaseHolder(s, caseId);                         // 非本案 holder→403

        Boolean isPrimary = optBoolOrNull(body, "isPrimary");
        Boolean invalid = optBoolOrNull(body, "invalid");
        String label = optStr(body, "label");

        if (isPrimary != null && isPrimary) {
            // 先清同 case 其他主号，再把本行置主（单主号约束）。
            jdbc.update("UPDATE contact SET is_primary = FALSE, updated_at = now() WHERE case_id = ?", caseId);
        }
        // 动态部分更新：仅 set 入参提供的字段。
        StringBuilder set = new StringBuilder("UPDATE contact SET updated_at = now()");
        List<Object> args = new ArrayList<>();
        if (isPrimary != null) { set.append(", is_primary = ?"); args.add(isPrimary); }
        if (invalid != null) { set.append(", invalid = ?"); args.add(invalid); }
        if (body != null && body.containsKey("label")) { set.append(", label = ?"); args.add(label); }
        set.append(" WHERE id = ?");
        args.add(contactId);
        jdbc.update(set.toString(), args.toArray());

        return loadContact(contactId);
    }

    // ── scope / 可见性助手 ────────────────────────────────────────────────────────

    /**
     * range scope 追加到 WHERE（含前导 AND），与 CasesM2Controller.appendRangeScope 同口径：
     * 平台全量 / 服务商 b.provider_id / 物业(兜底) p.org_id。
     * 调用方须已 JOIN \"case\" c / project p / batch b。
     */
    private void appendRangeScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        if (s.isPlatform()) return;
        if ("PROVIDER".equals(s.orgType())) {
            where.append(" AND b.provider_id = ?");
            args.add(parseOrgId(s));
        } else {
            where.append(" AND p.org_id = ?");
            args.add(parseOrgId(s));
        }
    }

    /** range 可见性（GET 端点）：不存在→404，越范围→403。 */
    private void requireCaseVisible(CurrentSubject s, long caseId) {
        if (!caseExists(caseId)) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + caseId);
        }
        if (!visibleByRange(s, caseId)) {
            throw new ApiException(BizError.PERM_403, "无权查看该案件");
        }
    }

    /**
     * case-actor scope（写端点）：CO 持有 + 关联 PL/PC + SA 代均可操作。
     * 地基期以 range（own-org 三分支）兜底——同组织即视为 actor，避免存在性泄漏。
     * TODO(M4 精确化)：CO 限 holder 本人；PL/PC 限本组织协调岗；SA 平台代。
     */
    private void requireCaseActor(CurrentSubject s, long caseId) {
        if (!caseExists(caseId)) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + caseId);
        }
        if (!visibleByRange(s, caseId)) {
            throw new ApiException(BizError.PERM_403, "无权操作该案件");
        }
    }

    /**
     * case-holder scope（手记/工单/联系人写端点）：仅案件 holder 本人。
     * 平台主体（SA 代）放行；其余须 case.holder_id = 当前 accountId。
     * 不存在→404；非本案 holder→403。
     */
    private void requireCaseHolder(CurrentSubject s, long caseId) {
        if (!caseExists(caseId)) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + caseId);
        }
        if (s.isPlatform()) return;                            // SA 平台代放行
        long me = parseAccountId(s);
        if (!isHolder(caseId, me)) {
            throw new ApiException(BizError.PERM_403, "非本案持有催收员，不可操作: " + caseId);
        }
    }

    private boolean caseExists(long caseId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM \"case\" WHERE id = ?", Long.class, caseId);
        return n != null && n > 0;
    }

    private boolean isHolder(long caseId, long accountId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM \"case\" WHERE id = ? AND holder_id = ?",
                Long.class, caseId, accountId);
        return n != null && n > 0;
    }

    private boolean visibleByRange(CurrentSubject s, long caseId) {
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

    /** handleTicket own-org：返回工单 case_id，越组织/不存在→404（避免存在性泄漏）。 */
    private Long ticketCaseIdForOwnOrg(CurrentSubject s, long ticketId) {
        Long[] row = jdbc.query(
                "SELECT t.case_id, p.org_id FROM ticket t"
                        + " JOIN \"case\" c ON c.id = t.case_id"
                        + " JOIN project p ON p.id = c.project_id"
                        + " WHERE t.id = ?",
                rs -> {
                    if (!rs.next()) return null;
                    return new Long[]{ rs.getLong("case_id"), rs.getLong("org_id") };
                }, ticketId);
        if (row == null) {
            throw new ApiException(BizError.NOT_FOUND_404, "工单不存在: " + ticketId);
        }
        if (!s.isPlatform()) {
            Long org = parseOrgId(s);
            if (org == null || !org.equals(row[1])) {
                throw new ApiException(BizError.NOT_FOUND_404, "工单不存在: " + ticketId);
            }
        }
        return row[0];
    }

    /** BR-M4-03/12：重置 case.t_collector_deadline = now() + CFG-TC。 */
    private void resetTCollector(long caseId) {
        jdbc.update(
                "UPDATE \"case\" SET t_collector_deadline = now() + (? * interval '1 second'),"
                        + " updated_at = now() WHERE id = ?",
                tcSeconds(), caseId);
    }

    private long tcSeconds() {
        try {
            Long v = jdbc.query(
                    "SELECT timers ->> 'tcSeconds' AS v FROM settings WHERE domain = 'TIMERS'"
                            + " ORDER BY version DESC LIMIT 1",
                    rs -> {
                        if (!rs.next()) return null;
                        String raw = rs.getString("v");
                        if (raw == null || raw.isBlank()) return null;
                        try { return Long.valueOf(raw.trim()); } catch (NumberFormatException e) { return null; }
                    });
            return v == null ? DEFAULT_TC_SECONDS : v;
        } catch (RuntimeException e) {
            return DEFAULT_TC_SECONDS;
        }
    }

    // ── RowMapper / loaders ──────────────────────────────────────────────────────

    private CaseActivityDto loadActivity(long activityId) {
        return jdbc.queryForObject(
                "SELECT a.*, acc.name AS actor_name FROM activity a"
                        + " LEFT JOIN account acc ON acc.id = a.actor_id WHERE a.id = ?",
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
                activityId);
    }

    private PromiseDto loadPromise(long promiseId) {
        return jdbc.queryForObject(
                "SELECT * FROM promise WHERE id = ?", promiseRowMapper(), promiseId);
    }

    private RowMapper<PromiseDto> promiseRowMapper() {
        return (rs, i) -> {
            long promiseId = rs.getLong("id");
            List<PromiseInstallmentDto> installments = jdbc.query(
                    "SELECT seq, due_date, amount_cents, state FROM promise_installment"
                            + " WHERE promise_id = ? ORDER BY seq",
                    (r2, j) -> new PromiseInstallmentDto(
                            r2.getInt("seq"),   // promise_installment.seq NOT NULL
                            dateStr(r2.getTimestamp("due_date")),
                            longOrNull(r2, "amount_cents"),
                            r2.getString("state")),
                    promiseId);
            // 整体 state：有分期则由分期汇总；无分期取 promise.state。
            String state = installments.isEmpty()
                    ? rs.getString("state")
                    : summarizeInstallmentState(installments);
            return new PromiseDto(
                    String.valueOf(promiseId),
                    String.valueOf(rs.getLong("case_id")),
                    dateStr(rs.getTimestamp("date")),
                    longOrNull(rs, "amount_cents"),
                    state,
                    installments,
                    idOrNull(rs, "created_by"),
                    ts(rs.getTimestamp("created_at")));
        };
    }

    /** 分期汇总整体履约（PromiseStateEnum）：全兑现=FULFILLED，全待履约=PENDING，有兑现=PARTIAL_FULFILLED，有违约=BROKEN。 */
    private static String summarizeInstallmentState(List<PromiseInstallmentDto> insts) {
        boolean anyFulfilled = false, anyPending = false, anyBroken = false;
        for (PromiseInstallmentDto i : insts) {
            switch (i.state() == null ? "" : i.state()) {
                case "FULFILLED" -> anyFulfilled = true;
                case "BROKEN" -> anyBroken = true;
                default -> anyPending = true;
            }
        }
        if (anyBroken) return "BROKEN";
        if (anyFulfilled && anyPending) return "PARTIAL_FULFILLED";
        if (anyFulfilled) return "FULFILLED";
        return "PENDING";
    }

    private TicketDto loadTicket(long ticketId) {
        return jdbc.queryForObject(
                "SELECT * FROM ticket WHERE id = ?", ticketRowMapper(), ticketId);
    }

    private RowMapper<TicketDto> ticketRowMapper() {
        return (rs, i) -> new TicketDto(
                String.valueOf(rs.getLong("id")),
                String.valueOf(rs.getLong("case_id")),
                rs.getString("type"),
                rs.getString("note"),
                rs.getString("status"),
                rs.getString("result"),
                rs.getString("receipt"),
                idOrNull(rs, "created_by"),
                ts(rs.getTimestamp("created_at")),
                idOrNull(rs, "handled_by"),
                ts(rs.getTimestamp("handled_at")));
    }

    private ContactDto loadContact(long contactId) {
        return jdbc.queryForObject(
                "SELECT * FROM contact WHERE id = ?",
                (rs, i) -> new ContactDto(
                        String.valueOf(rs.getLong("id")),
                        String.valueOf(rs.getLong("case_id")),
                        rs.getString("phone"),
                        rs.getString("label"),
                        rs.getBoolean("is_primary"),
                        rs.getBoolean("invalid")),
                contactId);
    }

    // ── 入参解析 / 校验（非法→422，绝不 5xx）──────────────────────────────────────

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "资源不存在: " + id);
        }
    }

    private static long parseAccountId(CurrentSubject s) {
        try {
            return Long.parseLong(s.accountId());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.PERM_403, "无效主体上下文");
        }
    }

    private static Long parseOrgId(CurrentSubject s) {
        try {
            return s.orgId() == null ? null : Long.valueOf(s.orgId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String requireStr(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少必填字段: " + key);
        }
        return String.valueOf(v).trim();
    }

    private static String optStr(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        String str = String.valueOf(v);
        return str.isBlank() ? null : str.trim();
    }

    private static boolean optBool(Map<String, Object> body, String key) {
        Boolean b = optBoolOrNull(body, key);
        return b != null && b;
    }

    private static Boolean optBoolOrNull(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String str = String.valueOf(v).trim();
        if ("true".equalsIgnoreCase(str)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(str)) return Boolean.FALSE;
        throw new ApiException(BizError.VALIDATION_422, key + " 须为布尔值");
    }

    private static long requireLong(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少必填字段: " + key);
        }
        if (v instanceof Number num) return num.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, key + " 须为整数（分）");
        }
    }

    /** FollowUpInput.method 枚举校验（CALL/SMS/VISIT/WECHAT/OTHER）；缺省 null 放行。 */
    private static String optMethod(Map<String, Object> body) {
        String m = optStr(body, "method");
        if (m == null) return null;
        if (!Set.of("CALL", "SMS", "VISIT", "WECHAT", "OTHER").contains(m)) {
            throw new ApiException(BizError.VALIDATION_422, "method 非法（CALL/SMS/VISIT/WECHAT/OTHER）");
        }
        return m;
    }

    /** attachments [{name,url}] → jsonb 文本；缺/空→null；结构非法→422。 */
    private String attachmentsJson(Map<String, Object> body) {
        Object v = body == null ? null : body.get("attachments");
        if (v == null) return null;
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            if (v instanceof List<?>) return null;            // 空数组等价无附件
            throw new ApiException(BizError.VALIDATION_422, "attachments 须为数组");
        }
        try {
            return json.writeValueAsString(list);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ApiException(BizError.VALIDATION_422, "attachments 结构非法");
        }
    }

    /** PromiseInput.installments 解析校验（可选数组；每项 seq/dueDate/amountCents）。非法→422。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> installmentsInput(Map<String, Object> body) {
        Object v = body == null ? null : body.get("installments");
        if (v == null) return List.of();
        if (!(v instanceof List<?> list)) {
            throw new ApiException(BizError.VALIDATION_422, "installments 须为数组");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?>)) {
                throw new ApiException(BizError.VALIDATION_422, "installments 元素须为对象");
            }
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    private static int instSeq(Map<String, Object> inst) {
        Object v = inst.get("seq");
        if (v instanceof Number num) return num.intValue();
        if (v == null) throw new ApiException(BizError.VALIDATION_422, "installment.seq 必填");
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, "installment.seq 非法");
        }
    }

    private static String instStr(Map<String, Object> inst, String key) {
        Object v = inst.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "installment." + key + " 必填");
        }
        return String.valueOf(v).trim();
    }

    private static long instAmount(Map<String, Object> inst) {
        Object v = inst.get("amountCents");
        if (v instanceof Number num) return num.longValue();
        if (v == null) throw new ApiException(BizError.VALIDATION_422, "installment.amountCents 必填");
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, "installment.amountCents 非法");
        }
    }

    // ── 低级转换 ───────────────────────────────────────────────────────────────

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    /** DATE 列 → ISO yyyy-MM-dd（取 instant 的日期部分，对齐契约 format: date）。 */
    private static String dateStr(Timestamp t) {
        if (t == null) return null;
        return t.toLocalDateTime().toLocalDate().toString();
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static String idOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : String.valueOf(v);
    }
}
