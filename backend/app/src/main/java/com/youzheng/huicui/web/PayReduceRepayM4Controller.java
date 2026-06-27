package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.PayLinkDto;
import com.youzheng.huicui.web.dto.ReductionDto;
import com.youzheng.huicui.web.dto.RepayLineDto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * M4 缴费链接 / 减免 / 回款组（横切层范式 + scaffold 行锁助手 {@link CaseScopeM4Service}）。
 * 类名带 M4 后缀，与 M1/M2/M3 controller 物理隔离，不碰共享件/其他组/pom。
 *
 * 端点（基路径 /v1 由 context-path 提供，注解写裸路径）：
 *   POST /cases/{id}/pay-links   createPayLink  | perm=case.paylink   | scope=case-actor | 幂等 | 201 PayLink / 409
 *   POST /pay-links/{id}/resend  resendPayLink  | perm=case.paylink   | scope=case-actor | 幂等 | 200 / 409
 *   POST /pay-links/{id}/void    voidPayLink    | perm=case.paylink   | scope=case-actor |      | 200（幂等）
 *   POST /cases/{id}/reductions  createReduction| perm=case.reduce    | scope=own-org    |      | 201 Reduction / 422
 *   POST /reductions/{id}/approve approveReduction| perm=reduce.approve| scope=own-org   |      | 200 / 403
 *   POST /cases/{id}/repay-lines createRepayLine | perm=case.repay.mark| scope=own-org   | 幂等 | 201 RepayLine / 422
 *   POST /repay-lines/{id}/reverse reverseRepayLine| perm=case.repay.mark| scope=own-org | 幂等 | 200 / 409
 *
 * 优雅降级（Gate1 not_a_server_error 命门）——所有非法输入映射契约 Error 信封，绝不 5xx：
 *   资源不存在 / 路径 id 非法形态 → 404 NOT_FOUND_404；
 *   越数据范围（case-actor / own-org 不可见）→ 403 PERM_403（拦截器标 perm，scope 在此复核）；
 *   状态不允许（如非 OFFLINE_TRACE approve / 已入 PAID 单冲正）→ 409 STATE_409 或专用 BIZ_*；
 *   缺必填 / 入参非法（金额非数、阶梯越界、channel 非法）→ 422 VALIDATION_422。
 *
 * 金额一律 *_cents（Long，分），paidAt 为 date（yyyy-MM-dd）。响应体对齐契约 schema。
 * 列名严格对齐 V1 DDL：pay_link / reduction / reduce_tier / repay_line / "case"(双引号) / activity。
 */
@RestController
public class PayReduceRepayM4Controller {

    private final JdbcTemplate jdbc;
    private final CaseScopeM4Service scope;

