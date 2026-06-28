package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.common.Permissions;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.OrgSystemDtos.AuditLogDto;
import com.youzheng.huicui.web.dto.OrgSystemDtos.OrgDto;
import com.youzheng.huicui.web.dto.OrgSystemDtos.OrgInputDto;
import com.youzheng.huicui.web.dto.OrgSystemDtos.OwnerRebindInputDto;
import com.youzheng.huicui.web.dto.OrgSystemDtos.PermissionMatrixItemDto;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * orgs-system 组端点（横切层范式：SubjectContext + DataScope range/platform + @RequirePermission +
 * ApiException 优雅错误，绝不 5xx）。基路径 /v1 由 context-path 提供，注解写裸路径。
 * 类名带 M1 后缀，不碰 M1-M10 既有控制器。
 *
 * 端点：
 *   GET   /orgs                 listOrgs           range，无 x-permission（靠 scope 控可见）
 *   POST  /orgs                 createOrg          org.manage，platform（仅平台建组织 BR-M1-01）
 *   PATCH /orgs/{id}/owner      rebindOrgOwner     org.manage，platform（负责人交接 BR-M1-02/12）
 *   GET   /audit-log            listAuditLog       range，无 x-permission
 *   GET   /permission-matrix    getPermissionMatrix platform，无 x-permission
 *
 * range 口径（BR-M1 平台全量/组织本组织）：
 *   /orgs      → 平台无过滤；物业/服务商 AND id = :orgId（own-org-on-self，仅本组织）。
 *   /audit-log → 平台无过滤；物业/服务商 AND actor_id IN (本组织成员)。
 * platform 口径：!isPlatform → 403 PERM_403（仅平台）。
 *
 * 敏感动作（org.create / org.owner.rebind / reset-password）必落 audit_log（OrgSystemAuditService）。
 */
@RestController
public class OrgSystemM1Controller {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final JdbcTemplate jdbc;
    private final OrgSystemAuditService audit;
    private final ObjectMapper json;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final java.security.SecureRandom rnd = new java.security.SecureRandom();

