package com.youzheng.huicui.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.FeeLineItemM9Dto;
import com.youzheng.huicui.web.dto.PaymentRequestM9Dto;
import com.youzheng.huicui.web.dto.VoucherM9Dto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M9 组1：支付申请单（IN/OUT 资金双线·最多审计模型）。横切层范式 + scaffold；JdbcTemplate 范式同
 * {@link PayReduceRepayM4Controller}。类名带 M9 后缀，与 M1-M4 controller 物理隔离，不碰共享件/其他组/pom。
 *
 * 端点（基路径 /v1 由 context-path 提供，注解写裸路径）：
 *   POST /payment-requests              createPaymentRequest | perm=payreq.create  | scope=own-org  | 幂等 | 201 / 403/404/409/422
 *   GET  /payment-requests              listPaymentRequests  | (scope=range·双线裁剪)             |     | 200 / 403
 *   GET  /payment-requests/{id}         getPaymentRequest    | (scope=range·双线裁剪)             |     | 200 / 403/404
 *   POST /payment-requests/{id}/send    sendPaymentRequest   | perm=payreq.create  | scope=range   | 幂等 | 200 / 403/404/409
 *   POST /payment-requests/{id}/revoke  revokePaymentRequest | perm=payreq.create  | scope=own-org | 幂等 | 200 / 403/404/409/422
 *   POST /payment-requests/{id}/complete completePaymentRequest| perm=payreq.complete| scope=platform | 幂等 | 200 / 403/404/409/422
 *
 * 【资金双线绑定 BR-M9-11/12（可验证鉴权）】
 *   IN=收佣（平台↔物业）：仅 PLATFORM 生成/撤销/完成；PROVIDER 访问 IN→403 BIZ_WRONG_SETTLE_SIDE。
 *   OUT=付佣（平台↔服务商）：仅 PROVIDER 且 batch.provider_id==orgId 生成/撤回；PROPERTY 访问 OUT→403。
 *   读端点双线裁剪：IN→PLATFORM + 物业(batch→project.org_id==orgId) 可见；OUT→PLATFORM + 本商可见。
 *   generated_by 由服务端从 SubjectContext 派生（IN=当前平台账号 / OUT=当前服务商账号），绝不接受前端 body。
 *   complete x-data-scope=platform：仅平台可完成（收/付双线均由平台落地支付动作）。
 *
 * 【状态机 PENDING→PAID / PENDING→VOIDED（version 乐观锁）】
 *   create→PENDING(version=1)；send 仅留痕不改 status；revoke→VOIDED 释放明细；complete→PAID 锁定明细+落凭证。
 *   乐观锁 UPDATE ... WHERE id=? AND version=? AND status='PENDING'，行数=0→并发冲突 409。
 *
 * 优雅降级（绝不 5xx）：路径 id 非法形态/不存在→404；越线/越 scope→403(PERM_403/BIZ_WRONG_SETTLE_SIDE)；
 *   状态不允许→409(STATE_409/BIZ_PR_PAID 用 STATE_409 承载/BIZ_LINE_LOCKED 用 STATE_409 承载)；
 *   缺必填/缺凭证→422(VALIDATION_422/BIZ_NO_VOUCHER)。每写操作落 audit_log（actor + before/after 快照）。
 *
 * 金额一律 *_cents（Long·分）原样返回；comm_rate 为 NUMERIC(6,4) 分数(0-1)，DB/后端/前端一致不×100。
 * 列名严格对齐 V1 DDL：payment_request / voucher / repay_line / batch / project / account / audit_log。
 */