    public PayReduceRepayM4Controller(JdbcTemplate jdbc, CaseScopeM4Service scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    // CFG 缺省（settings 未配时兜底）。
    private static final long DEFAULT_PAYLINK_TTL_SECONDS = 7L * 24 * 3600;  // CFG 缴费链接有效期 7d
    private static final long DEFAULT_SMS_COOLDOWN_SECONDS = 6L * 3600;      // CFG-SMS-COOLDOWN 同案短信冷却

    // ── [1] createPayLink  POST /cases/{id}/pay-links ────────────────────────
    // amount=case.reduce_after_cents ?: due_cents（BR-M4-04/14/15）。SMS 受同案冷却 BR-M4-14a。
    @PostMapping("/cases/{id}/pay-links")
    @RequirePermission("case.paylink")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PayLinkDto createPayLink(@PathVariable("id") String id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseCaseId(id);
        CaseScopeM4Service.CaseRow c = scope.requireCaseActor(s, caseId);  // 不存在→404 / 不可见→403

        String channel = parseChannel(body);                              // 缺/非法→422
        Long suggestionId = parseOptionalLong(body, "sourceSuggestionId"); // 可选；非数→422

        // 金额=减免后应收，缺则原应收（契约/BR-M4-04）。
        long amountCents = c.reduceAfterCents() != null ? c.reduceAfterCents() : c.dueCents();

        if ("SMS".equals(channel)) {
            // BR-M4-14a：同案最近一条 SMS pay_link < 冷却窗口 → 409 BIZ_SMS_COOLDOWN。
            requireSmsCooldownPassed(caseId);
            // 余量不足→409 BIZ_QUOTA_EXHAUSTED（M9 预付费，地基期不接，不触发）。TODO(M9)。
        }
        // WECHAT_COPY 不限频、不扣条数。

        String token = UUID.randomUUID().toString();
        Timestamp expiresAt = Timestamp.from(Instant.now().plusSeconds(payLinkTtlSeconds()));
        Long actorId = actorId(s);

        Long payLinkId = jdbc.queryForObject(
                "INSERT INTO pay_link(case_id, token, amount_cents, expires_at, status, channel, created_by)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?) RETURNING id",
                Long.class, caseId, token, amountCents, expiresAt, channel, actorId);

        // 写 activity（SMS 类型，ref 指向 pay_link）。WECHAT_COPY 亦记 SMS 类型活动以统一渠道动作流。
        insertActivity(caseId, "SMS", actorId,
                "发缴费链接(" + channel + ")", "pay_link", payLinkId, channel);

        // suggestionId 仅留溯源（地基期不落 StrategyCard 关联表），避免未用告警显式忽略。
        if (suggestionId != null) { /* TODO(M5): 关联 StrategyCard 溯源 sourceSuggestionId */ }

        return new PayLinkDto(String.valueOf(payLinkId), token, amountCents,
                ISO.format(expiresAt.toInstant()), "ACTIVE");
    }

    // ── [2] resendPayLink  POST /pay-links/{id}/resend ──────────────────────
    // 有效链接优先重发（BR-M4-15）；SMS 受冷却→409。不存在→404。
    @PostMapping("/pay-links/{id}/resend")
    @RequirePermission("case.paylink")
    @Transactional
    public Map<String, Object> resendPayLink(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long payLinkId = parsePayLinkId(id);
        PayLinkRow pl = lockPayLink(payLinkId);                 // 不存在→404
        scope.requireCaseActor(s, pl.caseId);                   // case 不可见→403

        if ("SMS".equals(pl.channel)) {
            requireSmsCooldownPassed(pl.caseId);                // 冷却未到→409 BIZ_SMS_COOLDOWN
        }
        // 重发即再记一条渠道动作（不新建链接，沿用同 token；有效链接优先 BR-M4-15）。
        insertActivity(pl.caseId, "SMS", actorId(s),
                "重发缴费链接(" + nz(pl.channel) + ")", "pay_link", payLinkId, pl.channel);
        return ok();
    }

    // ── [3] voidPayLink  POST /pay-links/{id}/void ──────────────────────────
    // UPDATE status='EXPIRED'（幂等：已 EXPIRED 仍 200）。业主访问失效提示由 M7 H5 处理。
    @PostMapping("/pay-links/{id}/void")
    @RequirePermission("case.paylink")
    @Transactional
    public Map<String, Object> voidPayLink(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long payLinkId = parsePayLinkId(id);
        PayLinkRow pl = lockPayLink(payLinkId);                 // 不存在→404
        scope.requireCaseActor(s, pl.caseId);                   // case 不可见→403

        if (!"EXPIRED".equals(pl.status)) {
            jdbc.update("UPDATE pay_link SET status = 'EXPIRED', updated_at = now() WHERE id = ?", payLinkId);
            insertActivity(pl.caseId, "SMS", actorId(s),
                    "作废缴费链接", "pay_link", payLinkId, pl.channel);
        }
        return ok();                                            // 幂等：重复作废仍 200
    }

    // ── [4] createReduction  POST /cases/{id}/reductions ────────────────────
    // 读 reduce_tier[tierIndex] 得 discount/decide（越界→422）。BR-M2-18a：
    //   decide=COLLECTOR_SELF → state=EFFECTIVE 并联动 case.reduce_after_cents=due_cents-amount；
    //   超自决档(OFFLINE_INTERNAL/PL_APPROVE) → state=OFFLINE_TRACE（系统仅留痕，不改 reduce_after）。
    @PostMapping("/cases/{id}/reductions")
    @RequirePermission("case.reduce")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ReductionDto createReduction(@PathVariable("id") String id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseCaseId(id);
        CaseScopeM4Service.CaseRow c = scope.requireOwnOrg(s, caseId);    // 不存在→404 / 越组织→403

        Integer tierIndex = parseRequiredInt(body, "tierIndex");          // 缺/非整→422
        if (tierIndex < 0) throw new ApiException(BizError.VALIDATION_422, "tierIndex 非法");
        Long amountCents = parseOptionalLong(body, "amountCents");        // 可选；非数→422
        String note = parseOptionalString(body, "note");

        // 阶梯：批次覆盖优先（batch_id = case.batch_id），否则项目级（batch_id IS NULL）；按 id 序取第 tierIndex 个。
        TierRow tier = loadTierAt(c.projectId(), c.batchId(), tierIndex);
        if (tier == null) {
            throw new ApiException(BizError.VALIDATION_422, "减免阶梯越界: tierIndex=" + tierIndex);
        }

        long amount = amountCents != null ? amountCents : 0L;
        // 减免额须在 [0, 应收]：超过应收会使 reduce_after_cents 变负，传导到业主账单 payableCents/对账分母(审计 H-4)。
        if (amount < 0 || amount > c.dueCents()) {
            throw new ApiException(BizError.VALIDATION_422, "amountCents 非法（须 0 ≤ 减免 ≤ 应收）");
        }

        boolean selfDecide = "COLLECTOR_SELF".equals(tier.decide);
        String state = selfDecide ? "EFFECTIVE" : "OFFLINE_TRACE";
        Long actorId = actorId(s);

        Long reductionId = jdbc.queryForObject(
                "INSERT INTO reduction(case_id, tier_ref, discount, amount_cents, decide, state, applied_by, note)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, caseId, tierIndex, tier.discount, amount, tier.decide, state, actorId, note);

        // 自决档生效即联动减免后应收；超档仅留痕，不改 reduce_after_cents（线下审批走线下 BR-M2-18a）。
        if (selfDecide) {
            jdbc.update(
                    "UPDATE \"case\" SET reduce_after_cents = (due_cents - ?), updated_at = now() WHERE id = ?",
                    amount, caseId);
        }
        insertActivity(caseId, "NOTE", actorId,
                "提交减免(" + tier.decide + "/" + state + ")", "reduction", reductionId, null);

        return new ReductionDto(String.valueOf(reductionId), tier.decide, state, amount);
    }

    // ── [5] approveReduction  POST /reductions/{id}/approve ─────────────────
    // PL 系统内直接核准（终点 BR-M2-18a）：仅 OFFLINE_TRACE→EFFECTIVE 合法并联动 reduce_after；
    //   已 EFFECTIVE→幂等 200；其它态→409。非本组织/非 PL→403。
    @PostMapping("/reductions/{id}/approve")
    @RequirePermission("reduce.approve")
    @Transactional
    public Map<String, Object> approveReduction(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long reductionId = parseGenericId(id);
        ReductionRow r = lockReduction(reductionId);            // 不存在→404
        scope.requireOwnOrg(s, r.caseId);                       // 越组织→403（own-org 复核）

        if ("EFFECTIVE".equals(r.state)) {
            return ok();                                        // 幂等：已生效重复核准仍 200
        }
        if (!"OFFLINE_TRACE".equals(r.state)) {
            throw new ApiException(BizError.STATE_409, "减免状态不可核准: " + r.state);
        }
        jdbc.update("UPDATE reduction SET state = 'EFFECTIVE', updated_at = now() WHERE id = ?", reductionId);
        // 联动减免后应收（核准生效后才扣减 BR-M2-18a）。
        jdbc.update(
                "UPDATE \"case\" SET reduce_after_cents = (due_cents - ?), updated_at = now() WHERE id = ?",
                r.amountCents, r.caseId);
        insertActivity(r.caseId, "NOTE", actorId(s),
                "核准减免(OFFLINE_TRACE→EFFECTIVE)", "reduction", reductionId, null);
        return ok();
    }

    // ── [6] createRepayLine  POST /cases/{id}/repay-lines ───────────────────
    // 标注线下回款（本期回款唯一入口 US-M4-08）。INSERT repay_line(batch_id 派生自 case)；
    //   触发结清判定：累计有效回款 ≥ 应收 → case.status='SETTLED'+closed_at。
    @PostMapping("/cases/{id}/repay-lines")
    @RequirePermission("case.repay.mark")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RepayLineDto createRepayLine(@PathVariable("id") String id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseCaseId(id);
        // case-actor 行级：CO 仅持有本人/PL-PC 本物业/SA-SE 平台（防同 org 非持有 CO 越权标他案回款）。
        CaseScopeM4Service.CaseRow c = scope.requireCaseActor(s, caseId);

        Long amountCents = parseRequiredLong(body, "amountCents");        // 缺/非数→422
        if (amountCents <= 0) throw new ApiException(BizError.VALIDATION_422, "amountCents 非法");
        String channel = parseRepayChannel(body);                         // 缺/非法→422
        LocalDate paidAt = parseRequiredDate(body, "paidAt");             // 缺/非法→422
        String note = parseOptionalString(body, "note");
        Long actorId = actorId(s);

        // B-03 到账归属快照（资金正确性·阻断）：登记回款即固化承接/持有归属，结算一律按此，
        //   不随后续单案再派(case.provider_id 改写)或换持有人(case.holder_id 改写)漂移。
        //   providerAtRepay = COALESCE(case.provider_id, batch.provider_id)（案件级优先，回落批次级）；
        //   collectorAtRepay = case.holder_id（CaseRow 已携带）。冲正不动快照。
        Long providerAtRepay = c.providerId() != null
                ? c.providerId()
                : jdbc.query("SELECT provider_id FROM batch WHERE id = ?",
                        rs -> rs.next() ? (Long) rs.getObject("provider_id") : null, c.batchId());
        Long collectorAtRepay = c.holderId();

        Long repayId = jdbc.queryForObject(
                "INSERT INTO repay_line(case_id, batch_id, amount_cents, channel, paid_at, note, marked_by, settled,"
                        + " provider_id_at_repay, collector_id_at_repay)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, false, ?, ?) RETURNING id",
                Long.class, caseId, c.batchId(), amountCents, channel, Date.valueOf(paidAt), note, actorId,
                providerAtRepay, collectorAtRepay);

        insertActivity(caseId, "NOTE", actorId,
                "标注线下回款(" + channel + ")", "repay_line", repayId, null);

        // 结清判定（US-M4-08）：累计有效（未冲正）回款 ≥ 减免后应收(无则原应收) → SETTLED。
        maybeSettle(c, caseId);

        return new RepayLineDto(String.valueOf(repayId), String.valueOf(caseId),
                c.ownerName(), c.room(), amountCents, channel, paidAt.toString(), false, null);
    }

    // ── [7] reverseRepayLine  POST /repay-lines/{id}/reverse ────────────────
    // 误标红冲（BR-M4-07）。已纳入 PAID 支付申请单的明细→409（走撤销流程）；
    //   否则 UPDATE reversed=true + reverse_reason + reversed_at；联动回退结清。佣金冲减留 M9。
    @PostMapping("/repay-lines/{id}/reverse")
    @RequirePermission("case.repay.mark")
    @Transactional
    public Map<String, Object> reverseRepayLine(@PathVariable("id") String id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long repayId = parseGenericId(id);
        String reason = parseRequiredString(body, "reason");    // 缺→422
        RepayLineRow rl = lockRepayLine(repayId);               // 不存在→404
        // case-actor 行级（同标注口径）：CO 仅持有本人/PL-PC 本物业/SA-SE 平台。
        CaseScopeM4Service.CaseRow c = scope.requireCaseActor(s, rl.caseId);

        if (Boolean.TRUE.equals(rl.reversed)) {
            return ok();                                        // 幂等：已冲正再冲正仍 200
        }
        // BLOCKER-1·冲正时序：已纳入未撤销支付申请单（payment_request_id 指向 PENDING 或 PAID 单）→409，
        //   须先 revoke 支付申请单再冲正。否则 PENDING 单绑定的 line 被冲正后 complete 仍会把 reversed=true
        //   的明细结算为 PAID（资金错配）。仅 VOIDED（已撤销，明细已解绑）不挡——实际解绑后 payment_request_id
        //   已置 NULL，此分支等同 null。
        if (rl.paymentRequestId != null && isPaymentRequestActive(rl.paymentRequestId)) {
            throw new ApiException(BizError.STATE_409,
                    "回款已纳入未撤销支付申请单，须先撤销该单再冲正: " + repayId);
        }
        jdbc.update(
                "UPDATE repay_line SET reversed = true, reverse_reason = ?, reversed_at = now(), updated_at = now()"
                        + " WHERE id = ?",
                reason, repayId);
        insertActivity(rl.caseId, "NOTE", actorId(s),
                "回款冲正/红冲", "repay_line", repayId, null);

        // 联动回退结清：冲正后若累计有效回款 < 应收 且当前已 SETTLED，则退回 IN_PROGRESS（佣金冲减下期留 M9）。TODO(M9)。
        maybeUnsettle(c, rl.caseId);
        return ok();
    }

    // ── 结清/反结清判定 ───────────────────────────────────────────────────────

    /** 累计有效（未冲正）回款 ≥ 应收（reduce_after_cents 优先，无则 due_cents）→ SETTLED。仅从在线态结清，幂等。 */
    private void maybeSettle(CaseScopeM4Service.CaseRow c, long caseId) {
        long target = c.reduceAfterCents() != null ? c.reduceAfterCents() : c.dueCents();
        long paid = sumActiveRepay(caseId);
        boolean onlineStatus = "IN_PROGRESS".equals(c.status()) || "PROMISED".equals(c.status());
        if (paid >= target && target > 0 && onlineStatus) {
            // 状态前置 CAS：c.status() 取自非锁快照，UPDATE 带 status 条件防并发回款/冲正基于陈旧态重复转移(审计 H-5)。
            int n = jdbc.update(
                    "UPDATE \"case\" SET status = 'SETTLED', closed_at = now(), updated_at = now()"
                            + " WHERE id = ? AND status IN ('IN_PROGRESS','PROMISED')",
                    caseId);
            if (n > 0) insertActivity(caseId, "STATUS", null, "回款达标自动结清(SETTLED)", "case", caseId, null);
        }
    }

    /** 冲正后回款不足且当前 SETTLED → 退回 IN_PROGRESS、清 closed_at。 */
    private void maybeUnsettle(CaseScopeM4Service.CaseRow c, long caseId) {
        if (!"SETTLED".equals(c.status())) return;
        long target = c.reduceAfterCents() != null ? c.reduceAfterCents() : c.dueCents();
        long paid = sumActiveRepay(caseId);
        if (paid < target) {
            int n = jdbc.update(
                    "UPDATE \"case\" SET status = 'IN_PROGRESS', closed_at = NULL, updated_at = now()"
                            + " WHERE id = ? AND status = 'SETTLED'",
                    caseId);
            if (n > 0) insertActivity(caseId, "STATUS", null, "回款冲正后回退结清(IN_PROGRESS)", "case", caseId, null);
        }
    }

    private long sumActiveRepay(long caseId) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM repay_line WHERE case_id = ? AND reversed = false",
                Long.class, caseId);
        return sum == null ? 0L : sum;
    }