    public OrgSystemM1Controller(JdbcTemplate jdbc, OrgSystemAuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    /** 随机初始口令(审计 M-3)：建组织 owner / 改绑重置均用，替代可预测的 devPassword。生产应带外下发+首登改密。 */
    private String generatePassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = 8 + rnd.nextInt(5); i < len; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    // ── [1] GET /orgs ─────────────────────────────────────────────────────────
    // x-data-scope=range，无 x-permission（列表靠 scope 控可见性）。
    @GetMapping("/orgs")
    public Page<OrgDto> listOrgs(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        // range（org 维退化为 own-org-on-self）：平台全量；物业/服务商仅本组织。
        if (!s.isPlatform()) {
            where.append(" AND id = ?");
            args.add(Long.valueOf(s.orgId()));
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM org" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<OrgDto> items = jdbc.query(
                "SELECT id, type, name, owner_account_id, status FROM org" + where
                        + " ORDER BY id LIMIT ? OFFSET ?",
                ORG_MAPPER, pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [2] POST /orgs ────────────────────────────────────────────────────────
    // x-permission=org.manage，x-data-scope=platform（仅平台建组织 BR-M1-01）。
    @PostMapping("/orgs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("org.manage")
    @Transactional
    public OrgDto createOrg(@RequestBody(required = false) OrgInputDto body) {
        CurrentSubject s = SubjectContext.get();
        // platform：非平台主体一律 403（BR-M1-01 仅平台建组织）。先于 422，避免越权者探测入参。
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台可新建组织");
        }
        // 422：枚举/必填校验（绝不 5xx）。
        if (body == null) {
            throw new ApiException(BizError.VALIDATION_422, "请求体必填");
        }
        String type = body.type();
        if (!isOrgType(type)) {
            throw new ApiException(BizError.VALIDATION_422, "type 非法（PLATFORM/PROPERTY/PROVIDER）");
        }
        if (isBlank(body.name())) {
            throw new ApiException(BizError.VALIDATION_422, "name 必填");
        }
        if (isBlank(body.ownerAccount())) {
            throw new ApiException(BizError.VALIDATION_422, "ownerAccount 必填");
        }
        if (isBlank(body.ownerPhone())) {
            throw new ApiException(BizError.VALIDATION_422, "ownerPhone 必填");
        }
        // 409：同类型组织名重复（BR 物业名称重复提示冲突且不创建）。
        Long dup = jdbc.queryForObject(
                "SELECT count(*) FROM org WHERE name = ? AND type = ?", Long.class, body.name(), type);
        if (dup != null && dup > 0) {
            throw new ApiException(BizError.STATE_409, "同类型组织名称已存在：" + body.name());
        }
        // 409：负责人用户名已存在（account uq_account_username）。
        Long unameDup = jdbc.queryForObject(
                "SELECT count(*) FROM account WHERE username = ?", Long.class, body.ownerAccount());
        if (unameDup != null && unameDup > 0) {
            throw new ApiException(BizError.STATE_409, "负责人账号已存在：" + body.ownerAccount());
        }

        // 写库：org → account(owner) → 回填 owner_account_id。
        Long orgId = jdbc.queryForObject(
                "INSERT INTO org(type, name, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
                Long.class, type, body.name());
        String ownerRole = "PROPERTY".equals(type) ? "PL" : "VL";
        String hash = bcrypt.encode(generatePassword());   // 随机口令(审计 M-3)
        Long ownerAccountId = jdbc.queryForObject(
                "INSERT INTO account(org_id, username, name, phone, role_template, status, is_owner, password_hash)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', TRUE, ?) RETURNING id",
                Long.class, orgId, body.ownerAccount(), body.ownerAccount(), body.ownerPhone(), ownerRole, hash);
        jdbc.update("UPDATE org SET owner_account_id = ?, updated_at = now() WHERE id = ?", ownerAccountId, orgId);

        // 审计（敏感动作必落）：org.create。
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("type", type);
        after.put("name", body.name());
        after.put("ownerAccountId", String.valueOf(ownerAccountId));
        after.put("ownerAccount", body.ownerAccount());
        audit.write(s, "org.create", "org", String.valueOf(orgId), "PLATFORM", null, null, null, after);

        return new OrgDto(String.valueOf(orgId), type, body.name(),
                String.valueOf(ownerAccountId), "ACTIVE");
    }

    // ── [3] PATCH /orgs/{id}/owner ────────────────────────────────────────────
    // x-permission=org.manage，x-data-scope=platform。BR-M1-02/12：不转移账号，仅改绑手机+(可选)重置密码。
    @PatchMapping("/orgs/{id}/owner")
    @RequirePermission("org.manage")
    @Transactional
    public Map<String, Object> rebindOrgOwner(@PathVariable String id,
                                              @RequestBody(required = false) OwnerRebindInputDto body) {
        CurrentSubject s = SubjectContext.get();
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台可改绑负责人");
        }
        long orgId = parseIdOr404(id, "组织");

        // 404：组织不存在。一并取 owner_account_id/name 作 before 快照。
        Map<String, Object> org;
        try {
            org = jdbc.queryForMap(
                    "SELECT owner_account_id, name FROM org WHERE id = ?", orgId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "组织不存在: " + id);
        }
        if (body == null || isBlank(body.newPhone())) {
            throw new ApiException(BizError.VALIDATION_422, "newPhone 必填");
        }
        Object ownerObj = org.get("owner_account_id");
        if (ownerObj == null) {
            throw new ApiException(BizError.STATE_409, "该组织暂无负责人，无法改绑");
        }
        long ownerAccountId = ((Number) ownerObj).longValue();
        boolean resetPassword = Boolean.TRUE.equals(body.resetPassword());

        // before 快照：取既有手机号。
        String oldPhone = jdbc.query(
                "SELECT phone FROM account WHERE id = ?",
                rs -> rs.next() ? rs.getString("phone") : null, ownerAccountId);

        // 写：改绑手机；可选重置密码（敏感动作）。
        jdbc.update("UPDATE account SET phone = ?, updated_at = now() WHERE id = ?",
                body.newPhone(), ownerAccountId);
        if (resetPassword) {
            jdbc.update("UPDATE account SET password_hash = ?, updated_at = now() WHERE id = ?",
                    bcrypt.encode(generatePassword()), ownerAccountId);   // 随机口令(审计 M-3)
        }

        // 审计（交接留痕 BR-M1-08；reset-password 敏感动作必落）：org.owner.rebind。
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("phone", oldPhone);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("phone", body.newPhone());
        after.put("passwordReset", resetPassword);
        audit.write(s, "org.owner.rebind", "account", String.valueOf(ownerAccountId),
                "PLATFORM", null, "负责人交接", before, after);

        return Map.of();   // 200 空体
    }

    // ── [4] GET /audit-log ────────────────────────────────────────────────────
    // x-data-scope=range，无 x-permission。
    @GetMapping("/audit-log")
    public Page<AuditLogDto> listAuditLog(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        // range：平台全量；物业/服务商 → 本组织成员产生的审计 + 平台代本组织操作的留痕。
        // BR-M1-15（M-07b）：proxy_for=本组织 id 的代操作记录（actor 为平台 SA/SE，不属本组织成员），
        // 被代方亦应可见——否则平台代物业/服务商所做操作对被代方不透明。proxy_for 为 TEXT，
        // 生产写路径（MasterWrite/Playbook/ProviderM3）存 String.valueOf(orgId)，故按字符串参数化比对。
        if (!s.isPlatform()) {
            where.append(" AND (actor_id IN (SELECT id FROM account WHERE org_id = ?)"
                    + " OR proxy_for = ?)");
            args.add(Long.valueOf(s.orgId()));
            args.add(s.orgId());
        }
        if (!isBlank(from)) {
            where.append(" AND tm >= ?::timestamptz");
            args.add(from);
        }
        if (!isBlank(to)) {
            where.append(" AND tm <= ?::timestamptz");
            args.add(to);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_log" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        List<AuditLogDto> items = jdbc.query(
                "SELECT id, actor, action, target, target_type, target_id, scope, proxy_for,"
                        + " before_snap, after_snap, reason, ip, trace_id, tm"
                        + " FROM audit_log" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                auditMapper(), pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [5] GET /permission-matrix ────────────────────────────────────────────
    // x-data-scope=platform，无 x-permission。纯内存：6 角色 × 全权限码笛卡尔展开。
    @GetMapping("/permission-matrix")
    public List<PermissionMatrixItemDto> getPermissionMatrix() {
        CurrentSubject s = SubjectContext.get();
        if (!s.isPlatform()) {
            throw new ApiException(BizError.PERM_403, "仅平台可查看权限矩阵");
        }
        List<PermissionMatrixItemDto> out = new ArrayList<>();
        // 全权限码 = 各角色 permissionsOf 并集（单一事实源 Permissions.of，与 AuthController 共用）。
        List<String> allPerms = allPermissions();
        for (String role : Permissions.ROLES) {
            Set<String> granted = Permissions.of(role);
            for (String perm : allPerms) {
                PermMeta meta = PERM_META.getOrDefault(perm, new PermMeta("其他", "own-org"));
                out.add(new PermissionMatrixItemDto(
                        meta.feature(), role, perm, meta.dataScope(), granted.contains(perm)));
            }
        }
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static final RowMapper<OrgDto> ORG_MAPPER = (rs, i) -> new OrgDto(
            String.valueOf(rs.getLong("id")),
            rs.getString("type"),
            rs.getString("name"),
            idOrNull(rs, "owner_account_id"),
            rs.getString("status"));

    private RowMapper<AuditLogDto> auditMapper() {
        return (rs, i) -> new AuditLogDto(
                String.valueOf(rs.getLong("id")),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("target"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("scope"),
                rs.getString("proxy_for"),
                parseJson(rs.getString("before_snap")),
                parseJson(rs.getString("after_snap")),
                rs.getString("reason"),
                rs.getString("ip"),
                rs.getString("trace_id"),
                ts(rs.getTimestamp("tm")));
    }

    /** JSONB 文本 → object（null→null）。解析失败容错返 null，绝不抛 5xx。 */
    private Object parseJson(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return json.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 全权限码并集（按 Permissions.of 各角色合并；permission-matrix 列空间）。 */
    private List<String> allPermissions() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String role : Permissions.ROLES) set.addAll(Permissions.of(role));
        List<String> list = new ArrayList<>(set);
        list.sort(String::compareTo);
        return list;
    }

    /** perm → feature/dataScope 静态元数据（本组内置；与契约 PermissionMatrixItem 语义对齐）。 */
    private record PermMeta(String feature, String dataScope) {}

    private static final Map<String, PermMeta> PERM_META = buildPermMeta();

    private static Map<String, PermMeta> buildPermMeta() {
        Map<String, PermMeta> m = new LinkedHashMap<>();
        m.put("proj.edit", new PermMeta("项目管理", "own-org"));
        m.put("reduce.policy.edit", new PermMeta("减免政策", "own-org"));
        m.put("batch.import", new PermMeta("批次导入", "own-org"));
        m.put("case.dispatch", new PermMeta("派单", "platform"));
        m.put("case.void", new PermMeta("批次作废", "own-org"));
        m.put("case.close", new PermMeta("结案", "range"));
        m.put("case.accept", new PermMeta("承接", "own-org"));
        m.put("case.assign", new PermMeta("分配", "own-org"));
        m.put("case.return", new PermMeta("退案", "own-org"));
        m.put("case.claim", new PermMeta("抢单", "own-org"));
        m.put("case.release", new PermMeta("释放", "case-holder"));
        m.put("case.follow", new PermMeta("跟进", "range"));
        m.put("case.call", new PermMeta("通话", "case-holder"));
        m.put("case.promise", new PermMeta("承诺", "case-actor"));
        m.put("case.ticket", new PermMeta("工单", "case-holder"));
        m.put("case.paylink", new PermMeta("缴费链接", "case-holder"));
        m.put("case.repay.mark", new PermMeta("标回款", "range"));
        m.put("case.reduce", new PermMeta("减免", "range"));
        m.put("evidence.create", new PermMeta("证据", "range"));
        m.put("legal.create", new PermMeta("法务", "range"));
        m.put("payreq.create", new PermMeta("支付申请单", "own-org"));
        m.put("payreq.complete", new PermMeta("支付申请单", "platform"));
        m.put("qc.review", new PermMeta("质检", "platform"));
        m.put("qc.dispose", new PermMeta("质检处置", "own-org"));
        m.put("qc.escalate", new PermMeta("质检上报", "range"));
        m.put("member.manage", new PermMeta("成员管理", "own-org"));
        m.put("report.export", new PermMeta("报表", "range"));
        m.put("cocomm.manage", new PermMeta("催收员佣金", "own-org"));
        m.put("cocomm.self.view", new PermMeta("我的佣金", "own-org"));
        return m;
    }

    // ── 低级工具 ──────────────────────────────────────────────────────────────

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static boolean isOrgType(String t) {
        return "PLATFORM".equals(t) || "PROPERTY".equals(t) || "PROVIDER".equals(t);
    }

    private static long parseIdOr404(String id, String what) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ApiException(BizError.NOT_FOUND_404, what + "不存在: " + id);
        }
    }

    private static String idOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : String.valueOf(v);
    }

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }
}
