package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.BatchPlaybookDto;
import com.youzheng.huicui.web.dto.PlaybookDto;
import com.youzheng.huicui.web.dto.PlaybookDto.PlaybookVersionDto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 护城河「playbook」组端点（M5 作战手册·飞轮终环）。横切层范式 + JdbcTemplate 直查真表。
 *
 * 端点（基路径 /v1 由 context-path 提供，方法注解写裸路径）：
 *   GET  /projects/{id}/playbook  getPlaybook        —— x-data-scope=range（读，无 x-permission）。
 *   POST /projects/{id}/playbook  adoptPlaybook      —— x-permission=playbook.adopt，x-data-scope=own-org（采纳·飞轮终环）。
 *   GET  /batches/{id}/playbook   getBatchPlaybook   —— x-data-scope=range（经 batch.project_id 折叠到 project）。
 *   POST /batches/{id}/playbook   adoptBatchPlaybook —— x-permission=playbook.adopt，x-data-scope=own-org（批次采纳，等价项目级采纳）。
 *
 * 飞轮终环（BR-M5-05/05a/05b）：AI 产草稿状态 DRAFT → 唯有物业（PL/PC）采纳此端点才转 PUBLISHED 发布给催收员。
 * 分级采纳闸（BR-M5-05b）：FORCE_MANUAL=强制人工（本端点即人工采纳路径）；LOW_RISK_AUTO=允许低风险自动发布（可回滚）。
 *
 * 可见性（BR-M5-05）：服务商/催收员只见 PUBLISHED；物业/平台见现行版 + 版本历史（含 DRAFT）。
 * 批次手册全部经 batch.project_id 折叠到 project 维度（DDL playbook 仅 project_id 无 batch_id），避免 DDL 漂移。
 *
 * 非法输入全部优雅落 404/403/422，绝不 5xx（ApiException + BizError）。
 */
