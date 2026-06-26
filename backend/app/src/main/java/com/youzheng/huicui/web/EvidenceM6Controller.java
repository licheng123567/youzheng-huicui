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
import com.youzheng.huicui.web.dto.EvidenceItemDto;
import com.youzheng.huicui.web.dto.EvidencePackageDto;
import com.youzheng.huicui.web.dto.EvidenceVerifyDto;
import com.youzheng.huicui.web.dto.LegalDocDto;
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
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M6 存证（evidence）+ 法律文书（legal）组端点（横切层范式 + scaffold 共享助手）。
 * 类名带 M6 后缀，与 M1-M5/M8/M9 controller 物理隔离；只承载本组 6 端点，不碰共享件/其他组/pom。
 * 冻结契约：docs/api/openapi-core.yaml（cases/{id}/evidence、evidence*、cases/{id}/legal-docs、legal-docs/{id}/deliver）。
 *
 * 端点（基路径 /v1 由 server.servlet.context-path 提供，注解写裸路径）：
 *   POST /cases/{id}/evidence       createEvidence    | tags=evidence | perm=evidence.create scope=own-org 幂等 | 201 EvidenceItem(ISSUING)/409/422
 *   GET  /evidence                  listEvidence      | tags=evidence |                      scope=range       | 200 EvidencePage（服务商恒空）
 *   GET  /evidence/{id}/verify      verifyEvidence    | tags=evidence | public 免鉴权        scope=public      | 200 EvidenceVerify/404
 *   POST /evidence/{id}/retry       retryEvidence     | tags=evidence | perm=evidence.create scope=own-org 幂等 | 200 EvidenceItem(ISSUING)/403/404/409
 *   GET  /cases/{id}/evidence/package getEvidencePackage | tags=evidence |                   scope=range       | 200 EvidencePackage（服务商恒空）/403/404
 *   GET  /cases/{id}/legal-docs      listCaseLegalDocs| tags=legal    |                      scope=range       | 200 LegalDocPage
 *   POST /cases/{id}/legal-docs      createCaseLegalDoc| tags=legal   | perm=legal.create    scope=range 幂等  | 201 LegalDoc(GENERATING)/409/422
 *   POST /legal-docs/{id}/deliver    deliverLegalDoc  | tags=legal    | perm=legal.create    scope=range 幂等  | 200 LegalDoc/409
 *
 * 优雅降级（Gate1 not_a_server_error 命门）——所有非法输入映射契约 Error 信封，绝不 5xx：
 *   路径 id 非法形态 / 资源不存在 → 404 NOT_FOUND_404（含 public verify 错 token→404，不报错）；
 *   越数据范围（own-org / range 不可见）→ 403 PERM_403（拦截器标 perm，scope 在此复核）；
 *   状态不允许（已 ARCHIVED 再 deliver）→ 409 STATE_409；缺必填/枚举非法/诉状要素不全 → 422 VALIDATION_422。
 *
 * public 端点（verifyEvidence）security:[]：JwtAuthFilter.isPublic endsWith('/verify') 已放行；
 *   本方法不读 SubjectContext token，只按存证号定位单条，仅返 EvidenceVerify 五字段，不泄 org/owner/case 明细。
 *
 * 计费 BR-M6-03：按次只向物业收（recharge_log type='EVIDENCE'）—— 地基期留 TODO（balance/operated_by 量级未定）。
 * 金额无涉本组（存证按次计量，不落 *_cents）。出证/生成 PDF 均异步：201 即返 ISSUING/GENERATING 占位。
 * 幂等：写端点 Idempotency-Key 由 IdempotencyInterceptor 在 header 层兜底（同键重放→409），控制器不声明该参数。
 */