@RestController
public class PaymentRequestM9Controller {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PaymentRequestM9Controller(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private static final String SIDE_IN = "IN";
    private static final String SIDE_OUT = "OUT";
    private static final String ST_PENDING = "PENDING";
    private static final String ST_PAID = "PAID";
    private static final String ST_VOIDED = "VOIDED";

    // ── [1] createPaymentRequest  POST /payment-requests ─────────────────────
    // 派生双线绑定 → FOR UPDATE 锁 lineIds 校验未结 → 固化 comm_rate → 算 base/comm → INSERT PENDING → 占位明细 → audit。
    @PostMapping("/payment-requests")
    @RequirePermission("payreq.create")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PaymentRequestM9Dto createPaymentRequest(@RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();

        String side = parseSide(body);                                   // 缺/非法→422
        long batchId = parseRequiredBatchId(body);                       // 缺/非数→422
        List<Long> lineIds = parseLineIds(body);                         // 空/缺/非数→422

        // 批次存在性（取双线比率快照与 provider_id/项目 org）。不存在→404。
        BatchRow batch = loadBatch(batchId);

        // 双线绑定：generated_by 服务端派生（绝不接受前端传）。
        long generatedBy = deriveGeneratorAndAssert(s, side, batch);     // 错线→403 BIZ_WRONG_SETTLE_SIDE

        // 固化比率（防 H-02 历史失真）：IN=batch.comm_in_rate；OUT=batch.pay_out_rate（开放抢单则 open_rate 兜底）。
        BigDecimal commRate = resolveCommRate(side, batch);              // 缺率→422

        // 事务内逐笔 FOR UPDATE 行锁校验未结（BR-M9-12a 手动组单）。
        // BLOCKER-2·OUT 付佣按到账归属快照：OUT 线要求所选 line 全部属本商快照(provider_id_at_repay==orgId)，
        //   与已修的 Recon OUT 汇总口径一致（不按 batch.provider_id——单案再派后到账归属不漂移）。IN 线不受此约束。
        Long providerSnapshot = SIDE_OUT.equals(side) ? orgIdLong(s) : null;
        List<LineRow> lines = lockAndValidateLines(lineIds, batchId, providerSnapshot); // 不存在→404/已占→409/越权(批次/快照不符)→403

        // 佣金算法（BR-M9-01b/02·逐笔×比率·分数，HALF_UP）。base=Σamount；comm=Σ round(amount×rate)。
        long baseCents = 0L;
        long commCents = 0L;
        List<PaymentRequestM9Dto.LineSnapshot> snap = new ArrayList<>();
        for (LineRow ln : lines) {
            long lineComm = roundComm(ln.amountCents, commRate);
            baseCents += ln.amountCents;
            commCents += lineComm;
            snap.add(new PaymentRequestM9Dto.LineSnapshot(
                    String.valueOf(ln.id), String.valueOf(ln.caseId),
                    ln.ownerName, ln.room, ln.amountCents, lineComm));
        }

        // 单号 PR-{side}-{batchId}-{seq}：seq=该批次该线已有单数+1（事务内计数，UNIQUE no 兜底并发）。
        long seq = nextSeq(batchId, side);
        String no = "PR-" + side + "-" + batchId + "-" + seq;
        String linesJson = writeJson(snap);

        Long prId = jdbc.queryForObject(
                "INSERT INTO payment_request(no, side, batch_id, generated_by, comm_rate, lines, base_cents, comm_cents, status, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'PENDING', 1) RETURNING id",
                Long.class, no, side, batchId, generatedBy, commRate, linesJson, baseCents, commCents);

        // 占位锁定明细：PENDING 阶段仅绑定 payment_request_id，settled 仍 FALSE（PAID 才置 TRUE）。
        for (LineRow ln : lines) {
            jdbc.update("UPDATE repay_line SET payment_request_id = ?, updated_at = now() WHERE id = ?", prId, ln.id);
        }

        audit(s, "payreq.create", prId, batchId, side,
                null, Map.of("status", ST_PENDING, "baseCents", baseCents, "commCents", commCents, "lineCount", lines.size()),
                null);

        return loadDto(prId);
    }

    // ── [2] listPaymentRequests  GET /payment-requests ───────────────────────
    // x-data-scope=range·双线裁剪：side 必填；status/batchId 可选过滤；分页。跨线→403。
    @GetMapping("/payment-requests")
    public Page<PaymentRequestM9Dto> listPaymentRequests(
            @RequestParam(required = false) String side,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        String sd = parseSideParam(side);                 // 必填·缺/非法→422
        assertSideVisible(s, sd);                          // 跨线主体→403 BIZ_WRONG_SETTLE_SIDE
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE pr.side = ?");
        List<Object> args = new ArrayList<>();
        args.add(sd);
        if (status != null && !status.isBlank()) {
            validateStatus(status);
            where.append(" AND pr.status = ?");
            args.add(status);
        }
        if (batchId != null && !batchId.isBlank()) {
            where.append(" AND pr.batch_id = ?");
            args.add(parseLongOr422(batchId, "batchId"));
        }
        appendOrgScope(s, sd, where, args);                // 物业/服务商组织级裁剪

        String base = "FROM payment_request pr"
                + " JOIN batch b ON b.id = pr.batch_id"
                + " JOIN project p ON p.id = b.project_id"
                + where;

        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<PaymentRequestM9Dto> items = jdbc.query(
                "SELECT pr.* " + base + " ORDER BY pr.id DESC LIMIT ? OFFSET ?",
                prRowMapper(), pageArgs.toArray());

        // 列表项不下钻 voucher（PAID 才有；下钻见 getPaymentRequest），feeLines 列表项也置空，控制载荷。
        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [3] getPaymentRequest  GET /payment-requests/{id} ────────────────────
    // 双线裁剪同 list；lines 下钻 + feeLines(IN 含/OUT 空·占位) + voucher(PAID 必有)。404/403。
    @GetMapping("/payment-requests/{id}")
    public PaymentRequestM9Dto getPaymentRequest(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long prId = parsePrId(id);
        PrCore core = loadCore(prId);                      // 不存在→404
        assertSideVisible(s, core.side);                   // 跨线主体→403
        if (!visibleByScope(s, core.side, prId)) {         // 单据级越 scope→403（按 pr.id 精确复核）
            throw new ApiException(BizError.PERM_403, "无权查看该支付申请单");
        }
        return loadDto(prId);
    }

    // ── [4] sendPaymentRequest  POST /payment-requests/{id}/send ─────────────
    // 仅 PENDING 可发（已 PAID/VOIDED→409）；仅生成方线别（错线→403）；仅留痕写 audit，不改 status。
    @PostMapping("/payment-requests/{id}/send")
    @RequirePermission("payreq.create")
    @Transactional
    public Map<String, Object> sendPaymentRequest(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long prId = parsePrId(id);
        PrRow pr = lockPr(prId);                            // 不存在→404
        BatchRow batch = loadBatch(pr.batchId);
        assertOperatorSide(s, pr, batch);                   // 错线→403；OUT 按到账归属快照复核归属
        if (!ST_PENDING.equals(pr.status)) {
            throw new ApiException(BizError.STATE_409, "仅待付(PENDING)单可发送，当前状态: " + pr.status);
        }
        audit(s, "payreq.send", prId, pr.batchId, pr.side,
                Map.of("status", pr.status), Map.of("status", pr.status, "sent", true), null);
        return ok();
    }

    // ── [5] revokePaymentRequest  POST /payment-requests/{id}/revoke ─────────
    // 仅 PENDING（已 PAID→409 视为 BIZ_PR_PAID 语义，用 STATE_409 承载）；仅生成方线别（错线→403）；
    //   version 乐观锁（缺/不匹配→422）；UPDATE→VOIDED 释放明细 payment_request_id=NULL,settled=FALSE。
    @PostMapping("/payment-requests/{id}/revoke")
    @RequirePermission("payreq.create")
    @Transactional
    public Map<String, Object> revokePaymentRequest(@PathVariable("id") String id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long prId = parsePrId(id);
        String reason = parseRequiredString(body, "reason");   // 缺→422
        int version = parseRequiredVersion(body);              // 缺/非整→422

        PrRow pr = lockPr(prId);                               // 不存在→404
        BatchRow batch = loadBatch(pr.batchId);
        assertOperatorSide(s, pr, batch);                      // 错线→403；OUT 按到账归属快照复核归属

        if (ST_PAID.equals(pr.status)) {
            throw new ApiException(BizError.STATE_409, "已支付单不可撤销(BIZ_PR_PAID)，须走冲正流程");
        }
        if (!ST_PENDING.equals(pr.status)) {
            throw new ApiException(BizError.STATE_409, "仅待付(PENDING)单可撤销，当前状态: " + pr.status);
        }
        if (version != pr.version) {
            throw new ApiException(BizError.VALIDATION_422,
                    "version 不匹配（乐观锁），当前 version=" + pr.version);
        }

        int rows = jdbc.update(
                "UPDATE payment_request SET status = 'VOIDED', voided_at = now(), void_reason = ?, version = version + 1, updated_at = now()"
                        + " WHERE id = ? AND version = ? AND status = 'PENDING'",
                reason, prId, version);
        if (rows == 0) {
            throw new ApiException(BizError.STATE_409, "并发冲突：单状态/版本已变更，撤销失败");
        }
        // 释放明细：解绑 + 退结算占位。
        jdbc.update(
                "UPDATE repay_line SET payment_request_id = NULL, settled = FALSE, updated_at = now() WHERE payment_request_id = ?",
                prId);

        audit(s, "payreq.revoke", prId, pr.batchId, pr.side,
                Map.of("status", ST_PENDING, "version", pr.version),
                Map.of("status", ST_VOIDED, "version", pr.version + 1), reason);
        return ok();
    }

    // ── [6] completePaymentRequest  POST /payment-requests/{id}/complete ─────
    // x-data-scope=platform（仅平台）；version 乐观锁；voucher 缺/fileUrl 空→422 BIZ_NO_VOUCHER；
    //   type 须匹配线别(IN→RECEIPT/OUT→PAYMENT)；UPDATE→PAID + INSERT voucher + 锁定明细 settled=TRUE。
    @PostMapping("/payment-requests/{id}/complete")
    @RequirePermission("payreq.complete")
    @Transactional
    public PaymentRequestM9Dto completePaymentRequest(@PathVariable("id") String id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        // x-data-scope=platform：仅平台主体可完成（service 层强制，即使 perm 通过）。
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台可完成支付申请单");
        }
        long prId = parsePrId(id);
        int version = parseRequiredVersion(body);              // 缺/非整→422

        // voucher 兜底校验（即使 schema required）：缺 / fileUrl 空 → 422 BIZ_NO_VOUCHER。
        VoucherInput v = parseVoucher(body);

        PrRow pr = lockPr(prId);                               // 不存在→404
        // BLOCKER-3·complete 是资金落地，SE 不得越权完成任意单：锁 PR 后 SA 放行，
        //   SE 按 data_range 对该单精确复核（该 pr 绑定 lines 的 provider/property 须落 SE 范围内），越范围→403。
        assertCompleteScope(s, pr);
        if (ST_PAID.equals(pr.status)) {
            throw new ApiException(BizError.STATE_409, "单已完成(BIZ_PR_PAID)，不可重复完成");
        }
        if (!ST_PENDING.equals(pr.status)) {
            throw new ApiException(BizError.STATE_409, "仅待付(PENDING)单可完成，当前状态: " + pr.status);
        }
        // 凭证类型须匹配线别：IN→RECEIPT(收款凭证) / OUT→PAYMENT(支付凭证)。
        String expectType = SIDE_IN.equals(pr.side) ? "RECEIPT" : "PAYMENT";
        if (!expectType.equals(v.type)) {
            throw new ApiException(BizError.VALIDATION_422,
                    "凭证类型与线别不符：" + pr.side + " 须为 " + expectType);
        }
        if (version != pr.version) {
            throw new ApiException(BizError.VALIDATION_422,
                    "version 不匹配（乐观锁），当前 version=" + pr.version);
        }
        // BLOCKER-1·冲正时序（complete 侧防护）：锁定该单绑定 lines 并校验全部 reversed=false。
        //   若有已冲正 line（绕过 reverse 侧防护或时序竞态注入）→ 409，不可把含已冲正明细的单结算为 PAID。
        assertNoReversedLines(pr.id);

        Long actorId = actorId(s);
        int rows = jdbc.update(
                "UPDATE payment_request SET status = 'PAID', completed_by = ?, completed_at = now(), version = version + 1, updated_at = now()"
                        + " WHERE id = ? AND version = ? AND status = 'PENDING'",
                actorId, prId, version);
        if (rows == 0) {
            throw new ApiException(BizError.STATE_409, "并发冲突：单状态/版本已变更，完成失败");
        }
        // 落凭证（uploaded_by 服务端派生·不接受前端传；voucher 每单 UNIQUE）。
        jdbc.update(
                "INSERT INTO voucher(payment_request_id, type, file_url, uploaded_by, uploaded_at)"
                        + " VALUES (?, ?, ?, ?, now())",
                prId, v.type, v.fileUrl, actorId);
        // 锁定明细：PAID 才置 settled=TRUE（平台双线专属语义）。
        jdbc.update(
                "UPDATE repay_line SET settled = TRUE, updated_at = now() WHERE payment_request_id = ?",
                prId);

        audit(s, "payreq.complete", prId, pr.batchId, pr.side,
                Map.of("status", ST_PENDING, "version", pr.version),
                Map.of("status", ST_PAID, "version", pr.version + 1, "voucherType", v.type), null);

        return loadDto(prId);
    }

    // ════════════════════════════ 双线绑定 / 可见性 ═══════════════════════════

    /** create 派生 generated_by 并断言生成方线别合法。IN→平台账号；OUT→服务商账号(且本商 batch)。 */
    private long deriveGeneratorAndAssert(CurrentSubject s, String side, BatchRow batch) {
        assertGeneratorSide(s, side, batch);
        Long id = actorId(s);
        if (id == null) throw new ApiException(BizError.AUTH_401, "无效主体账号");
        return id;
    }

    /**
     * 生成方线别校验（create 用；send/revoke 走 assertOperatorSide）。
     * IN 须平台。OUT（BLOCKER-2）：只校验主体 orgType=PROVIDER——归属完全交给逐 line 的
     *   provider_id_at_repay 快照校验（lockAndValidateLines 已做），不再用 batch.provider_id 预门，
     *   否则单案再派后 provider_id_at_repay 属新服务商但 batch.provider_id 仍旧 → 新服务商建不了单。
     */
    private void assertGeneratorSide(CurrentSubject s, String side, BatchRow batch) {
        if (SIDE_IN.equals(side)) {
            if (!s.isPlatform()) {
                throw new ApiException(BizError.BIZ_WRONG_SETTLE_SIDE, "收佣线(IN)仅平台可生成/操作");
            }
        } else { // OUT
            if (!"PROVIDER".equals(s.orgType()) || orgIdLong(s) == null) {
                throw new ApiException(BizError.BIZ_WRONG_SETTLE_SIDE, "付佣线(OUT)仅承接服务商可生成/操作");
            }
        }
    }

    /**
     * 既有单的写操作方校验（send/revoke）。IN：仅平台（同 generatorSide）。
     * OUT（BLOCKER-2·到账归属快照）：须 PROVIDER 且该单含本商快照(provider_id_at_repay==orgId)绑定明细，
     *   不再按 batch.provider_id——单案再派后到账归属不漂移。错线/越权→403。
     */
    private void assertOperatorSide(CurrentSubject s, PrRow pr, BatchRow batch) {
        if (SIDE_IN.equals(pr.side)) {
            assertGeneratorSide(s, pr.side, batch);
            return;
        }
        // OUT
        Long orgId = orgIdLong(s);
        if (!"PROVIDER".equals(s.orgType()) || orgId == null) {
            throw new ApiException(BizError.BIZ_WRONG_SETTLE_SIDE, "付佣线(OUT)仅承接服务商可操作");
        }
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM repay_line rl"
                        + " WHERE rl.payment_request_id = ? AND rl.provider_id_at_repay = ?",
                Long.class, pr.id, orgId);
        if (n == null || n == 0) {
            throw new ApiException(BizError.PERM_403, "付佣单到账归属非本服务商，无权操作");
        }
    }