    // ── SMS 冷却 / TTL 配置 ───────────────────────────────────────────────────

    /** BR-M4-14a：同案最近一条 SMS 渠道 pay_link 创建时间在冷却窗口内 → 409 BIZ_SMS_COOLDOWN。 */
    private void requireSmsCooldownPassed(long caseId) {
        Timestamp last = jdbc.query(
                "SELECT max(created_at) AS t FROM pay_link WHERE case_id = ? AND channel = 'SMS'",
                rs -> rs.next() ? rs.getTimestamp("t") : null, caseId);
        if (last == null) return;
        long elapsed = Instant.now().getEpochSecond() - last.toInstant().getEpochSecond();
        if (elapsed < smsCooldownSeconds()) {
            throw new ApiException(BizError.BIZ_SMS_COOLDOWN, "同案缴费短信冷却未到，请稍后再发");
        }
    }

    private long payLinkTtlSeconds() {
        return smsSetting("payLinkTtlSeconds", DEFAULT_PAYLINK_TTL_SECONDS);
    }

    private long smsCooldownSeconds() {
        return smsSetting("cooldownSeconds", DEFAULT_SMS_COOLDOWN_SECONDS);
    }

    /** 读 settings.sms 域 JSON 字段（取最新版本），缺/异常退兜底——配置读不致 5xx。 */
    private long smsSetting(String key, long dflt) {
        try {
            Long v = jdbc.query(
                    "SELECT sms ->> ? AS v FROM settings WHERE domain = 'SMS' ORDER BY version DESC LIMIT 1",
                    rs -> {
                        if (!rs.next()) return null;
                        String raw = rs.getString("v");
                        if (raw == null || raw.isBlank()) return null;
                        try { return Long.valueOf(raw.trim()); } catch (NumberFormatException e) { return null; }
                    }, key);
            return v == null ? dflt : v;
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    /**
     * 支付申请单是否处于「未撤销」活动态（PENDING 或 PAID）。BLOCKER-1：契约 PaymentRequestStatus=
     *   {PENDING,PAID,VOIDED}；仅 VOIDED 视为已释放（且释放时 repay_line.payment_request_id 已置 NULL）。
     *   PENDING/PAID 均锁定该 line，冲正须先撤销该单。
     */
    private boolean isPaymentRequestActive(long paymentRequestId) {
        Integer n = jdbc.query(
                "SELECT count(*) FROM payment_request WHERE id = ? AND status IN ('PENDING','PAID')",
                rs -> rs.next() ? rs.getInt(1) : 0, paymentRequestId);
        return n != null && n > 0;
    }

    // ── 行锁加载（须在 @Transactional 内）────────────────────────────────────

    private record PayLinkRow(long id, long caseId, String status, String channel) {}
    private record ReductionRow(long id, long caseId, String state, long amountCents) {}
    private record RepayLineRow(long id, long caseId, Boolean reversed, Long paymentRequestId) {}

    private PayLinkRow lockPayLink(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, case_id, status, channel FROM pay_link WHERE id = ? FOR UPDATE",
                    (rs, i) -> new PayLinkRow(rs.getLong("id"), rs.getLong("case_id"),
                            rs.getString("status"), rs.getString("channel")),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "缴费链接不存在");
        }
    }