@RestController
public class EvidenceM6Controller {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EvidenceM6Controller(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private static final Set<String> EVIDENCE_SCENES = Set.of("DELIVERY", "RECORDING", "MATERIAL_PACK");
    private static final Set<String> LEGAL_TYPES = Set.of("COLLECTION_LETTER", "LAWYER_LETTER", "LITIGATION");

    // ── [1] POST /cases/{id}/evidence  createEvidence ────────────────────────────
    // perm=evidence.create（仅 PL/PC 物业角色有；VL/CO 服务商无→拦截器 403）。
    // scope=own-org：校验 case 所属项目 project.org_id = 本组织（平台不限；越组织→403）。
    @PostMapping("/cases/{id}/evidence")
    @RequirePermission("evidence.create")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public EvidenceItemDto createEvidence(@PathVariable("id") String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id, "案件");

        // case 主体：取 project_id/org_id（存在性 404 优先于可见性 403）。
        CaseOrg co = loadCaseOrg(caseId);                       // 不存在→404
        requireOwnOrg(s, co.orgId);                             // 越组织→403 PERM_403

        String scene = parseScene(body);                        // 缺/非枚举→422
        List<String> refIds = parseRefIds(body);                // 可选 string[]
        String note = parseOptionalString(body, "note");

        // 场景校验（BR-M6，失败→422）。
        validateScene(scene, refIds);

        // 重复发起防护（同 case 同 scene 同 refIds 仍在 ISSUING/ISSUED → 409）。
        // Idempotency-Key 命中由 IdempotencyInterceptor 兜底 409。
        String refIdsJson = serializeRefIds(refIds);
        if (existsActiveEvidence(caseId, scene, refIdsJson)) {
            throw new ApiException(BizError.STATE_409, "同案同场景同关联的存证已发起");
        }

        long actorId = actorIdOrThrow(s);   // created_by NOT NULL：无效主体→403，绝不传 null 触发约束 5xx
        // 出证异步（上链）：落 ISSUING，cert_no/cert_url/issued_at 均 null。org_id 派生自 project.org_id。
        Long evidenceId = jdbc.queryForObject(
                "INSERT INTO evidence(org_id, case_id, scene, ref_ids, status, note, created_by)"
                        + " VALUES (?, ?, ?, ?::jsonb, 'ISSUING', ?, ?) RETURNING id",
                Long.class, co.orgId, caseId, scene, refIdsJson, note, actorId);

        // TODO(BR-M6-03/M9-B): 按次计费只向物业 —— recharge_log(org_id=co.orgId, type='EVIDENCE', delta=-1, ...)。
        //   地基期不落（balance 快照/operated_by 取值待 M9 计费域定）。

        return new EvidenceItemDto(String.valueOf(evidenceId), String.valueOf(caseId),
                scene, "ISSUING", null, null, null);
    }

