package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.BatchPropertyView;
import com.youzheng.huicui.web.dto.CaseDto;
import com.youzheng.huicui.web.dto.MasterWriteDtos.BatchImportInput;
import com.youzheng.huicui.web.dto.MasterWriteDtos.CaseImportRow;
import com.youzheng.huicui.web.dto.MasterWriteDtos.CoordinatorsInput;
import com.youzheng.huicui.web.dto.MasterWriteDtos.ImportError;
import com.youzheng.huicui.web.dto.MasterWriteDtos.ImportLitigation;
import com.youzheng.huicui.web.dto.MasterWriteDtos.ImportResult;
import com.youzheng.huicui.web.dto.MasterWriteDtos.LitigationFields;
import com.youzheng.huicui.web.dto.MasterWriteDtos.ManualCaseInput;
import com.youzheng.huicui.web.dto.MasterWriteDtos.ReasonInput;
import com.youzheng.huicui.web.dto.MasterWriteDtos.ReduceTierDto;
import com.youzheng.huicui.web.dto.MasterWriteDtos.ReduceTiersPutInput;
import com.youzheng.huicui.web.dto.MasterWriteDtos.ReduceTiersResult;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「master-write」组写端点（主数据：导入批次 / 手工入案 / 作废 / 协调员 / 减免阶梯 / 项目级配置）。
 * 基路径 /v1 由 server.servlet.context-path 提供，方法注解写裸路径。
 * 与既有读端点控制器（BatchesM2Controller/CasesM2Controller/ProjectsM2Controller）物理隔离，互不覆盖。
 *
 * 横切落地（对齐契约 x-permission/x-data-scope）：
 *   - @RequirePermission(契约 x-permission) → PermissionInterceptor 校验，无权 403；
 *   - x-data-scope=own-org：DataScope.ownOrg 的等价显式校验——经 project.org_id 关联，越权统一 403/404；
 *   - Idempotency-Key 由 IdempotencyInterceptor header 层兜底（同键重放 409），控制器不声明该参；
 *   - 写端点 @Transactional；
 *   - 敏感写（作废 / 协调员变更）落 audit_log（actor/action/target/target_type/target_id/reason/proxy_for）；
 *   - 金额 *_cents 原样 Long；Rate=分数(0-1) 直存 NUMERIC(6,4)；reduce_tier.discount 文本原样；
 *   - 所有非法输入优雅返回（404/403/409/422），绝不 5xx。
 *
 * 端点：
 *   [1] POST /batches/import                 importBatch            | batch.import        | @Transactional
 *   [2] POST /batches/{id}/cases             createCase             | batch.import
 *   [3] POST /batches/{id}/void              voidBatch              | case.void           | @Transactional + audit
 *   [4] POST /cases/{id}/void                voidCase               | case.void           | @Transactional + audit
 *   [5] PUT  /batches/{id}/coordinators      setBatchCoordinators   | batch.import        | @Transactional + audit
 *   [6] GET  ... reduce-tiers 读在 BatchesM2/ProjectsM2；此处只承载写。
 *   [6']PUT  /batches/{id}/reduce-tiers       putBatchReduceTiers    | reduce.policy.edit  | @Transactional（覆盖时写基线 V912）
 *   [7s]POST /batches/{id}/reduce-tiers:sync  syncBatchReduceTiers   | reduce.policy.edit  | @Transactional（一键同步为项目最新 BR-M2-18b）
 *   [7p]POST /batches/{id}/playbook:sync      syncBatchPlaybook      | playbook.adopt      | @Transactional + audit（手册一键同步 BR-M2-18b；当前 no-op·DDL 无批次级手册）
 *   [7] PUT  /projects/{id}/reduce-tiers      setProjectReduceTiers  | reduce.policy.edit  | @Transactional
 *   [8] PUT  /projects/{id}/coordinators      setProjectCoordinators | proj.edit           | @Transactional + audit
 * 注：GET /batches/{id}/reduce-tiers 由本类提供（契约该读端点尚未落地，且需 source 推导）。
 */
@RestController
public class MasterWriteController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MasterWriteController(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final Set<String> DECIDE_ENUM = Set.of("COLLECTOR_SELF", "OFFLINE_INTERNAL", "PL_APPROVE");

    // =====================================================================
    // [1] POST /batches/import — importBatch
    // =====================================================================
    @PostMapping("/batches/import")
    @RequirePermission("batch.import")
    @Transactional
    public ImportResult importBatch(@RequestBody(required = false) BatchImportInput in) {
        CurrentSubject s = SubjectContext.get();
        if (in == null) throw validation("请求体不能为空");
        if (in.projectId() == null || in.projectId().isBlank()) throw validation("projectId 必填");
        BigDecimal rate = in.commInRate();
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw validation("commInRate 须为分数 0-1");
        }
        List<CaseImportRow> rows = in.rows();
        if (rows == null || rows.isEmpty()) throw validation("rows 不能为空");

        long projectId = parseIdOr422(in.projectId(), "projectId 非法");
        ProjectRef proj = loadProjectOwnOrg(s, projectId);   // 不存在→404，越权→403

        // 创建批次（no 自动生成 'B'+seq；commInRate 直存 NUMERIC(6,4)；inherited=false；状态 PENDING）。
        String batchNo = nextBatchNo();
        String importMeta = toJson(Map.of(
                "total", rows.size(),
                "importedBy", s.accountId(),
                "importedAt", java.time.Instant.now().toString()));
        Long batchId = jdbc.queryForObject(
                "INSERT INTO batch(project_id, no, comm_in_rate, comm_in_inherited, pay_out_rate, status, import_meta)"
                        + " VALUES (?, ?, ?, false, NULL, 'PENDING', ?::jsonb) RETURNING id",
                Long.class, projectId, batchNo, rate, importMeta);

        int succeeded = 0, skipped = 0;
        List<ImportError> errors = new ArrayList<>();
        int rowNo = 0;
        for (CaseImportRow r : rows) {
            rowNo++;
            String missing = firstMissing(r);
            if (missing != null) {
                skipped++;
                errors.add(new ImportError(rowNo, missing, "VALIDATION_422", missing + " 缺失"));
                continue;
            }
            if (r.dueCents() < 0) {
                skipped++;
                errors.add(new ImportError(rowNo, "dueCents", "VALIDATION_422", "dueCents 不能为负"));
                continue;
            }
            try {
                insertCase(batchId, projectId, proj.name(), r.acctNo(), r.ownerName(), r.room(),
                        r.dueCents(), List.of(r.arrearPeriod()), litigationFromImport(r.litigation()));
                succeeded++;
            } catch (DuplicateKeyException dup) {
                // uq_case_batch_acct：同批户号重复 → 进 errors 不中断（BR-M2-14）。
                skipped++;
                errors.add(new ImportError(rowNo, "acctNo", "BIZ_DUP_ACCT", "同批户号重复: " + r.acctNo()));
            }
        }

        // 导入是物业 master-data 动作 → 返物业视角(BatchForProperty,无 payOutRate)：
        //   ① 未派单批次 payOutRate 本就 null,平台视角必填 payOutRate 是非-null Rate,无法满足→违约;
        //   ② 资金双线 BR-M9-11:物业不该见付佣比例。平台要全视图可另查 GET /batches。
        // 新导入批次未覆盖项目级减免/手册 → reduceDrift/playbookDrift 恒 false（BR-M2-18b）。
        BatchPropertyView batchView = new BatchPropertyView(
                String.valueOf(batchId), String.valueOf(projectId), batchNo, null, List.of(),
                "PENDING", "INHERIT", "INHERIT", "PROPERTY", rate, false, false, false);
        return new ImportResult(batchView, rows.size(), succeeded, skipped, errors);
    }

    private static String firstMissing(CaseImportRow r) {
        if (isBlank(r.acctNo())) return "acctNo";
        if (isBlank(r.ownerName())) return "ownerName";
        if (isBlank(r.phone())) return "phone";
        if (isBlank(r.room())) return "room";
        if (r.dueCents() == null) return "dueCents";
        if (isBlank(r.arrearPeriod())) return "arrearPeriod";
        return null;
    }

    // =====================================================================
    // [2] POST /batches/{id}/cases — createCase
    // =====================================================================
    @PostMapping("/batches/{id}/cases")
    @RequirePermission("batch.import")
    @Transactional
    public ResponseEntity<CaseDto> createCase(@PathVariable("id") String id,
                                              @RequestBody(required = false) ManualCaseInput in) {
        CurrentSubject s = SubjectContext.get();
        if (in == null) throw validation("请求体不能为空");
        if (isBlank(in.acctNo())) throw validation("acctNo 必填");
        if (isBlank(in.ownerName())) throw validation("ownerName 必填");
        if (isBlank(in.phone())) throw validation("phone 必填");
        if (isBlank(in.room())) throw validation("room 必填");
        if (in.dueCents() == null) throw validation("dueCents 必填");
        if (in.dueCents() < 0) throw validation("dueCents 不能为负");
        if (in.arrearagePeriods() == null || in.arrearagePeriods().isEmpty()) throw validation("arrearagePeriods 必填");

        long batchId = parseIdOr404(id);
        BatchRef batch = loadBatchOwnOrg(s, batchId);   // 不存在→404，越权→403
        // 批次须可纳入：仅 PENDING 可继续导入案件，否则 409。
        if (!"PENDING".equals(batch.status())) {
            throw new ApiException(BizError.STATE_409, "批次非 PENDING，不可纳入新案件");
        }

        long caseId;
        try {
            caseId = insertCase(batchId, batch.projectId(), batch.projectName(), in.acctNo(), in.ownerName(),
                    in.room(), in.dueCents(), in.arrearagePeriods(), litigationToJson(in.litigationFields()));
        } catch (DuplicateKeyException dup) {
            // 同批户号重复 → 409 BIZ_DUP_ACCT（契约 409）。
            throw new ApiException(BizError.STATE_409, "同批户号重复: " + in.acctNo());
        }

        CaseDto dto = new CaseDto(
                String.valueOf(caseId), in.acctNo(), String.valueOf(batchId), String.valueOf(batch.projectId()),
                batch.projectName(), in.ownerName(), in.room(), in.dueCents(), in.dueCents(),
                in.arrearagePeriods(), in.litigationFields(), "PENDING_DISPATCH", "NONE",
                null, "PLATFORM_SEA", null, null, null, null, null, false);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    // =====================================================================
    // [3] POST /batches/{id}/void — voidBatch（敏感写 + audit）
    // =====================================================================
    @PostMapping("/batches/{id}/void")
    @RequirePermission("case.void")
    @Transactional
    public void voidBatch(@PathVariable("id") String id, @RequestBody(required = false) ReasonInput in) {
        CurrentSubject s = SubjectContext.get();
        requireReason(in);
        long batchId = parseIdOr404(id);
        BatchRef batch = loadBatchOwnOrg(s, batchId);

        // 前置：批内案件须均 PENDING_DISPATCH，否则 409 BIZ_NOT_PENDING_DISPATCH。
        Integer notPending = jdbc.queryForObject(
                "SELECT count(*) FROM \"case\" WHERE batch_id = ? AND status <> 'PENDING_DISPATCH'",
                Integer.class, batchId);
        if (notPending != null && notPending > 0) {
            throw new ApiException(BizError.STATE_409, "批内含非待派单案件，不可整批作废");
        }

        // CAS：全部待派单案件置 VOIDED；批次置 CLOSED。
        int voided = jdbc.update(
                "UPDATE \"case\" SET status = 'VOIDED', updated_at = now()"
                        + " WHERE batch_id = ? AND status = 'PENDING_DISPATCH'",
                batchId);
        jdbc.update("UPDATE batch SET status = 'CLOSED', updated_at = now() WHERE id = ? AND status = 'PENDING'",
                batchId);

        String proxyFor = proxyForOrg(s, batch.orgId());
        audit(s, "batch.void", "batch", batchId, in.reason(), proxyFor,
                Map.of("batchId", batchId, "voidedCases", voided));
    }

    // =====================================================================
    // [4] POST /cases/{id}/void — voidCase（敏感写 + audit）
    // =====================================================================
    @PostMapping("/cases/{id}/void")
    @RequirePermission("case.void")
    @Transactional
    public void voidCase(@PathVariable("id") String id, @RequestBody(required = false) ReasonInput in) {
        CurrentSubject s = SubjectContext.get();
        requireReason(in);
        long caseId = parseIdOr404(id);
        CaseRef c = loadCaseOwnOrg(s, caseId);   // 不存在→404，越权→403

        // 仅 PENDING_DISPATCH 可作废（CAS）。
        int n = jdbc.update(
                "UPDATE \"case\" SET status = 'VOIDED', updated_at = now()"
                        + " WHERE id = ? AND status = 'PENDING_DISPATCH'",
                caseId);
        if (n == 0) {
            // 行存在（loadCaseOwnOrg 已确认）但非待派单 → 409。
            throw new ApiException(BizError.STATE_409, "案件非待派单，不可作废");
        }

        String proxyFor = proxyForOrg(s, c.orgId());
        audit(s, "case.void", "case", caseId, in.reason(), proxyFor, Map.of("caseId", caseId));
    }

    // =====================================================================
    // [5] PUT /batches/{id}/coordinators — setBatchCoordinators（全量覆盖 + audit）
    // =====================================================================
    @PutMapping("/batches/{id}/coordinators")
    @RequirePermission("batch.import")
    @Transactional
    public void setBatchCoordinators(@PathVariable("id") String id,
                                     @RequestBody(required = false) CoordinatorsInput in) {
        CurrentSubject s = SubjectContext.get();
        if (in == null || in.coordinatorIds() == null) throw validation("coordinatorIds 必填");
        long batchId = parseIdOr404(id);
        BatchRef batch = loadBatchOwnOrg(s, batchId);

        List<Long> coordIds = validateCoordinatorsArePc(in.coordinatorIds(), batch.orgId());

        List<String> before = currentBatchCoordinators(batchId);
        jdbc.update("DELETE FROM batch_coordinators WHERE batch_id = ?", batchId);
        for (Long cid : coordIds) {
            jdbc.update("INSERT INTO batch_coordinators(batch_id, coordinator_id) VALUES (?, ?)", batchId, cid);
        }

        String proxyFor = proxyForOrg(s, batch.orgId());
        auditSnap(s, "batch.coordinators.set", "batch", batchId, null, proxyFor,
                Map.of("coordinatorIds", before), Map.of("coordinatorIds", strIds(coordIds)));
    }

    // =====================================================================
    // [6 read] GET /batches/{id}/reduce-tiers — getBatchReduceTiers
    // =====================================================================
    @org.springframework.web.bind.annotation.GetMapping("/batches/{id}/reduce-tiers")
    @RequirePermission("reduce.policy.edit")
    public ReduceTiersResult getBatchReduceTiers(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long batchId = parseIdOr404(id);
        BatchRef batch = loadBatchRange(s, batchId);   // range：越范围/不存在→404
        return effectiveReduceTiers(batchId, batch.projectId());
    }

    // =====================================================================
    // [7] PUT /batches/{id}/reduce-tiers — putBatchReduceTiers（全量覆盖）
    // =====================================================================
    @PutMapping("/batches/{id}/reduce-tiers")
    @RequirePermission("reduce.policy.edit")
    @Transactional
    public ReduceTiersResult putBatchReduceTiers(@PathVariable("id") String id,
                                                 @RequestBody(required = false) ReduceTiersPutInput in) {
        CurrentSubject s = SubjectContext.get();
        long batchId = parseIdOr404(id);
        BatchRef batch = loadBatchOwnOrg(s, batchId);

        List<ReduceTierDto> tiers = in == null ? null : in.tiers();
        validateTiers(tiers);

        jdbc.update("DELETE FROM reduce_tier WHERE batch_id = ?", batchId);
        if (tiers != null && !tiers.isEmpty()) {
            // BR-M2-18b：覆盖时记录“项目级当前 max(updated_at)”作基线，供 getBatch 比对得出 reduceDrift（V912 列）。
            java.sql.Timestamp baseline = projectReduceBaseline(batch.projectId());
            for (ReduceTierDto t : tiers) {
                jdbc.update(
                        "INSERT INTO reduce_tier(project_id, batch_id, discount, cap_cents, waive_penalty, decide,"
                                + " baseline_project_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        batch.projectId(), batchId, t.discount(), t.capCents(),
                        t.waivePenalty() != null && t.waivePenalty(), t.decide(), baseline);
            }
        }
        // 空数组 → 不插 → 回退继承项目级（source=INHERITED）。
        return effectiveReduceTiers(batchId, batch.projectId());
    }

    // =====================================================================
    // [7s] POST /batches/{id}/reduce-tiers:sync — syncBatchReduceTiers（一键同步为项目最新 BR-M2-18b）
    //   放弃批次自定义、重新继承项目默认（等价 PUT tiers=[]），同步后 source=INHERITED、reduceDrift=false。
    //   注意路径含冒号子动作 'reduce-tiers:sync'；权限 reduce.policy.edit，own-org。
    // =====================================================================
    @PostMapping("/batches/{id}/reduce-tiers:sync")
    @RequirePermission("reduce.policy.edit")
    @Transactional
    public ReduceTiersResult syncBatchReduceTiers(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long batchId = parseIdOr404(id);
        BatchRef batch = loadBatchOwnOrg(s, batchId);   // 不存在→404；越权→403

        // 删除批次自定义（含其基线列）→ 回退继承项目级；source=INHERITED、drift 自然消失。
        jdbc.update("DELETE FROM reduce_tier WHERE batch_id = ?", batchId);
        return effectiveReduceTiers(batchId, batch.projectId());
    }

    // =====================================================================
    // [7p] POST /batches/{id}/playbook:sync — syncBatchPlaybook（一键同步手册为项目最新 BR-M2-18b）
    //   放弃批次自定义、重新继承项目最新手册，同步后 source=INHERITED、playbookDrift=false。
    //   DDL playbook 仅 project_id 无 batch_id（批次手册经 project 折叠，见 PlaybookController）→ 当前无批次级自定义可清，
    //   语义上为幂等 no-op（恒已是 INHERITED），仅落审计留痕；待批次级手册存储落地后此处清除批次覆盖+基线。
    //   权限 playbook.adopt（对齐契约 syncBatchPlaybook x-permission），own-org；契约 200 无响应体。
    // =====================================================================
    @PostMapping("/batches/{id}/playbook:sync")
    @RequirePermission("playbook.adopt")
    @Transactional
    public void syncBatchPlaybook(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long batchId = parseIdOr404(id);
        BatchRef batch = loadBatchOwnOrg(s, batchId);   // 不存在→404；越权→403

        // 当前无批次级手册存储 → no-op；留痕“恢复继承项目最新手册”。
        String proxyFor = proxyForOrg(s, batch.orgId());
        audit(s, "playbook.sync.batch", "batch", batchId, "sync-to-project-latest", proxyFor,
                Map.of("batchId", batchId, "source", "INHERITED"));
    }

    // =====================================================================
    // [8] PUT /projects/{id}/reduce-tiers — setProjectReduceTiers（裸数组全量覆盖）
    // =====================================================================
    @PutMapping("/projects/{id}/reduce-tiers")
    @RequirePermission("reduce.policy.edit")
    @Transactional
    public void setProjectReduceTiers(@PathVariable("id") String id,
                                      @RequestBody(required = false) List<ReduceTierDto> tiers) {
        CurrentSubject s = SubjectContext.get();
        long projectId = parseIdOr404(id);
        loadProjectOwnOrg(s, projectId);
        validateTiers(tiers);

        // 仅删项目级（batch_id IS NULL）；BR-M2-18b：已自定义批次（batch_id 非空）保留，不动。
        jdbc.update("DELETE FROM reduce_tier WHERE project_id = ? AND batch_id IS NULL", projectId);
        if (tiers != null) {
            for (ReduceTierDto t : tiers) {
                jdbc.update(
                        "INSERT INTO reduce_tier(project_id, batch_id, discount, cap_cents, waive_penalty, decide)"
                                + " VALUES (?, NULL, ?, ?, ?, ?)",
                        projectId, t.discount(), t.capCents(),
                        t.waivePenalty() != null && t.waivePenalty(), t.decide());
            }
        }
    }

    // =====================================================================
    // [9] PUT /projects/{id}/coordinators — setProjectCoordinators（全量覆盖 + audit）
    // =====================================================================
    @PutMapping("/projects/{id}/coordinators")
    @RequirePermission("proj.edit")
    @Transactional
    public void setProjectCoordinators(@PathVariable("id") String id,
                                       @RequestBody(required = false) CoordinatorsInput in) {
        CurrentSubject s = SubjectContext.get();
        if (in == null || in.coordinatorIds() == null) throw validation("coordinatorIds 必填");
        long projectId = parseIdOr404(id);
        ProjectRef proj = loadProjectOwnOrg(s, projectId);

        List<Long> coordIds = validateCoordinatorsArePc(in.coordinatorIds(), proj.orgId());

        List<String> before = currentProjectCoordinators(projectId);
        jdbc.update("DELETE FROM project_coordinators WHERE project_id = ?", projectId);
        for (Long cid : coordIds) {
            jdbc.update("INSERT INTO project_coordinators(project_id, coordinator_id) VALUES (?, ?)", projectId, cid);
        }

        String proxyFor = proxyForOrg(s, proj.orgId());
        auditSnap(s, "project.coordinators.set", "project", projectId, null, proxyFor,
                Map.of("coordinatorIds", before), Map.of("coordinatorIds", strIds(coordIds)));
    }

    // =====================================================================
    // ── 共享：减免阶梯有效配置（source 推导 BR-M2-18a/18b）────────────────
    // =====================================================================
    private ReduceTiersResult effectiveReduceTiers(long batchId, long projectId) {
        List<ReduceTierDto> custom = jdbc.query(
                "SELECT discount, cap_cents, waive_penalty, decide FROM reduce_tier"
                        + " WHERE batch_id = ? ORDER BY id",
                (rs, i) -> new ReduceTierDto(
                        rs.getString("discount"), (Long) rs.getObject("cap_cents"),
                        rs.getBoolean("waive_penalty"), rs.getString("decide")),
                batchId);
        if (!custom.isEmpty()) {
            return new ReduceTiersResult("CUSTOM", custom);
        }
        List<ReduceTierDto> inherited = jdbc.query(
                "SELECT discount, cap_cents, waive_penalty, decide FROM reduce_tier"
                        + " WHERE project_id = ? AND batch_id IS NULL ORDER BY id",
                (rs, i) -> new ReduceTierDto(
                        rs.getString("discount"), (Long) rs.getObject("cap_cents"),
                        rs.getBoolean("waive_penalty"), rs.getString("decide")),
                projectId);
        return new ReduceTiersResult("INHERITED", inherited);
    }

    /**
     * 项目级减免阶梯当前基线（max(updated_at)，batch_id IS NULL）。
     * 批次覆盖写入时快照此值存入 reduce_tier.baseline_project_updated_at（V912），
     * getBatch 以“项目级当前 max(updated_at) > 基线”判定 reduceDrift（BR-M2-18b）。
     * 项目级无任何阶梯时返回 null（基线为空，getBatch 端按无 drift 处理）。
     */
    private java.sql.Timestamp projectReduceBaseline(long projectId) {
        return jdbc.query(
                "SELECT max(updated_at) AS m FROM reduce_tier WHERE project_id = ? AND batch_id IS NULL",
                rs -> rs.next() ? rs.getTimestamp("m") : null,
                projectId);
    }

    private void validateTiers(List<ReduceTierDto> tiers) {
        if (tiers == null) return;
        for (ReduceTierDto t : tiers) {
            if (t == null) throw validation("减免阶梯项不能为空");
            if (isBlank(t.discount())) throw validation("discount 必填");
            if (t.decide() == null || !DECIDE_ENUM.contains(t.decide())) throw validation("decide 非法枚举");
            if (t.capCents() != null && t.capCents() < 0) throw validation("capCents 不能为负");
        }
    }

    // =====================================================================
    // ── 共享：own-org/range 资源加载（不存在→404，越权→403）──────────────
    // =====================================================================

    private record ProjectRef(long id, long orgId, String name) {}
    private record BatchRef(long id, long projectId, long orgId, String status, String projectName) {}
    private record CaseRef(long id, long orgId, String status) {}

    /** own-org：项目须属本 org（平台不限）。不存在→404；越权→403。 */
    private ProjectRef loadProjectOwnOrg(CurrentSubject s, long projectId) {
        List<ProjectRef> rows = jdbc.query(
                "SELECT id, org_id, name FROM project WHERE id = ?",
                (rs, i) -> new ProjectRef(rs.getLong("id"), rs.getLong("org_id"), rs.getString("name")),
                projectId);
        if (rows.isEmpty()) throw new ApiException(BizError.NOT_FOUND_404, "项目不存在");
        ProjectRef p = rows.get(0);
        if (!s.isPlatform() && p.orgId() != orgIdLong(s)) {
            throw new ApiException(BizError.PERM_403, "无权操作非本组织项目");
        }
        return p;
    }

    /** own-org：批次 JOIN project 取 org。不存在→404；越权→403。 */
    private BatchRef loadBatchOwnOrg(CurrentSubject s, long batchId) {
        BatchRef b = loadBatch(batchId);
        if (!s.isPlatform() && b.orgId() != orgIdLong(s)) {
            throw new ApiException(BizError.PERM_403, "无权操作非本组织批次");
        }
        return b;
    }

    /** range：读阶段以 own-org 等价裁剪——越范围/不存在统一 404（不泄露存在性）。 */
    private BatchRef loadBatchRange(CurrentSubject s, long batchId) {
        BatchRef b = loadBatch(batchId);
        if (!s.isPlatform() && b.orgId() != orgIdLong(s)) {
            throw new ApiException(BizError.NOT_FOUND_404, "批次不存在");
        }
        return b;
    }

    private BatchRef loadBatch(long batchId) {
        // project_name 取 project.name（冗余进 case.project_name 的源），免依赖批内已有案件。
        List<BatchRef> rows = jdbc.query(
                "SELECT b.id, b.project_id, p.org_id, b.status, p.name AS project_name"
                        + " FROM batch b JOIN project p ON p.id = b.project_id"
                        + " WHERE b.id = ?",
                (rs, i) -> new BatchRef(
                        rs.getLong("id"), rs.getLong("project_id"), rs.getLong("org_id"),
                        rs.getString("status"), rs.getString("project_name")),
                batchId);
        if (rows.isEmpty()) throw new ApiException(BizError.NOT_FOUND_404, "批次不存在");
        return rows.get(0);
    }

    /** own-org：案件 JOIN project 取 org。不存在→404；越权→403。 */
    private CaseRef loadCaseOwnOrg(CurrentSubject s, long caseId) {
        List<CaseRef> rows = jdbc.query(
                "SELECT c.id, p.org_id, c.status FROM \"case\" c JOIN project p ON p.id = c.project_id WHERE c.id = ?",
                (rs, i) -> new CaseRef(rs.getLong("id"), rs.getLong("org_id"), rs.getString("status")),
                caseId);
        if (rows.isEmpty()) throw new ApiException(BizError.NOT_FOUND_404, "案件不存在");
        CaseRef c = rows.get(0);
        if (!s.isPlatform() && c.orgId() != orgIdLong(s)) {
            throw new ApiException(BizError.PERM_403, "无权操作非本组织案件");
        }
        return c;
    }

    /** 每个 coordinatorId 须为目标 org 的 PC 账号，否则 422。返回去重后的 Long id 列表。 */
    private List<Long> validateCoordinatorsArePc(List<String> ids, long orgId) {
        List<Long> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (String raw : ids) {
            long cid = parseIdOr422(raw, "coordinatorId 非法: " + raw);
            if (!seen.add(cid)) continue;   // 去重
            Integer ok = jdbc.queryForObject(
                    "SELECT count(*) FROM account WHERE id = ? AND org_id = ? AND role_template = 'PC'",
                    Integer.class, cid, orgId);
            if (ok == null || ok == 0) {
                throw validation("coordinatorId 非本组织 PC 账号: " + raw);
            }
            result.add(cid);
        }
        return result;
    }

    private List<String> currentBatchCoordinators(long batchId) {
        return jdbc.query("SELECT coordinator_id FROM batch_coordinators WHERE batch_id = ? ORDER BY coordinator_id",
                (rs, i) -> String.valueOf(rs.getLong("coordinator_id")), batchId);
    }

    private List<String> currentProjectCoordinators(long projectId) {
        return jdbc.query("SELECT coordinator_id FROM project_coordinators WHERE project_id = ? ORDER BY coordinator_id",
                (rs, i) -> String.valueOf(rs.getLong("coordinator_id")), projectId);
    }

    // =====================================================================
    // ── 共享：案件插入 / 批次号 / 校验 / 审计 / 转换工具 ──────────────────
    // =====================================================================

    /** 插入案件（PENDING_DISPATCH/PLATFORM_SEA）。uq_case_batch_acct 冲突由调用方捕 DuplicateKeyException。 */
    private long insertCase(long batchId, long projectId, String projectName, String acctNo, String ownerName,
                            String room, long dueCents, List<String> periods, String litigationJson) {
        Long id = jdbc.queryForObject(
                "INSERT INTO \"case\"(batch_id, project_id, project_name, acct_no, owner_name, room, due_cents,"
                        + " reduce_after_cents, arrearags_periods, litigation_fields, status, pool)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'PENDING_DISPATCH', 'PLATFORM_SEA')"
                        + " RETURNING id",
                Long.class, batchId, projectId, projectName, acctNo, ownerName, room, dueCents,
                dueCents, toJson(periods), litigationJson);
        return id == null ? 0L : id;
    }

    /** 批次号 'B'+全局序号（基于现有 batch 行数+1；地基期简化，生产应用序列）。 */
    private String nextBatchNo() {
        Long max = jdbc.queryForObject("SELECT COALESCE(max(id), 0) + 1 FROM batch", Long.class);
        return "B" + (max == null ? 1 : max);
    }

    private String litigationFromImport(ImportLitigation lit) {
        if (lit == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (lit.idCard() != null) m.put("idCard", lit.idCard());
        if (lit.addr() != null) m.put("mailingAddr", lit.addr());
        return m.isEmpty() ? null : toJson(m);
    }

    private String litigationToJson(LitigationFields lit) {
        if (lit == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (lit.idCard() != null) m.put("idCard", lit.idCard());
        if (lit.buildingArea() != null) m.put("buildingArea", lit.buildingArea());
        if (lit.mailingAddr() != null) m.put("mailingAddr", lit.mailingAddr());
        if (lit.contractNo() != null) m.put("contractNo", lit.contractNo());
        return m.isEmpty() ? null : toJson(m);
    }

    private void requireReason(ReasonInput in) {
        if (in == null || isBlank(in.reason())) throw validation("reason 必填");
    }

    /** 平台代物业/服务商操作时填 proxy_for=目标 org；本组织自操作为 null。 */
    private String proxyForOrg(CurrentSubject s, long targetOrgId) {
        if (s.isPlatform() && targetOrgId != orgIdLong(s)) return String.valueOf(targetOrgId);
        return null;
    }

    private void audit(CurrentSubject actor, String action, String targetType, long targetId,
                       String reason, String proxyFor, Map<String, Object> afterSnap) {
        auditSnap(actor, action, targetType, targetId, reason, proxyFor, null, afterSnap);
    }

    private void auditSnap(CurrentSubject actor, String action, String targetType, long targetId,
                           String reason, String proxyFor, Map<String, Object> beforeSnap, Map<String, Object> afterSnap) {
        Long actorId = Long.valueOf(actor.accountId());
        jdbc.update(
                "INSERT INTO audit_log(actor_id, actor, action, target, target_type, target_id, scope,"
                        + " proxy_for, before_snap, after_snap, reason, trace_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                actorId, actor.name(), action, targetType + "#" + targetId, targetType, String.valueOf(targetId),
                actor.orgType(), proxyFor,
                beforeSnap == null ? null : toJson(beforeSnap),
                afterSnap == null ? null : toJson(afterSnap),
                reason, MDC.get("traceId"));
    }

    private static List<String> strIds(List<Long> ids) {
        List<String> out = new ArrayList<>(ids.size());
        for (Long id : ids) out.add(String.valueOf(id));
        return out;
    }

    private long orgIdLong(CurrentSubject s) {
        return Long.parseLong(s.orgId());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ApiException validation(String msg) {
        return new ApiException(BizError.VALIDATION_422, msg);
    }

    /** 非法 id 形态 → 404（不泄露存在性）。 */
    private static long parseIdOr404(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "资源不存在");
        }
    }

    /** 非法 id 形态（入参体内引用）→ 422。 */
    private static long parseIdOr422(String id, String msg) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw validation(msg);
        }
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