    /** 读端点：主体线别可见性（与生成方区分——读侧 IN 物业可见、OUT 服务商可见）。跨线主体→403。 */
    private void assertSideVisible(CurrentSubject s, String side) {
        if (s.isPlatform()) return;                            // 平台双线皆可见
        if (SIDE_IN.equals(side)) {
            // IN：PROPERTY 可见、PROVIDER 不可见。
            if ("PROVIDER".equals(s.orgType())) {
                throw new ApiException(BizError.BIZ_WRONG_SETTLE_SIDE, "服务商无权访问收佣线(IN)");
            }
        } else { // OUT
            // OUT：PROVIDER 可见、PROPERTY 不可见（物业↔服务商无任何资金接口）。
            if (!"PROVIDER".equals(s.orgType())) {
                throw new ApiException(BizError.BIZ_WRONG_SETTLE_SIDE, "物业无权访问付佣线(OUT)");
            }
        }
    }

    /** 读端点组织级 scope（在 assertSideVisible 之后，复核单据归属本组织）。 */
    private void appendOrgScope(CurrentSubject s, String side, StringBuilder where, List<Object> args) {
        if (s.isPlatform()) {
            // SA 全量；SE 按 data_range 三维裁剪（B-01）。areas(p.area)/properties(p.org_id) 走批次→项目维；
            // HIGH-1·providers 维不能 fail-open：PR 为 batch 维但其绑定 repay_line 持有 provider_id_at_repay 到账快照，
            //   故 providers 维基于「绑定 line 的 provider 快照 ∈ SE.providers」做 EXISTS 复核（与 complete/OUT 口径一致），
            //   否则只配 providers 的 SE 可读全量 PR（越范围）。providerCol 仍传 null（PR/project 无 provider 列）。
            com.youzheng.huicui.common.DataScope.appendRange(
                    s, where, args, null, "p.org_id", "p.area", null, null);
            appendSeProviderSnapshotScope(s, where, args);
            return;
        }
        // 催收员(CO)不见组织级支付申请单(US-M9-09 本人佣金只读,走 /me/settlement)→裁剪为空。
        if ("CO".equals(s.role())) { where.append(" AND 1 = 0"); return; }
        if (SIDE_IN.equals(side)) {                            // 物业：按 batch→project.org_id
            where.append(" AND p.org_id = ?");
            args.add(orgIdLong(s));
        } else {                                               // 服务商：OUT 付佣按到账归属快照
            // BLOCKER-2·OUT 可见性按快照：单据须含至少一条本商快照(provider_id_at_repay==orgId)绑定明细，
            //   与 Recon OUT 汇总 / create OUT 组单口径一致（不按 batch.provider_id——再派后归属不漂移）。
            where.append(" AND EXISTS (SELECT 1 FROM repay_line rl"
                    + " WHERE rl.payment_request_id = pr.id AND rl.provider_id_at_repay = ?)");
            args.add(orgIdLong(s));
        }
    }