    // ── [2] GET /evidence  listEvidence ──────────────────────────────────────────
    // 无 perm（靠 scope 控可见）。三方隔离 BR-M6：平台→全量；物业→e.org_id=本组织；服务商→恒空(1=0)。
    @GetMapping("/evidence")
    public Page<EvidenceItemDto> listEvidence(
            @RequestParam(required = false) String caseId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        addEqLong(where, args, "e.case_id", caseId);
        appendEvidenceScope(s, where, args);

        String base = "FROM evidence e" + where;
        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<EvidenceItemDto> items = jdbc.query(
                "SELECT e.* " + base + " ORDER BY e.id DESC LIMIT ? OFFSET ?",
                evidenceMapper(), pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [3] GET /evidence/{id}/verify  verifyEvidence ────────────────────────────
    // public 免鉴权（security:[]）：不读 SubjectContext，不依赖 token。错 id→404，不报错、不泄越权字段。
    @GetMapping("/evidence/{id}/verify")
    public EvidenceVerifyDto verifyEvidence(@PathVariable("id") String id) {
        long evidenceId = parseId(id, "存证");          // 非数→404 NOT_FOUND_404
        VerifyRow r;
        try {
            r = jdbc.queryForObject(
                    "SELECT id, case_id, scene, status, cert_no, issued_at FROM evidence WHERE id = ?",
                    (rs, i) -> new VerifyRow(
                            rs.getLong("id"),
                            rs.getLong("case_id"),
                            rs.getString("scene"),
                            rs.getString("status"),
                            rs.getString("cert_no"),
                            rs.getTimestamp("issued_at")),
                    evidenceId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "存证不存在");
        }

        boolean valid = "ISSUED".equals(r.status) && r.certNo != null;
        String issuedAt = ts(r.issuedAt);
        // 派生哈希（DDL 无 hash 列）：SHA-256(id|case_id|scene|cert_no|issued_at) hex。
        String hash = sha256Hex(r.id + "|" + r.caseId + "|" + nz(r.scene)
                + "|" + nz(r.certNo) + "|" + nz(issuedAt));
        // public 不泄越权数据：仅返 valid/certNo/scene/issuedAt/hash，不含 org/owner/case 明细。
        return new EvidenceVerifyDto(valid, r.certNo, r.scene, issuedAt, hash);
    }

    // ── [3b] POST /evidence/{id}/retry  retryEvidence ────────────────────────────
    // perm=evidence.create（同 createEvidence；VL/CO 服务商无→拦截器 403）。
    // scope=own-org：经 evidence.org_id 复核本组织（平台不限；越组织→403）。
    // 仅 status=FAILED 可重试，置回 ISSUING 重新出证；非 FAILED→409；不存在→404。
    // 行锁 FOR UPDATE 防并发双重重试。
    @PostMapping("/evidence/{id}/retry")
    @RequirePermission("evidence.create")
    @Transactional
    public EvidenceItemDto retryEvidence(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long evidenceId = parseId(id, "存证");
        EvidenceLock lock = lockEvidence(evidenceId);          // 不存在→404
        requireOwnOrg(s, lock.orgId);                          // 越组织→403 PERM_403

        // 仅 FAILED 可重试。已 ISSUING/ISSUED 重试→409（非失败态不可重发）。
        if (!"FAILED".equals(lock.status)) {
            throw new ApiException(BizError.STATE_409, "仅失败(FAILED)存证可重试");
        }

        // 置回 ISSUING 重新出证：清空上轮出证产物（cert_no/cert_url/issued_at），等待异步上链。
        jdbc.update(
                "UPDATE evidence SET status = 'ISSUING', cert_no = NULL, cert_url = NULL,"
                        + " issued_at = NULL, updated_at = now() WHERE id = ?",
                evidenceId);

        // TODO(BR-M6-03/M9-B): 重试同样按次计费只向物业 —— recharge_log(type='EVIDENCE') 待 M9 计费域定。

        return loadEvidence(evidenceId);
    }

    // ── [3c] GET /cases/{id}/evidence/package  getEvidencePackage ─────────────────
    // 无 perm（靠 scope 控可见）。scope=range：case 越范围→403（存在性 404 优先）。
    // 聚合本案已出证(ISSUED)存证为打包下载；三方隔离 BR-M6：服务商不可见存证→items 恒空。
    // documentUrl 文件通道 TBD → 占位 null。
    @GetMapping("/cases/{id}/evidence/package")
    public EvidencePackageDto getEvidencePackage(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id, "案件");

        loadCaseOrg(caseId);                       // 不存在→404（存在性优先）
        if (!visibleByRange(s, caseId)) {          // 越范围→403
            throw new ApiException(BizError.PERM_403, "无权下载该案件存证包");
        }

        // 聚合已出证存证；三方隔离同 listEvidence（服务商恒空、物业 e.org_id、平台全量）。
        StringBuilder where = new StringBuilder(" WHERE e.case_id = ? AND e.status = 'ISSUED'");
        List<Object> args = new ArrayList<>();
        args.add(caseId);
        appendEvidenceScope(s, where, args);

        List<EvidenceItemDto> items = jdbc.query(
                "SELECT e.* FROM evidence e" + where + " ORDER BY e.id DESC",
                evidenceMapper(), args.toArray());

        // documentUrl 占位 null（文件打包通道 TBD）；itemCount = 聚合条数。
        return new EvidencePackageDto(String.valueOf(caseId), null, items.size(), items);
    }

    // ── [4] GET /cases/{id}/legal-docs  listCaseLegalDocs ────────────────────────
    // 无 perm。scope=range：case 经 project.org_id / batch.provider_id 三分支可见性裁剪（同 CasesM2 appendRangeScope）。
    @GetMapping("/cases/{id}/legal-docs")
    public Page<LegalDocDto> listCaseLegalDocs(
            @PathVariable("id") String id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id, "案件");
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE l.case_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(caseId);
        appendRangeScope(s, where, args);   // 越范围→空页（WHERE 不匹配）

        String base = "FROM legal_doc l"
                + " JOIN \"case\" c ON c.id = l.case_id"
                + " JOIN project p ON p.id = c.project_id"
                + " JOIN batch b ON b.id = c.batch_id"
                + where;

        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<LegalDocDto> items = jdbc.query(
                "SELECT l.* " + base + " ORDER BY l.id DESC LIMIT ? OFFSET ?",
                legalDocMapper(), pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [5] POST /cases/{id}/legal-docs  createCaseLegalDoc ──────────────────────
    // perm=legal.create（PL/PC 有）。scope=range：case 越范围→403。
    @PostMapping("/cases/{id}/legal-docs")
    @RequirePermission("legal.create")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public LegalDocDto createCaseLegalDoc(@PathVariable("id") String id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id, "案件");

        loadCaseOrg(caseId);                       // 不存在→404（存在性优先）
        if (!visibleByRange(s, caseId)) {          // 越范围→403
            throw new ApiException(BizError.PERM_403, "无权操作该案件");
        }

        String type = parseLegalType(body);        // 缺/非枚举→422
        String templateId = parseOptionalString(body, "templateId");
        String note = parseOptionalString(body, "note");

        // LITIGATION 类要素不全→422（BR-M4-18a：校验 case.litigation_fields 非空，地基期校验非空即可）。
        if ("LITIGATION".equals(type)) {
            requireLitigationFields(caseId);
        }

        long actorId = actorIdOrThrow(s);   // created_by NOT NULL：无效主体→403，绝不传 null 触发约束 5xx
        // 生成 PDF 异步：落 GENERATING，pdf_url=null。
        Long legalDocId = jdbc.queryForObject(
                "INSERT INTO legal_doc(case_id, type, template_id, status, note, created_by)"
                        + " VALUES (?, ?, ?, 'GENERATING', ?, ?) RETURNING id",
                Long.class, caseId, type, templateId, note, actorId);

        return loadLegalDoc(legalDocId);
    }

    // ── [6] POST /legal-docs/{id}/deliver  deliverLegalDoc ───────────────────────
    // perm=legal.create。scope=range：经 legal_doc.case_id 复核可见性，越→403。
    // 行锁 FOR UPDATE；已 ARCHIVED 再 deliver→409；已 SIGNED 重复→幂等 200。
    @PostMapping("/legal-docs/{id}/deliver")
    @RequirePermission("legal.create")
    @Transactional
    public LegalDocDto deliverLegalDoc(@PathVariable("id") String id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long legalDocId = parseId(id, "法律文书");
        LegalDocLock lock = lockLegalDoc(legalDocId);          // 不存在→404
        if (!visibleByRange(s, lock.caseId)) {                 // 越范围→403
            throw new ApiException(BizError.PERM_403, "无权操作该法律文书");
        }

        // 已 ARCHIVED 不可再送达→409 STATE_409。
        if ("ARCHIVED".equals(lock.status)) {
            throw new ApiException(BizError.STATE_409, "已存证归档的文书不可再送达");
        }
        // 已 SIGNED 重复 deliver→幂等 200（直接返回当前快照）。
        if ("SIGNED".equals(lock.status)) {
            return loadLegalDoc(legalDocId);
        }

        String signedPhotoUrl = parseOptionalString(body, "signedPhotoUrl");
        Timestamp deliveredAt = parseInstantOrNow(body, "deliveredAt");

        // 派生 legalStage=SIGNED（送达签收即推进；非独立推进端点）。
        jdbc.update(
                "UPDATE legal_doc SET status = 'SIGNED', delivered_at = ?, signed_photo_url = ?, updated_at = now()"
                        + " WHERE id = ?",
                deliveredAt, signedPhotoUrl, legalDocId);

        // TODO(BR-M4-18): 签收后可对接存证 —— 触发 /cases/{id}/evidence(scene=DELIVERY) 并回填 legal_doc.evidence_id。

        return loadLegalDoc(legalDocId);
    }

    // ── 场景校验（BR-M6）────────────────────────────────────────────────────────

    private void validateScene(String scene, List<String> refIds) {
        switch (scene) {
            case "RECORDING" -> {
                // 校验 refIds 指向 call_recording.status='READY'。
                if (refIds.isEmpty()) {
                    throw new ApiException(BizError.VALIDATION_422, "录音存证须指定 refIds(就绪录音)");
                }
                List<Long> ids = toLongIds(refIds);            // 非数→422
                String ph = ids.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(","));
                Integer ready = jdbc.query(
                        "SELECT count(*) FROM call_recording WHERE id IN (" + ph + ") AND status = 'READY'",
                        rs -> rs.next() ? rs.getInt(1) : 0,
                        ids.toArray());
                if (ready == null || ready != ids.size()) {
                    throw new ApiException(BizError.VALIDATION_422, "存在未就绪(非 READY)录音，无法存证");
                }
            }
            case "DELIVERY" -> {
                // 校验关联 legal_doc 已签收（SIGNED）。refIds 指向 legal_doc id。
                if (refIds.isEmpty()) {
                    throw new ApiException(BizError.VALIDATION_422, "送达存证须指定 refIds(已签收文书)");
                }
                List<Long> ids = toLongIds(refIds);            // 非数→422
                String ph = ids.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(","));
                Integer signed = jdbc.query(
                        "SELECT count(*) FROM legal_doc WHERE id IN (" + ph + ") AND status = 'SIGNED'",
                        rs -> rs.next() ? rs.getInt(1) : 0,
                        ids.toArray());
                if (signed == null || signed != ids.size()) {
                    throw new ApiException(BizError.VALIDATION_422, "存在未签收文书，无法发起送达存证");
                }
            }
            case "MATERIAL_PACK" -> {
                // TODO(BR-M6): 校验材料齐备 —— 地基期放宽，仅落库不强校验。
            }
            default -> throw new ApiException(BizError.VALIDATION_422, "scene 非法");
        }
    }

    /** LITIGATION：case.litigation_fields 非空才可申请诉状（BR-M4-18a，地基期校验非空）。 */
    private void requireLitigationFields(long caseId) {
        String lf = jdbc.query(
                "SELECT litigation_fields FROM \"case\" WHERE id = ?",
                rs -> rs.next() ? rs.getString("litigation_fields") : null,
                caseId);
        boolean empty = lf == null || lf.isBlank() || "{}".equals(lf.trim()) || "null".equals(lf.trim());
        if (empty) {
            throw new ApiException(BizError.VALIDATION_422, "起诉状要素不全(litigation_fields 为空)");
        }
    }

    // ── scope 助手 ────────────────────────────────────────────────────────────

    /** 存证列表 scope（三方隔离 BR-M6）：平台全量；服务商恒空(1=0)；其余(物业)按 e.org_id。 */
    private void appendEvidenceScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        if (s.isPlatform()) return;                       // 平台全量
        if ("PROVIDER".equals(s.orgType())) {
            where.append(" AND 1=0");                     // 服务商不可见存证 → 恒空页
            return;
        }
        where.append(" AND e.org_id = ?");                // 物业本组织
        args.add(orgIdOrThrow(s));
    }

    /** range scope（与 CasesM2Controller 同口径）：平台全量；服务商 b.provider_id；其余 p.org_id。 */
    private void appendRangeScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        if (s.isPlatform()) return;
        if ("PROVIDER".equals(s.orgType())) {
            // 案件级归属唯一权威（不 COALESCE 回落 batch）。
            where.append(" AND c.provider_id = ?");
            args.add(orgIdOrThrow(s));
        } else {
            where.append(" AND p.org_id = ?");
            args.add(orgIdOrThrow(s));
        }
    }

    /** range 可见性复核：该 case 对当前主体是否可见。 */
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

    /** own-org 复核（evidence.create）：平台不限；其余须 project.org_id = 本组织，否则 403。 */
    private void requireOwnOrg(CurrentSubject s, long orgId) {
        if (s.isPlatform()) return;
        if (orgIdOrThrow(s) != orgId) {
            throw new ApiException(BizError.PERM_403, "无权在该案件发起存证(越组织)");
        }
    }

    // ── 数据加载 ────────────────────────────────────────────────────────────

    private record CaseOrg(long caseId, long orgId) {}

    /** 取 case 及其项目 org_id（不存在→404）。 */
    private CaseOrg loadCaseOrg(long caseId) {
        try {
            return jdbc.queryForObject(
                    "SELECT c.id AS cid, p.org_id AS org_id FROM \"case\" c"
                            + " JOIN project p ON p.id = c.project_id WHERE c.id = ?",
                    (rs, i) -> new CaseOrg(rs.getLong("cid"), rs.getLong("org_id")),
                    caseId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在");
        }
    }

    private LegalDocDto loadLegalDoc(long id) {
        return jdbc.queryForObject(
                "SELECT * FROM legal_doc WHERE id = ?", legalDocMapper(), id);
    }

    private record LegalDocLock(long id, long caseId, String status) {}

    private LegalDocLock lockLegalDoc(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, case_id, status FROM legal_doc WHERE id = ? FOR UPDATE",
                    (rs, i) -> new LegalDocLock(rs.getLong("id"), rs.getLong("case_id"), rs.getString("status")),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "法律文书不存在");
        }
    }

    private record VerifyRow(long id, long caseId, String scene, String status, String certNo, Timestamp issuedAt) {}

    private record EvidenceLock(long id, long orgId, String status) {}

    /** 取存证并行锁（重试用）：不存在→404。带 org_id 供 own-org 复核。 */
    private EvidenceLock lockEvidence(long id) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, org_id, status FROM evidence WHERE id = ? FOR UPDATE",
                    (rs, i) -> new EvidenceLock(rs.getLong("id"), rs.getLong("org_id"), rs.getString("status")),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "存证不存在");
        }
    }

    /** 重新读取单条存证（重试后回快照）。 */
    private EvidenceItemDto loadEvidence(long id) {
        return jdbc.queryForObject("SELECT e.* FROM evidence e WHERE e.id = ?", evidenceMapper(), id);
    }

    /** 同 case 同 scene 同 refIds 且仍在 ISSUING/ISSUED → 重复发起。 */
    private boolean existsActiveEvidence(long caseId, String scene, String refIdsJson) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM evidence WHERE case_id = ? AND scene = ?"
                        + " AND ref_ids = ?::jsonb AND status IN ('ISSUING','ISSUED')",
                Long.class, caseId, scene, refIdsJson);
        return n != null && n > 0;
    }

    // ── RowMapper ─────────────────────────────────────────────────────────────

    private RowMapper<EvidenceItemDto> evidenceMapper() {
        return (rs, i) -> new EvidenceItemDto(
                String.valueOf(rs.getLong("id")),
                String.valueOf(rs.getLong("case_id")),
                rs.getString("scene"),
                rs.getString("status"),
                rs.getString("cert_no"),
                rs.getString("cert_url"),
                ts(rs.getTimestamp("issued_at")));
    }

    private RowMapper<LegalDocDto> legalDocMapper() {
        return (rs, i) -> new LegalDocDto(
                String.valueOf(rs.getLong("id")),
                String.valueOf(rs.getLong("case_id")),
                rs.getString("type"),
                rs.getString("status"),
                rs.getString("pdf_url"),
                ts(rs.getTimestamp("delivered_at")),
                rs.getString("signed_photo_url"),
                idOrNull(rs, "evidence_id"),
                idOrNull(rs, "created_by"),
                ts(rs.getTimestamp("created_at")));
    }

    // ── 入参解析（非法一律 422 / id 非法 404）─────────────────────────────────

    private String parseScene(Map<String, Object> body) {
        Object v = body == null ? null : body.get("scene");
        String c = v == null ? null : String.valueOf(v).trim();
        if (c == null || c.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 scene");
        }
        if (!EVIDENCE_SCENES.contains(c)) {
            throw new ApiException(BizError.VALIDATION_422, "scene 非法(DELIVERY/RECORDING/MATERIAL_PACK)");
        }
        return c;
    }

    private String parseLegalType(Map<String, Object> body) {
        Object v = body == null ? null : body.get("type");
        String c = v == null ? null : String.valueOf(v).trim();
        if (c == null || c.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 type");
        }
        if (!LEGAL_TYPES.contains(c)) {
            throw new ApiException(BizError.VALIDATION_422, "type 非法(COLLECTION_LETTER/LAWYER_LETTER/LITIGATION)");
        }
        return c;
    }

    /** refIds 可选 string[]。缺/空→空列表；元素非字符串列表→422。 */
    @SuppressWarnings("unchecked")
    private List<String> parseRefIds(Map<String, Object> body) {
        Object v = body == null ? null : body.get("refIds");
        if (v == null) return List.of();
        if (!(v instanceof List<?> raw)) {
            throw new ApiException(BizError.VALIDATION_422, "refIds 须为字符串数组");
        }
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (o == null) continue;
            out.add(String.valueOf(o).trim());
        }
        return out;
    }

    /** refIds → bigint id 列表（非数→422，用于场景校验关联表）。 */
    private List<Long> toLongIds(List<String> refIds) {
        List<Long> out = new ArrayList<>();
        for (String r : refIds) {
            try {
                out.add(Long.valueOf(r));
            } catch (RuntimeException e) {
                throw new ApiException(BizError.VALIDATION_422, "refIds 含非法 id: " + r);
            }
        }
        return out;
    }

    /** refIds 序列化为 JSON 文本（落 ref_ids JSONB）。失败兜 '[]' 不致 5xx。 */
    private String serializeRefIds(List<String> refIds) {
        try {
            return json.writeValueAsString(refIds);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String parseOptionalString(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        String str = String.valueOf(v).trim();
        return str.isBlank() ? null : str;
    }

    /** deliveredAt 可选 ISO-8601 date-time；缺→now；非法→422。 */
    private static Timestamp parseInstantOrNow(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return Timestamp.from(Instant.now());
        }
        try {
            return Timestamp.from(Instant.parse(String.valueOf(v).trim()));
        } catch (DateTimeParseException e) {
            throw new ApiException(BizError.VALIDATION_422, key + " 时间格式非法(须 ISO-8601)");
        }
    }

    // ── 低级工具 ──────────────────────────────────────────────────────────────

    private static void addEqLong(StringBuilder where, List<Object> args, String col, String v) {
        if (v == null || v.isBlank()) return;
        try {
            args.add(Long.valueOf(v.trim()));
            where.append(" AND ").append(col).append(" = ?");
        } catch (NumberFormatException e) {
            where.append(" AND 1=0");   // 非法过滤值→无匹配（宽松，防 5xx）
        }
    }

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    private static String idOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : String.valueOf(v);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(in.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** created_by NOT NULL 列：actorId 必须可解析，否则 403（防 null 触发约束 → 5xx）。 */
    private static long actorIdOrThrow(CurrentSubject s) {
        try {
            return Long.parseLong(s.accountId());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.PERM_403, "无效主体上下文");
        }
    }

    private static long orgIdOrThrow(CurrentSubject s) {
        try {
            return Long.parseLong(s.orgId());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.PERM_403, "无组织上下文");
        }
    }

    /** 路径 id 非法形态统一 404，避免存在性泄漏 / 防 5xx。 */
    private static long parseId(String id, String label) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, label + "不存在: " + id);
        }
    }
}