@RestController
public class PlaybookController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final String DEFAULT_ADOPT_MODE = "FORCE_MANUAL";

    private final JdbcTemplate jdbc;
    private final OrgSystemAuditService audit;

    public PlaybookController(JdbcTemplate jdbc, OrgSystemAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ── [1] GET /projects/{id}/playbook ──────────────────────────────────────
    // x-data-scope=range（无 x-permission）。越范围→403，项目不存在→404。
    @GetMapping("/projects/{id}/playbook")
    public PlaybookDto getPlaybook(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long projectId = parseId(id, "项目不存在: " + id);
        Long orgId = loadProjectOrgId(projectId);              // 不存在→404
        requireProjectVisible(s, projectId, orgId);            // 越 range→403
        return buildProjectPlaybook(s, projectId);
    }

    // ── [2] POST /projects/{id}/playbook（采纳·飞轮终环） ────────────────────
    // x-permission=playbook.adopt（拦截器先校验 PL/PC 权限点）；x-data-scope=own-org。
    @PostMapping("/projects/{id}/playbook")
    @RequirePermission("playbook.adopt")
    @Transactional
    public PlaybookDto adoptPlaybook(@PathVariable("id") String id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long projectId = parseId(id, "项目不存在: " + id);
        Long orgId = loadProjectOrgId(projectId);              // 不存在→404
        String proxyFor = requireOwnOrgProject(s, orgId);      // 越本组织→403；SA 代→proxyFor
        String content = requireContent(body);                 // content 缺失→422
        String reqVersion = optStr(body, "version");

        long newId = doAdopt(projectId, reqVersion, content, s);
        PlaybookDto after = buildProjectPlaybook(s, projectId);

        audit.write(s, "playbook.adopt", "playbook", String.valueOf(newId),
                "own-org", proxyFor, null, null, snapshot(after));
        return after;
    }

    // ── [3] GET /batches/{id}/playbook ───────────────────────────────────────
    // x-data-scope=range。批次有覆盖→source=CUSTOM 返批次级手册；否则 INHERITED 折叠到项目现行手册。
    @GetMapping("/batches/{id}/playbook")
    public BatchPlaybookDto getBatchPlaybook(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long batchId = parseId(id, "批次不存在: " + id);
        BatchRow b = loadBatch(batchId);                       // 不存在→404
        requireBatchVisible(s, b);                             // 越 range→403
        return effectiveBatchPlaybook(s, b);
    }

    // ── [4] POST /batches/{id}/playbook（批次级覆盖采纳 / 恢复继承） ──────────
    // x-permission=playbook.adopt；x-data-scope=own-org。
    //   content 非空 = 写批次级覆盖（source=CUSTOM）；content=null/键缺失 = 删批次级行恢复继承（source=INHERITED）。
    @PostMapping("/batches/{id}/playbook")
    @RequirePermission("playbook.adopt")
    @Transactional
    public BatchPlaybookDto adoptBatchPlaybook(@PathVariable("id") String id,
                                               @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long batchId = parseId(id, "批次不存在: " + id);
        BatchRow b = loadBatch(batchId);                       // 不存在→404
        String proxyFor = requireOwnOrgProject(s, b.orgId());  // 越本组织→403；SA 代→proxyFor

        // content=null（或键缺失）=清除批次自定义恢复继承（BR-M2-18b）：删批次级行 → source 回 INHERITED。
        if (body == null || !body.containsKey("content") || body.get("content") == null) {
            jdbc.update("DELETE FROM playbook WHERE batch_id = ?", batchId);
            BatchPlaybookDto inherited = effectiveBatchPlaybook(s, b);
            audit.write(s, "playbook.adopt.batch", "batch", String.valueOf(batchId),
                    "own-org", proxyFor, "clear-custom-inherit", null, snapshot(inherited.playbook()));
            return inherited;
        }

        // content 非空 = 写批次级覆盖手册（source=CUSTOM），快照项目级现行手册作 baseline（drift 比对源）。
        String content = requireContent(body);
        String reqVersion = optStr(body, "version");
        doAdoptBatch(b.projectId(), batchId, reqVersion, content, s);
        BatchPlaybookDto after = effectiveBatchPlaybook(s, b);

        audit.write(s, "playbook.adopt.batch", "batch", String.valueOf(batchId),
                "own-org", proxyFor, null, null, snapshot(after.playbook()));
        return after;
    }

    /**
     * 批次级有效手册（source 推导 BR-M2-18b）：
     * 批次存在覆盖行（batch_id=batchId 且 status<>'ARCHIVED'）→ source=CUSTOM 返批次级手册；
     * 否则 source=INHERITED 折叠到项目现行手册。
     */
    private BatchPlaybookDto effectiveBatchPlaybook(CurrentSubject s, BatchRow b) {
        if (hasBatchOverride(b.id())) {
            return new BatchPlaybookDto("CUSTOM", buildBatchPlaybook(s, b.id()));
        }
        return new BatchPlaybookDto("INHERITED", buildProjectPlaybook(s, b.projectId()));
    }

    /** 批次是否存在现行覆盖手册（status<>'ARCHIVED'）。 */
    private boolean hasBatchOverride(long batchId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM playbook WHERE batch_id = ? AND status <> 'ARCHIVED'",
                Long.class, batchId);
        return n != null && n > 0;
    }

    // ── 取数：构造项目现行手册 + 版本历史 ─────────────────────────────────────

    /**
     * 取该 project 现行 playbook（平台/物业：status<>'ARCHIVED'；服务商/催收员：status='PUBLISHED'）
     * ORDER BY id DESC LIMIT 1；非平台/非物业主体若无 PUBLISHED 版 → content=null（不暴露草稿）。
     * 版本历史 versions[]：平台/物业含全部（含 DRAFT）；服务商/催收员仅 PUBLISHED。
     */
    private PlaybookDto buildProjectPlaybook(CurrentSubject s, long projectId) {
        boolean privileged = isPrivileged(s);                  // 平台或物业 → 见草稿/历史全量

        // batch_id IS NULL 限定项目级（V915 起批次级覆盖行 batch_id 非空，须排除，否则项目级混入批次手册）。
        String currentSql = "SELECT * FROM playbook WHERE project_id = ? AND batch_id IS NULL"
                + (privileged ? " AND status <> 'ARCHIVED'" : " AND status = 'PUBLISHED'")
                + " ORDER BY id DESC LIMIT 1";
        List<PlaybookRow> cur = jdbc.query(currentSql, playbookRowMapper(), projectId);

        // 版本历史：特权主体见全部版本；非特权仅见已发布版（不暴露 DRAFT）。
        String histSql = "SELECT version, status, adopted_by, adopted_at, created_at FROM playbook"
                + " WHERE project_id = ? AND batch_id IS NULL"
                + (privileged ? "" : " AND status = 'PUBLISHED'")
                + " ORDER BY id DESC";
        List<PlaybookVersionDto> versions = jdbc.query(histSql, versionRowMapper(), projectId);

        if (cur.isEmpty()) {
            // 无可见现行版：content 留空（不暴露草稿）。adoptMode 给默认值，versions 仍按可见性返回。
            return new PlaybookDto(String.valueOf(projectId), null, null,
                    DEFAULT_ADOPT_MODE, null, versions);
        }
        PlaybookRow r = cur.get(0);
        return new PlaybookDto(
                String.valueOf(projectId),
                r.version(),
                r.content(),
                r.adoptMode(),
                r.adoptedBy() == null ? null : String.valueOf(r.adoptedBy()),
                versions);
    }

    /**
     * 批次级覆盖手册（source=CUSTOM 时返回）：取该批次现行覆盖版（batch_id=batchId 且 status<>'ARCHIVED'）。
     * versions[] 仅列该批次自身的覆盖版本历史，避免与项目级混淆。
     */
    private PlaybookDto buildBatchPlaybook(CurrentSubject s, long batchId) {
        List<PlaybookRow> cur = jdbc.query(
                "SELECT * FROM playbook WHERE batch_id = ? AND status <> 'ARCHIVED' ORDER BY id DESC LIMIT 1",
                playbookRowMapper(), batchId);
        List<PlaybookVersionDto> versions = jdbc.query(
                "SELECT version, status, adopted_by, adopted_at, created_at FROM playbook"
                        + " WHERE batch_id = ? ORDER BY id DESC",
                versionRowMapper(), batchId);
        if (cur.isEmpty()) {
            return new PlaybookDto(String.valueOf(batchId), null, null,
                    DEFAULT_ADOPT_MODE, null, versions);
        }
        PlaybookRow r = cur.get(0);
        return new PlaybookDto(
                String.valueOf(batchId),
                r.version(),
                r.content(),
                r.adoptMode(),
                r.adoptedBy() == null ? null : String.valueOf(r.adoptedBy()),
                versions);
    }

    // ── 采纳事务：archive 现行已发布 → INSERT 新 PUBLISHED 版 ──────────────────

    /**
     * 飞轮终环写：把现行已发布版 archive，再 INSERT 新 PUBLISHED 版。
     * adopt_mode 取项目当前现行版的 adopt_mode（无则默认 FORCE_MANUAL）；adopted_by=采纳人，adopted_at=now()。
     * 返回新插入行 id。
     */
    private long doAdopt(long projectId, String reqVersion, String content, CurrentSubject s) {
        // 取项目当前 adopt_mode（沿用现行版，无现行版则默认）。batch_id IS NULL 限定项目级。
        String adoptMode = jdbc.query(
                "SELECT adopt_mode FROM playbook WHERE project_id = ? AND batch_id IS NULL AND status <> 'ARCHIVED'"
                        + " ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getString("adopt_mode") : DEFAULT_ADOPT_MODE,
                projectId);
        if (adoptMode == null || adoptMode.isBlank()) adoptMode = DEFAULT_ADOPT_MODE;

        // archive 现行已发布版（同项目级仅一现行 PUBLISHED）。
        jdbc.update("UPDATE playbook SET status = 'ARCHIVED', updated_at = now()"
                + " WHERE project_id = ? AND batch_id IS NULL AND status = 'PUBLISHED'", projectId);

        String version = (reqVersion == null || reqVersion.isBlank())
                ? nextVersion(projectId) : reqVersion;
        Long adopterId = parseLongOrNull(s.accountId());

        return jdbc.queryForObject(
                "INSERT INTO playbook(project_id, version, content, status, adopt_mode, adopted_by, adopted_at)"
                        + " VALUES (?, ?, ?, 'PUBLISHED', ?, ?, now()) RETURNING id",
                Long.class,
                projectId, version, content, adoptMode, adopterId);
    }

    /**
     * 批次级覆盖采纳：archive 该批次现行覆盖版 → INSERT 新批次级 PUBLISHED 版。
     * baseline_project_version / baseline_project_updated_at 快照项目级现行手册（drift 比对源，BR-M2-18b）：
     *   覆盖写入后若项目级手册更新（updated_at 更晚或 version 不同）→ getBatch 判 playbookDrift=true。
     * 项目级无现行手册时 baseline 留 NULL（无可比基线，按无 drift 处理）。
     */
    private void doAdoptBatch(long projectId, long batchId, String reqVersion, String content, CurrentSubject s) {
        // 沿用项目级 adopt_mode（无则默认）。
        String adoptMode = jdbc.query(
                "SELECT adopt_mode FROM playbook WHERE project_id = ? AND batch_id IS NULL AND status <> 'ARCHIVED'"
                        + " ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getString("adopt_mode") : DEFAULT_ADOPT_MODE,
                projectId);
        if (adoptMode == null || adoptMode.isBlank()) adoptMode = DEFAULT_ADOPT_MODE;

        // 项目级现行手册基线快照（version + updated_at）。
        String[] baseVersion = {null};
        java.sql.Timestamp[] baseTs = {null};
        jdbc.query(
                "SELECT version, updated_at FROM playbook WHERE project_id = ? AND batch_id IS NULL"
                        + " AND status <> 'ARCHIVED' ORDER BY id DESC LIMIT 1",
                rs -> { baseVersion[0] = rs.getString("version"); baseTs[0] = rs.getTimestamp("updated_at"); },
                projectId);

        // archive 该批次现行覆盖版（同批次仅一现行 PUBLISHED 覆盖）。
        jdbc.update("UPDATE playbook SET status = 'ARCHIVED', updated_at = now()"
                + " WHERE batch_id = ? AND status = 'PUBLISHED'", batchId);

        String version = (reqVersion == null || reqVersion.isBlank()) ? "v1.0" : reqVersion;
        Long adopterId = parseLongOrNull(s.accountId());

        jdbc.update(
                "INSERT INTO playbook(project_id, batch_id, version, content, status, adopt_mode, adopted_by, adopted_at,"
                        + " baseline_project_version, baseline_project_updated_at)"
                        + " VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?, now(), ?, ?)",
                projectId, batchId, version, content, adoptMode, adopterId, baseVersion[0], baseTs[0]);
    }

    /** 无显式版本号时按现有版本计数派生 v{n}。 */
    private String nextVersion(long projectId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM playbook WHERE project_id = ? AND batch_id IS NULL", Long.class, projectId);
        return "v" + ((n == null ? 0 : n) + 1);
    }

    // ── range 可见性校验 ──────────────────────────────────────────────────────

    /** project range：平台全量；物业 project.org_id=本组织；服务商 EXISTS 承接该项目批次。越范围→403。 */
    private void requireProjectVisible(CurrentSubject s, long projectId, Long projectOrgId) {
        if (s.isPlatform()) {
            // SE（平台员工）受三维 data_range 约束（B-01）；SA 全量。
            if (s.isSE() && !com.youzheng.huicui.common.DataScope.seVisible(s, projectOrgId, projectArea(projectId), null)) {
                throw new ApiException(BizError.PERM_403, "无权查看该项目作战手册（超出数据范围）");
            }
            return;
        }
        Long org = parseLongOrNull(s.orgId());
        if (org == null) throw new ApiException(BizError.PERM_403, "无权查看该项目作战手册");
        if ("PROVIDER".equals(s.orgType())) {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM batch WHERE project_id = ? AND provider_id = ?",
                    Long.class, projectId, org);
            if (n == null || n == 0) {
                throw new ApiException(BizError.PERM_403, "无权查看该项目作战手册（未承接该项目）");
            }
            return;
        }
        // PROPERTY（及非平台/非服务商兜底）：项目归属本组织。
        if (!org.equals(projectOrgId)) {
            throw new ApiException(BizError.PERM_403, "无权查看该项目作战手册（越组织范围）");
        }
    }

    /** batch range：同 CasesM2Controller.appendRangeScope。平台全量；物业 project.org_id；服务商 batch.provider_id。 */
    private void requireBatchVisible(CurrentSubject s, BatchRow b) {
        if (s.isPlatform()) {
            // SE 受三维 data_range 约束（B-01）：批次按其项目 org/area 与批次承接 provider 判定；SA 全量。
            if (s.isSE() && !com.youzheng.huicui.common.DataScope.seVisible(s, b.orgId(), projectArea(b.projectId()), b.providerId())) {
                throw new ApiException(BizError.PERM_403, "无权查看该批次作战手册（超出数据范围）");
            }
            return;
        }
        Long org = parseLongOrNull(s.orgId());
        if (org == null) throw new ApiException(BizError.PERM_403, "无权查看该批次作战手册");
        if ("PROVIDER".equals(s.orgType())) {
            if (b.providerId() == null || !b.providerId().equals(org)) {
                throw new ApiException(BizError.PERM_403, "无权查看该批次作战手册（未承接该批次）");
            }
            return;
        }
        if (b.orgId() == null || !b.orgId().equals(org)) {
            throw new ApiException(BizError.PERM_403, "无权查看该批次作战手册（越组织范围）");
        }
    }

    // ── own-org 守门（采纳写） ────────────────────────────────────────────────

    /**
     * own-org：project.org_id 必须=本组织，否则 403。
     * 平台 SA 代操作（proxyFor）：返回被代物业组织 id 作 proxy_for 留痕；非平台返回 null。
     */
    private String requireOwnOrgProject(CurrentSubject s, Long projectOrgId) {
        if (s.isPlatform()) {
            // 平台代物业采纳：留痕 proxy_for = 被代物业组织。
            return projectOrgId == null ? null : String.valueOf(projectOrgId);
        }
        Long org = parseLongOrNull(s.orgId());
        if (org == null || projectOrgId == null || !org.equals(projectOrgId)) {
            throw new ApiException(BizError.PERM_403, "无权采纳作战手册（非本组织项目）");
        }
        return null;
    }

    // ── 行加载（区分 404/403） ────────────────────────────────────────────────

    /** project.area（供 SE data_range areas 维判定）；不存在/异常→null（按维度参数 null 视为该维通过）。 */
    private String projectArea(long projectId) {
        try {
            return jdbc.queryForObject("SELECT area FROM project WHERE id = ?", String.class, projectId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** project.org_id（不存在→404）。 */
    private Long loadProjectOrgId(long projectId) {
        try {
            return jdbc.queryForObject(
                    "SELECT org_id FROM project WHERE id = ?", Long.class, projectId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "项目不存在: " + projectId);
        }
    }

    /** batch + JOIN project 取 org_id（经 batch.project_id 解析）。不存在→404。 */
    private BatchRow loadBatch(long batchId) {
        try {
            return jdbc.queryForObject(
                    "SELECT b.id, b.project_id, p.org_id, b.provider_id FROM batch b"
                            + " JOIN project p ON p.id = b.project_id WHERE b.id = ?",
                    (rs, i) -> new BatchRow(
                            rs.getLong("id"),
                            rs.getLong("project_id"),
                            (Long) rs.getObject("org_id"),
                            (Long) rs.getObject("provider_id")),
                    batchId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "批次不存在: " + batchId);
        }
    }

    // ── RowMappers ────────────────────────────────────────────────────────────

    private RowMapper<PlaybookRow> playbookRowMapper() {
        return (rs, i) -> new PlaybookRow(
                rs.getLong("id"),
                rs.getString("version"),
                rs.getString("content"),
                rs.getString("status"),
                rs.getString("adopt_mode"),
                (Long) rs.getObject("adopted_by"));
    }

    private RowMapper<PlaybookVersionDto> versionRowMapper() {
        return (rs, i) -> {
            Long by = (Long) rs.getObject("adopted_by");
            // tm 优先取 adopted_at（发布时刻），无则回落 created_at。
            Timestamp adoptedAt = rs.getTimestamp("adopted_at");
            Timestamp createdAt = rs.getTimestamp("created_at");
            return new PlaybookVersionDto(
                    rs.getString("version"),
                    rs.getString("status"),               // source 表达版本来源/状态（PUBLISHED/DRAFT/ARCHIVED）
                    by == null ? null : String.valueOf(by),
                    ts(adoptedAt != null ? adoptedAt : createdAt));
        };
    }

    // ── 入参校验/工具 ─────────────────────────────────────────────────────────

    /** content 必填（缺失/空白→422 ValidationError）。 */
    private static String requireContent(Map<String, Object> body) {
        String c = optStr(body, "content");
        if (c == null || c.isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少作战手册正文 content");
        }
        return c;
    }

    private static long parseId(String id, String notFoundMsg) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ApiException(BizError.NOT_FOUND_404, notFoundMsg);
        }
    }

    private static String optStr(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long parseLongOrNull(String v) {
        try {
            return v == null ? null : Long.valueOf(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    /** 平台或物业 → 见草稿/版本历史全量；服务商/催收员 → 仅 PUBLISHED。 */
    private static boolean isPrivileged(CurrentSubject s) {
        return s.isPlatform() || "PROPERTY".equals(s.orgType());
    }

    /** after_snap 快照（采纳留痕）。 */
    private static Map<String, Object> snapshot(PlaybookDto p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", p.projectId());
        m.put("version", p.version());
        m.put("adoptMode", p.adoptMode());
        m.put("adoptedBy", p.adoptedBy());
        m.put("status", "PUBLISHED");
        return m;
    }

    // ── 内部行 ────────────────────────────────────────────────────────────────

    private record PlaybookRow(long id, String version, String content, String status,
                               String adoptMode, Long adoptedBy) {
    }

    private record BatchRow(long id, long projectId, Long orgId, Long providerId) {
    }
}