    private ReductionRow lockReduction(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, case_id, state, amount_cents FROM reduction WHERE id = ? FOR UPDATE",
                    (rs, i) -> new ReductionRow(rs.getLong("id"), rs.getLong("case_id"),
                            rs.getString("state"), rs.getLong("amount_cents")),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "减免记录不存在");
        }
    }

    private RepayLineRow lockRepayLine(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, case_id, reversed, payment_request_id FROM repay_line WHERE id = ? FOR UPDATE",
                    (rs, i) -> new RepayLineRow(rs.getLong("id"), rs.getLong("case_id"),
                            rs.getBoolean("reversed"), (Long) rs.getObject("payment_request_id")),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "回款明细不存在");
        }
    }

    private record TierRow(String discount, String decide) {}

    /** reduce_tier：批次覆盖优先（batch_id=case.batch_id），否则项目级（batch_id IS NULL）；按 id 序取第 idx 个（0-based）。 */
    private TierRow loadTierAt(long projectId, long batchId, int idx) {
        // 先查批次覆盖阶梯；为空则回落项目级。与契约/DDL「批次可覆盖项目」一致。
        java.util.List<TierRow> batchTiers = jdbc.query(
                "SELECT discount, decide FROM reduce_tier WHERE project_id = ? AND batch_id = ? ORDER BY id",
                (rs, i) -> new TierRow(rs.getString("discount"), rs.getString("decide")),
                projectId, batchId);
        java.util.List<TierRow> tiers = batchTiers.isEmpty()
                ? jdbc.query(
                        "SELECT discount, decide FROM reduce_tier WHERE project_id = ? AND batch_id IS NULL ORDER BY id",
                        (rs, i) -> new TierRow(rs.getString("discount"), rs.getString("decide")),
                        projectId)
                : batchTiers;
        if (idx >= tiers.size()) return null;
        return tiers.get(idx);
    }

    // ── activity 写入 ─────────────────────────────────────────────────────────

    private void insertActivity(long caseId, String type, Long actorId,
                                String content, String refType, Long refId, String method) {
        jdbc.update(
                "INSERT INTO activity(case_id, type, actor_id, content, ref_type, ref_id, method)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                caseId, type, actorId, content, refType, refId, method);
    }

    // ── 入参解析（非法一律 422 / id 非法 404）────────────────────────────────

    private static Map<String, Object> ok() {
        return Map.of("ok", true);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private Long actorId(CurrentSubject s) {
        try {
            return s.accountId() == null ? null : Long.valueOf(s.accountId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** /cases/{id} 路径 id 非法形态 → 404（避免存在性泄漏 / 防 5xx）。 */
    private static long parseCaseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在");
        }
    }

    private static long parsePayLinkId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "缴费链接不存在");
        }
    }

    private static long parseGenericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "资源不存在");
        }
    }

    /** channel 必填且 ∈ {SMS, WECHAT_COPY}，否则 422。 */
    private static String parseChannel(Map<String, Object> body) {
        Object v = body == null ? null : body.get("channel");
        String c = v == null ? null : String.valueOf(v).trim();
        if (c == null || c.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 channel");
        }
        if (!"SMS".equals(c) && !"WECHAT_COPY".equals(c)) {
            throw new ApiException(BizError.VALIDATION_422, "channel 非法（仅 SMS/WECHAT_COPY）");
        }
        return c;
    }

    /** repay channel 必填且 ∈ ChannelEnum，否则 422。 */
    private static String parseRepayChannel(Map<String, Object> body) {
        Object v = body == null ? null : body.get("channel");
        String c = v == null ? null : String.valueOf(v).trim();
        if (c == null || c.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 channel");
        }
        if (!"WECHAT_QR".equals(c) && !"BANK_TRANSFER".equals(c) && !"CASH".equals(c)) {
            throw new ApiException(BizError.VALIDATION_422, "channel 非法（仅 WECHAT_QR/BANK_TRANSFER/CASH）");
        }
        return c;
    }

    private static Integer parseRequiredInt(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 " + key);
        }
        try {
            if (v instanceof Number n) return n.intValue();
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, key + " 非法");
        }
    }

    private static Long parseRequiredLong(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 " + key);
        }
        return toLong(v, key);
    }

    private static Long parseOptionalLong(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        return toLong(v, key);
    }

    private static Long toLong(Object v, String key) {
        try {
            if (v instanceof Number n) return n.longValue();
            return Long.valueOf(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, key + " 非法");
        }
    }

    private static String parseRequiredString(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 " + key);
        }
        return String.valueOf(v).trim();
    }

    private static String parseOptionalString(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        String str = String.valueOf(v).trim();
        return str.isBlank() ? null : str;
    }

    private static LocalDate parseRequiredDate(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 " + key);
        }
        try {
            // 接受 date(yyyy-MM-dd) 或 date-time（取日期部分）。
            String str = String.valueOf(v).trim();
            return str.length() > 10 ? LocalDate.parse(str.substring(0, 10)) : LocalDate.parse(str);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, key + " 非法日期");
        }
    }
}