    /**
     * HIGH-1·SE providers 维基于绑定 line 的到账归属快照复核：仅当 SE data_range 配了 providers 维时追加。
     *   要求该 PR 至少存在一条绑定 repay_line，其 provider_id_at_repay ∈ SE.providers（EXISTS 口径）。
     *   未配 providers / UNRESTRICTED → 不追加（areas/properties 维仍由 appendRange 生效）；
     *   RESTRICTED_EMPTY（fail-closed）→ appendRange 已置 1=0，此处不再叠加。
     */
    private void appendSeProviderSnapshotScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        if (!s.isSE()) return;
        com.youzheng.huicui.security.DataRange r = s.dataRange();
        if (r == null || r.isUnrestricted() || r.isRestrictedEmpty()) return;
        if (!r.hasProviders()) return;                         // 未配 providers 维 → 该维不裁剪
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < r.providers().size(); i++) {
            if (i > 0) in.append(',');
            in.append('?');
        }
        where.append(" AND EXISTS (SELECT 1 FROM repay_line rl"
                + " WHERE rl.payment_request_id = pr.id AND rl.provider_id_at_repay IN (").append(in).append("))");
        args.addAll(r.providers());
    }

    /**
     * 详情可见性（BLOCKER-1·按 pr.id 精确复核当前单据归属，不用 batchId 代替单据 id）：
     *   旧实现按 pr.batch_id 数同批单，OUT 同批多服务商时一个服务商只要同批有自己的单就能读别人的 PR 详情。
     *   改：固定 pr.id = ?，OUT 须该 pr 绑定 lines 的 provider_id_at_repay 含本商（appendOrgScope 的 EXISTS
     *   子查询已锚定 rl.payment_request_id = pr.id）。SA 全量直通；SE 仍须过 data_range 裁剪（B-01）。
     */
    private boolean visibleByScope(CurrentSubject s, String side, long prId) {
        if (s.isPlatform() && !s.isSE()) return true;          // SA 全量；SE 落入下方 data_range 裁剪
        StringBuilder where = new StringBuilder(" WHERE pr.id = ?");
        List<Object> args = new ArrayList<>();
        args.add(prId);
        appendOrgScope(s, side, where, args);
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM payment_request pr"
                        + " JOIN batch b ON b.id = pr.batch_id"
                        + " JOIN project p ON p.id = b.project_id" + where,
                Long.class, args.toArray());
        return n != null && n > 0;
    }

    /**
     * BLOCKER-1·complete 前锁定并校验绑定 lines 全部未冲正。FOR UPDATE 锁住绑定行（与 reverse 侧
     *   lockRepayLine 互斥），存在任一 reversed=true → 409（不可完成含已冲正明细的单）。
     */
    private void assertNoReversedLines(long prId) {
        // 先 FOR UPDATE 锁住绑定 lines（与 reverse 侧 lockRepayLine 互斥；PG 不允许 FOR UPDATE 用于聚合/子查询，
        //   故先取被锁行的 reversed 标志，再在内存判定）。
        List<Boolean> reversedFlags = jdbc.query(
                "SELECT rl.reversed FROM repay_line rl WHERE rl.payment_request_id = ? FOR UPDATE OF rl",
                (rs, i) -> rs.getBoolean("reversed"),
                prId);
        for (Boolean reversed : reversedFlags) {
            if (Boolean.TRUE.equals(reversed)) {
                throw new ApiException(BizError.STATE_409,
                        "支付申请单含已冲正明细，不可完成（须先撤销该单并重新组单）");
            }
        }
    }

    /**
     * complete 资金落地的范围复核（BLOCKER-3）：仅平台可完成（外层已挡非平台）。
     *   SA → 放行；SE → 该 pr 绑定 lines 的 provider/property/area 须全部落 SE data_range 内，
     *   任一条越范围 → 403。复核口径：以 SE data_range 裁剪绑定 lines 的命中数须等于总绑定数。
     *   provider 维按 OUT 到账归属快照 rl.provider_id_at_repay；物业/区域按 batch→project。
     */
    private void assertCompleteScope(CurrentSubject s, PrRow pr) {
        if (!s.isSE()) return;                                 // SA（及其它平台角色）全量放行
        if (s.dataRange() != null && s.dataRange().isRestrictedEmpty()) {
            throw new ApiException(BizError.PERM_403, "数据范围非法（fail-closed），无权完成该支付申请单");
        }
        if (s.dataRange() == null || s.dataRange().isUnrestricted()) return;  // SE 未收窄=全平台

        String fromJoin = "FROM repay_line rl"
                + " JOIN batch b ON b.id = rl.batch_id"
                + " JOIN project p ON p.id = b.project_id"
                + " WHERE rl.payment_request_id = ?";
        List<Object> totalArgs = new ArrayList<>();
        totalArgs.add(pr.id);
        Long bound = jdbc.queryForObject("SELECT count(*) " + fromJoin, Long.class, totalArgs.toArray());
        long boundN = bound == null ? 0L : bound;

        // 在绑定 lines 上叠加 SE data_range 裁剪；in-range 命中数 < 总绑定数 ⇒ 有 line 越范围 → 403。
        StringBuilder where = new StringBuilder(" WHERE rl.payment_request_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(pr.id);
        com.youzheng.huicui.common.DataScope.appendRange(
                s, where, args, "rl.provider_id_at_repay", "p.org_id", "p.area", "b.project_id", "rl.batch_id");
        Long inRange = jdbc.queryForObject(
                "SELECT count(*) FROM repay_line rl"
                        + " JOIN batch b ON b.id = rl.batch_id"
                        + " JOIN project p ON p.id = b.project_id" + where,
                Long.class, args.toArray());
        long inRangeN = inRange == null ? 0L : inRange;
        if (inRangeN < boundN) {
            throw new ApiException(BizError.PERM_403, "支付申请单超出本人数据范围，无权完成");
        }
    }

    // ════════════════════════════ 比率 / 佣金 ════════════════════════════════

    /** 固化生效比率：IN=comm_in_rate；OUT=pay_out_rate（开放抢单则 open_rate 兜底）。缺率→422。 */
    private BigDecimal resolveCommRate(String side, BatchRow batch) {
        BigDecimal rate;
        if (SIDE_IN.equals(side)) {
            rate = batch.commInRate;
        } else {
            rate = batch.payOutRate != null ? batch.payOutRate : batch.openRate;
        }
        if (rate == null) {
            throw new ApiException(BizError.VALIDATION_422, "批次未设置本线生效比率，无法组单");
        }
        return rate;
    }

    /** commCents = round(amount_cents × rate)，rate 为分数(0-1)，HALF_UP setScale(0)。 */
    private static long roundComm(long amountCents, BigDecimal rate) {
        return BigDecimal.valueOf(amountCents)
                .multiply(rate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    // ════════════════════════════ 明细行锁 / 组单 ════════════════════════════

    private record LineRow(long id, long caseId, String ownerName, String room, long amountCents) {}

    /**
     * 逐笔 FOR UPDATE 行锁校验未结（BR-M9-12a）：每个 lineId 要求
     *  (a) 属于 batchId 且未 reversed；(b) payment_request_id IS NULL & settled=FALSE；
     *  (c) OUT 线（providerSnapshot 非空）须 provider_id_at_repay==本商快照（BLOCKER-2·到账归属）。
     * 不存在→404；批次/快照不符（越权占用别批/别商明细）→403；已占（结算/已纳入其他单/已冲正）→409 BIZ_LINE_LOCKED(用 STATE_409 承载)。
     */
    private List<LineRow> lockAndValidateLines(List<Long> lineIds, long batchId, Long providerSnapshot) {
        List<LineRow> out = new ArrayList<>();
        for (Long lineId : lineIds) {
            RepayLineLock rl;
            try {
                rl = jdbc.queryForObject(
                        "SELECT rl.id, rl.case_id, rl.batch_id, rl.amount_cents, rl.settled, rl.reversed, rl.payment_request_id,"
                                + " rl.provider_id_at_repay, c.owner_name, c.room"
                                + " FROM repay_line rl JOIN \"case\" c ON c.id = rl.case_id"
                                + " WHERE rl.id = ? FOR UPDATE OF rl",
                        (rs, i) -> new RepayLineLock(
                                rs.getLong("id"), rs.getLong("case_id"), rs.getLong("batch_id"),
                                rs.getLong("amount_cents"), rs.getBoolean("settled"), rs.getBoolean("reversed"),
                                (Long) rs.getObject("payment_request_id"),
                                (Long) rs.getObject("provider_id_at_repay"),
                                rs.getString("owner_name"), rs.getString("room")),
                        lineId);
            } catch (EmptyResultDataAccessException e) {
                throw new ApiException(BizError.NOT_FOUND_404, "回款明细不存在: " + lineId);
            }
            if (rl.batchId != batchId) {
                throw new ApiException(BizError.PERM_403, "明细不属于该批次: " + lineId);
            }
            // OUT 付佣按到账归属快照：所选 line 须全部属本商（provider_id_at_repay==orgId）。
            if (providerSnapshot != null
                    && (rl.providerIdAtRepay == null || !rl.providerIdAtRepay.equals(providerSnapshot))) {
                throw new ApiException(BizError.PERM_403, "明细到账归属非本服务商，不可组单: " + lineId);
            }
            if (rl.reversed) {
                throw new ApiException(BizError.STATE_409, "明细已冲正不可组单: " + lineId);
            }
            if (rl.paymentRequestId != null || rl.settled) {
                throw new ApiException(BizError.STATE_409, "明细已结算/已纳入其他单(BIZ_LINE_LOCKED): " + lineId);
            }
            out.add(new LineRow(rl.id, rl.caseId, rl.ownerName, rl.room, rl.amountCents));
        }
        return out;
    }

    private record RepayLineLock(long id, long caseId, long batchId, long amountCents,
                                 boolean settled, boolean reversed, Long paymentRequestId,
                                 Long providerIdAtRepay, String ownerName, String room) {}

    private long nextSeq(long batchId, String side) {
        Long cnt = jdbc.queryForObject(
                "SELECT count(*) FROM payment_request WHERE batch_id = ? AND side = ?",
                Long.class, batchId, side);
        return (cnt == null ? 0L : cnt) + 1L;
    }

    // ════════════════════════════ 批次 / PR 加载 ═════════════════════════════

    private record BatchRow(long id, Long providerId, long projectId,
                            BigDecimal commInRate, BigDecimal payOutRate, BigDecimal openRate) {}

    private BatchRow loadBatch(long batchId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, provider_id, project_id, comm_in_rate, pay_out_rate, open_rate FROM batch WHERE id = ?",
                    (rs, i) -> new BatchRow(rs.getLong("id"), (Long) rs.getObject("provider_id"),
                            rs.getLong("project_id"), rs.getBigDecimal("comm_in_rate"),
                            rs.getBigDecimal("pay_out_rate"), rs.getBigDecimal("open_rate")),
                    batchId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "批次不存在: " + batchId);
        }
    }

    private record PrRow(long id, String side, long batchId, String status, int version) {}

    /** 行锁加载（须在 @Transactional 内）。不存在→404。 */
    private PrRow lockPr(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, side, batch_id, status, version FROM payment_request WHERE id = ? FOR UPDATE",
                    (rs, i) -> new PrRow(rs.getLong("id"), rs.getString("side"),
                            rs.getLong("batch_id"), rs.getString("status"), rs.getInt("version")),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "支付申请单不存在");
        }
    }

    private record PrCore(long id, String side, long batchId) {}

    private PrCore loadCore(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, side, batch_id FROM payment_request WHERE id = ?",
                    (rs, i) -> new PrCore(rs.getLong("id"), rs.getString("side"), rs.getLong("batch_id")),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "支付申请单不存在");
        }
    }

    /** 完整 DTO 加载（含 voucher 下钻 + feeLines 占位）。 */
    private PaymentRequestM9Dto loadDto(long id) {
        PaymentRequestM9Dto bare = jdbc.queryForObject(
                "SELECT * FROM payment_request WHERE id = ?", prRowMapper(), id);
        VoucherM9Dto voucher = jdbc.query(
                "SELECT type, file_url, uploaded_by, uploaded_at FROM voucher WHERE payment_request_id = ?",
                rs -> rs.next() ? new VoucherM9Dto(
                        rs.getString("type"), rs.getString("file_url"),
                        idOrNull(rs, "uploaded_by"), ts(rs.getTimestamp("uploaded_at"))) : null,
                id);
        // feeLines：仅 IN 线含存证/法律按次计费；地基期先返回空列表占位（OUT 线本就空）。TODO(M9-fee)。
        List<FeeLineItemM9Dto> feeLines = List.of();
        return new PaymentRequestM9Dto(
                bare.id(), bare.code(), bare.side(), bare.batchId(), bare.generatedBy(),
                bare.commRate(), bare.lines(), feeLines, bare.baseCents(), bare.commCents(),
                bare.status(), voucher, bare.documentUrl(), bare.sealed(),
                bare.createdBy(), bare.createdAt(), bare.completedBy(), bare.completedAt(),
                bare.voidedAt(), bare.voidReason(), bare.version());
    }

    /** payment_request 行 → DTO（不含 voucher/feeLines；列名映射）。 */
    private RowMapper<PaymentRequestM9Dto> prRowMapper() {
        return (rs, i) -> {
            String generatedBy = idOrNull(rs, "generated_by");
            return new PaymentRequestM9Dto(
                    String.valueOf(rs.getLong("id")),
                    rs.getString("no"),
                    rs.getString("side"),
                    String.valueOf(rs.getLong("batch_id")),
                    generatedBy,
                    rs.getBigDecimal("comm_rate"),
                    parseLines(rs.getString("lines")),
                    List.of(),
                    longOrNull(rs, "base_cents"),
                    longOrNull(rs, "comm_cents"),
                    rs.getString("status"),
                    null,
                    null,                                  // documentUrl 占位
                    false,                                 // sealed 占位
                    generatedBy,                           // createdBy = generated_by 展示
                    ts(rs.getTimestamp("created_at")),
                    idOrNull(rs, "completed_by"),
                    ts(rs.getTimestamp("completed_at")),
                    ts(rs.getTimestamp("voided_at")),
                    rs.getString("void_reason"),
                    rs.getInt("version"));
        };
    }

    // ════════════════════════════ audit_log 写入 ═════════════════════════════

    /** 写 audit_log（最多审计模型）：actor + action + target(payment_request) + before/after JSON 快照。 */
    private void audit(CurrentSubject s, String action, long prId, long batchId, String side,
                       Map<String, Object> before, Map<String, Object> after, String reason) {
        jdbc.update(
                "INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope, before_snap, after_snap, reason, trace_id)"
                        + " VALUES (?, ?, ?, ?, 'payment_request', ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                actorId(s), nz(s.name()), action,
                "PR#" + prId + " side=" + side + " batch=" + batchId,
                String.valueOf(prId), s.orgType(),
                before == null ? null : writeJson(before),
                after == null ? null : writeJson(after),
                reason, org.slf4j.MDC.get("traceId"));
    }

    // ════════════════════════════ 入参解析（非法→422 / id→404）═════════════════

    private static Map<String, Object> ok() { return Map.of("ok", true); }

    private static String nz(String s) { return s == null ? "" : s; }

    private Long actorId(CurrentSubject s) {
        try {
            return s.accountId() == null ? null : Long.valueOf(s.accountId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Long orgIdLong(CurrentSubject s) {
        try {
            return s.orgId() == null ? null : Long.valueOf(s.orgId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long parsePrId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "支付申请单不存在");
        }
    }

    /** body.side 必填且 ∈ {IN,OUT}，否则 422。 */
    private static String parseSide(Map<String, Object> body) {
        Object v = body == null ? null : body.get("side");
        String sd = v == null ? null : String.valueOf(v).trim();
        if (sd == null || sd.isBlank()) throw new ApiException(BizError.VALIDATION_422, "缺少 side");
        if (!SIDE_IN.equals(sd) && !SIDE_OUT.equals(sd)) {
            throw new ApiException(BizError.VALIDATION_422, "side 非法（仅 IN/OUT）");
        }
        return sd;
    }

    /** 查询参 side 必填（read 端点）且 ∈ {IN,OUT}，否则 422。 */
    private static String parseSideParam(String side) {
        if (side == null || side.isBlank()) throw new ApiException(BizError.VALIDATION_422, "缺少 side");
        String sd = side.trim();
        if (!SIDE_IN.equals(sd) && !SIDE_OUT.equals(sd)) {
            throw new ApiException(BizError.VALIDATION_422, "side 非法（仅 IN/OUT）");
        }
        return sd;
    }

    private static void validateStatus(String status) {
        if (!ST_PENDING.equals(status) && !ST_PAID.equals(status) && !ST_VOIDED.equals(status)) {
            throw new ApiException(BizError.VALIDATION_422, "status 非法（仅 PENDING/PAID/VOIDED）");
        }
    }

    private static long parseRequiredBatchId(Map<String, Object> body) {
        Object v = body == null ? null : body.get("batchId");
        if (v == null || String.valueOf(v).isBlank()) throw new ApiException(BizError.VALIDATION_422, "缺少 batchId");
        try {
            if (v instanceof Number n) return n.longValue();
            return Long.parseLong(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, "batchId 非法");
        }
    }

    /** lineIds 必填非空数组（手动组单）；任一非数→422；空→422。 */
    private List<Long> parseLineIds(Map<String, Object> body) {
        Object v = body == null ? null : body.get("lineIds");
        if (!(v instanceof List<?> raw) || raw.isEmpty()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 lineIds（至少勾选一条未结明细）");
        }
        List<Long> ids = new ArrayList<>();
        // BLOCKER-2·拒重复 lineId：同一回款行重复传入会被 base/comm 重复计费，却只锁一条明细（资金虚增）。
        //   解析阶段用 Set 去重检测，任一重复 → 422，绝不静默去重。
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Object o : raw) {
            if (o == null) throw new ApiException(BizError.VALIDATION_422, "lineIds 含空值");
            long id;
            try {
                if (o instanceof Number n) id = n.longValue();
                else id = Long.parseLong(String.valueOf(o).trim());
            } catch (RuntimeException e) {
                throw new ApiException(BizError.VALIDATION_422, "lineIds 含非法 id: " + o);
            }
            if (!seen.add(id)) {
                throw new ApiException(BizError.VALIDATION_422, "lineIds 含重复 id: " + id);
            }
            ids.add(id);
        }
        return ids;
    }

    private static long parseLongOr422(String v, String field) {
        try {
            return Long.parseLong(v.trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, field + " 非法");
        }
    }

    private static int parseRequiredVersion(Map<String, Object> body) {
        Object v = body == null ? null : body.get("version");
        if (v == null || String.valueOf(v).isBlank()) throw new ApiException(BizError.VALIDATION_422, "缺少 version（乐观锁）");
        try {
            if (v instanceof Number n) return n.intValue();
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.VALIDATION_422, "version 非法");
        }
    }

    private static String parseRequiredString(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) throw new ApiException(BizError.VALIDATION_422, "缺少 " + key);
        return String.valueOf(v).trim();
    }

    private record VoucherInput(String type, String fileUrl) {}

    /** voucher 兜底校验：缺 voucher / 缺 type / fileUrl 空 → 422 BIZ_NO_VOUCHER（即使 schema required）。 */
    @SuppressWarnings("unchecked")
    private VoucherInput parseVoucher(Map<String, Object> body) {
        Object vo = body == null ? null : body.get("voucher");
        if (!(vo instanceof Map)) {
            throw new ApiException(BizError.BIZ_NO_VOUCHER, "完成支付申请单必须附凭证");
        }
        Map<String, Object> m = (Map<String, Object>) vo;
        Object t = m.get("type");
        Object f = m.get("fileUrl");
        String type = t == null ? null : String.valueOf(t).trim();
        String fileUrl = f == null ? null : String.valueOf(f).trim();
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new ApiException(BizError.BIZ_NO_VOUCHER, "凭证 fileUrl 不能为空");
        }
        if (type == null || type.isBlank() || (!"RECEIPT".equals(type) && !"PAYMENT".equals(type))) {
            throw new ApiException(BizError.VALIDATION_422, "凭证 type 非法（仅 RECEIPT/PAYMENT）");
        }
        return new VoucherInput(type, fileUrl);
    }

    // ════════════════════════════ JSON / 低级转换 ════════════════════════════

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** lines JSONB 文本 → List<LineSnapshot>。空/异常返回空列表（不致 5xx）。 */
    private List<PaymentRequestM9Dto.LineSnapshot> parseLines(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            return json.readValue(jsonText, new TypeReference<List<PaymentRequestM9Dto.LineSnapshot>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

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
}
