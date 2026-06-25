package com.youzheng.huicui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.audit.AuditService;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.dispatch.CaseStateService;
import com.youzheng.huicui.dispatch.CaseStateService.CaseSnapshot;
import com.youzheng.huicui.dispatch.CaseStateService.Transition;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.MemberDto;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M1 组织成员管理写/读端点（横切层范式 + scaffold 助手）。类名带 M1 后缀，与既有
 * M2-M10 controller 物理隔离，不碰共享件/其他组/pom。督导端点见 {@link SupervisionM10Controller}（同模块，类后缀隔离）。
 *
 * 端点（基路径 /v1 由 context-path 提供，方法注解写裸路径）：
 *   GET  /members                       listMembers          | scope=range（平台见全量但跨组织只读；非平台=本组织）
 *   POST /members                       createMember         | perm=member.manage | scope=own-org | 201/403/409/422
 *   PATCH/members/{id}                  updateMember         | perm=member.manage | scope=own-org | 200/403/404/422
 *   POST /members/{id}/disable          disableMember        | perm=member.manage | scope=own-org | 触发私海释放
 *   POST /members/{id}/enable           enableMember         | perm=member.manage | scope=own-org
 *   POST /members/{id}/reset-password   resetMemberPassword  | perm=member.manage | scope=own-org | BCrypt
 *
 * 绝不 5xx（横切共性）：资源不存在/越 scope→404；无权限/平台改跨组织/越子集→403；状态非法（停用 owner/名重复）→409；
 *   入参/枚举/必填→422。错误经 GlobalExceptionHandler 统一信封。account JSONB 列 permissions/data_range
 *   用 ?::jsonb 写、Jackson readTree 读。敏感动作（create/disable/enable/reset_password/update）必落 audit_log。
 */
@RestController
public class MemberM1Controller {

    private static final Set<String> ROLE_ENUM = Set.of("SA", "SE", "PL", "PC", "VL", "CO");
    private static final String DEV_INITIAL_PASSWORD = "Huicui@123";   // 创建员工 dev 初始口令

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final BCryptPasswordEncoder bcrypt;
    private final CaseStateService state;
    private final SecureRandom rnd = new SecureRandom();

    public MemberM1Controller(JdbcTemplate jdbc, ObjectMapper json, AuditService audit,
                              CaseStateService state) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.bcrypt = new BCryptPasswordEncoder();
        this.state = state;
    }

    // ── 成员行快照 ────────────────────────────────────────────────────────────
    private record MemberRow(long id, long orgId, String username, String name, String phone,
                             String role, String status, boolean isOwner) {}

    // ── [1] GET /members ──────────────────────────────────────────────────────
    // 无 x-permission（列表靠 scope 控可见性）。range：平台无过滤（全量）；非平台 AND org_id=本组织。
    @GetMapping("/members")
    public Page<MemberDto> listMembers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (role != null && !role.isBlank()) {
            if (!ROLE_ENUM.contains(role)) {
                throw new ApiException(BizError.VALIDATION_422, "role 非法枚举: " + role);
            }
            where.append(" AND role_template = ?");
            args.add(role);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            args.add(status);
        }
        // range：平台全量；非平台限本组织。
        if (!s.isPlatform()) {
            where.append(" AND org_id = ?");
            args.add(orgIdLong(s));
        }

        String base = "FROM account" + where;
        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        String listSql = "SELECT id, org_id, username, name, phone, role_template, status, is_owner "
                + base + " ORDER BY id DESC LIMIT ? OFFSET ?";
        List<MemberDto> items = jdbc.query(listSql,
                (rs, i) -> toDto(s, new MemberRow(
                        rs.getLong("id"), rs.getLong("org_id"), rs.getString("username"),
                        rs.getString("name"), rs.getString("phone"), rs.getString("role_template"),
                        rs.getString("status"), rs.getBoolean("is_owner"))),
                pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [2] POST /members ─────────────────────────────────────────────────────
    @PostMapping("/members")
    @RequirePermission("member.manage")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public MemberDto createMember(@RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        String username = requireStr(body, "username");
        String name = requireStr(body, "name");
        String phone = requireStr(body, "phone");
        String role = requireStr(body, "role");
        if (!ROLE_ENUM.contains(role)) {
            throw new ApiException(BizError.VALIDATION_422, "role 非法枚举: " + role);
        }
        List<String> permissions = readStringList(body, "permissions");

        // own-org 角色口径（BR-M1-04a）：
        if (s.isPlatform()) {
            // 平台只建平台员工（role∈SA/SE），不代建跨组织成员。
            if (!"SA".equals(role) && !"SE".equals(role)) {
                throw new ApiException(BizError.PERM_403, "平台不代建跨组织成员，只可建平台员工(SA/SE)");
            }
        } else {
            // 非平台：角色须匹配本组织类型，且不可建 owner 角色。
            String orgType = s.orgType();
            if ("PROPERTY".equals(orgType)) {
                if (!"PC".equals(role)) {
                    throw new ApiException(BizError.PERM_403, "物业负责人只可建协调员(PC)");
                }
            } else if ("PROVIDER".equals(orgType)) {
                if (!"CO".equals(role)) {
                    throw new ApiException(BizError.PERM_403, "服务商负责人只可建催收员(CO)");
                }
            } else {
                throw new ApiException(BizError.PERM_403, "当前组织类型不可建员工");
            }
        }

        // permissions 越子集校验（BR-M1-03）：员工权限须 ⊆ 操作人权限集。
        if (permissions != null && !permissions.isEmpty()) {
            requireSubset(s, permissions);
        }

        long orgId = orgIdLong(s);   // 均落操作人本组织
        String permJson = toJsonArrayOrNull(permissions);
        String passwordHash = bcrypt.encode(DEV_INITIAL_PASSWORD);

        Long newId;
        try {
            newId = jdbc.queryForObject(
                    "INSERT INTO account(org_id, username, name, phone, role_template, status,"
                            + " is_owner, permissions, password_hash)"
                            + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', FALSE, ?::jsonb, ?) RETURNING id",
                    Long.class,
                    orgId, username, name, phone, role, permJson, passwordHash);
        } catch (DuplicateKeyException e) {
            // uq_account_username 冲突 → 业务幂等/重复，409。
            throw new ApiException(BizError.STATE_409, "用户名已存在: " + username);
        }
        if (newId == null) {
            throw new ApiException(BizError.STATE_409, "创建成员失败: " + username);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("username", username);
        after.put("role", role);
        after.put("permissions", permissions == null ? List.of() : permissions);
        audit.write(s, "member.create", "account", String.valueOf(newId), null, null, after);

        // 新建成员一定属操作人本组织 → manageable=true。
        return new MemberDto(String.valueOf(newId), String.valueOf(orgId), username, name, phone,
                role, "ACTIVE", false, true);
    }

    // ── [3] PATCH /members/{id} ───────────────────────────────────────────────
    @PatchMapping("/members/{id}")
    @RequirePermission("member.manage")
    @Transactional
    public MemberDto updateMember(@PathVariable("id") String id,
                                  @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long memberId = parseId(id);

        MemberRow target = loadManageable(s, memberId);   // 不存在→404；越 own-org/平台改跨组织→403

        // is_owner 成员降权/改 → 403（BR-M1-12 负责人常驻）。
        if (target.isOwner()) {
            throw new ApiException(BizError.PERM_403, "负责人账号常驻，不可调整权限/数据范围");
        }

        String name = optStr(body, "name");
        List<String> permissions = readStringList(body, "permissions");
        Object dataScope = body == null ? null : body.get("dataScope");

        if (permissions != null && !permissions.isEmpty()) {
            requireSubset(s, permissions);   // 越子集→403
        }
        // dataScope 仅平台员工(SE)可设；非 SE 传 dataScope→422（BR-M1-14 平台员工三维数据范围授权）。
        if (dataScope != null && !"SE".equals(target.role())) {
            throw new ApiException(BizError.VALIDATION_422, "数据范围仅平台员工(SE)可设");
        }

        // before 快照（现状）。
        Map<String, Object> currentPerm = readJsonbAsList(memberId, "permissions");
        Map<String, Object> currentScope = readJsonbAsObject(memberId, "data_range");
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", target.name());
        before.put("permissions", currentPerm.get("v"));
        before.put("dataScope", currentScope.get("v"));

        String permJson = permissions == null ? null : toJsonArrayOrNull(permissions);
        String scopeJson = dataScope == null ? null : toJsonOrNull(dataScope);

        // permissions/data_range：传 null 时保持原值（COALESCE on jsonb）；name 同。
        jdbc.update(
                "UPDATE account SET name = COALESCE(?, name),"
                        + " permissions = COALESCE(?::jsonb, permissions),"
                        + " data_range = COALESCE(?::jsonb, data_range),"
                        + " updated_at = now() WHERE id = ?",
                name, permJson, scopeJson, memberId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name == null ? target.name() : name);
        after.put("permissions", permissions == null ? currentPerm.get("v") : permissions);
        after.put("dataScope", dataScope == null ? currentScope.get("v") : dataScope);
        audit.write(s, "member.update", "account", String.valueOf(memberId), null, before, after);

        MemberRow updated = loadRow(memberId);
        return toDto(s, updated);
    }

    // ── [4] POST /members/{id}/disable ────────────────────────────────────────
    @PostMapping("/members/{id}/disable")
    @RequirePermission("member.manage")
    @Transactional
    public Map<String, Object> disableMember(@PathVariable("id") String id,
                                             @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long memberId = parseId(id);
        String reason = optStr(body, "reason");

        MemberRow target = loadManageable(s, memberId);   // 不存在→404；越 own-org/平台改跨组织→403
        // is_owner=true → 409（负责人账号常驻不停用 BR-M1-12）。
        if (target.isOwner()) {
            throw new ApiException(BizError.STATE_409, "负责人账号常驻，不可停用");
        }

        jdbc.update("UPDATE account SET status = 'DISABLED', updated_at = now()"
                + " WHERE id = ? AND is_owner = FALSE", memberId);

        // 私海释放（BR-M1-07）：对该成员持有的私海案件回服务商公海（复用 CaseStateService CAS Transition）。
        int released = releasePrivateCases(s, memberId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "DISABLED");
        after.put("releasedCases", released);
        audit.write(s, "member.disable", "account", String.valueOf(memberId), reason, null, after);
        return Map.of("ok", true, "releasedCases", released);
    }

    // ── [5] POST /members/{id}/enable ─────────────────────────────────────────
    @PostMapping("/members/{id}/enable")
    @RequirePermission("member.manage")
    @Transactional
    public Map<String, Object> enableMember(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long memberId = parseId(id);

        loadManageable(s, memberId);   // 不存在→404；越 own-org/平台改跨组织→403

        jdbc.update("UPDATE account SET status = 'ACTIVE', updated_at = now() WHERE id = ?", memberId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "ACTIVE");
        audit.write(s, "member.enable", "account", String.valueOf(memberId), null, null, after);
        return Map.of("ok", true);
    }

    // ── [6] POST /members/{id}/reset-password ─────────────────────────────────
    @PostMapping("/members/{id}/reset-password")
    @RequirePermission("member.manage")
    @Transactional
    public Map<String, Object> resetMemberPassword(@PathVariable("id") String id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long memberId = parseId(id);

        loadManageable(s, memberId);   // 不存在→404；越 own-org/平台改跨组织→403

        Object pwRaw = body == null ? null : body.get("newPassword");
        boolean notify = boolVal(body, "notify");
        String pw = (pwRaw == null || String.valueOf(pwRaw).isBlank())
                ? generatePassword() : String.valueOf(pwRaw);

        jdbc.update("UPDATE account SET password_hash = ?, updated_at = now() WHERE id = ?",
                bcrypt.encode(pw), memberId);

        // notify==true → 短信通知骨架（占位，不阻断）。
        // TODO: 接短信通道下发。当前仅记审计 notify 标记。

        // 审计：绝不记明文/哈希。
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("passwordReset", true);
        after.put("notify", notify);
        audit.write(s, "member.reset_password", "account", String.valueOf(memberId),
                "重置密码", null, after);

        // 不回传明文口令(审计 H-2)：明文经响应体会落日志/前端状态/APM。
        // 生产应带外下发(短信/邮件 notify 通道)；契约 reset-password 响应仅 200 ok。
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        return resp;
    }

    // ── helpers：加载/可管校验 ─────────────────────────────────────────────────

    /**
     * 加载成员并校验 own-org 可管：
     *   不存在→404；
     *   非平台主体且 target.org_id≠subject.orgId→403（跨组织）；
     *   平台主体改跨组织成员（target.org_id≠subject.orgId）→403（BR-M1-04a 平台对跨组织成员只读）。
     */
    private MemberRow loadManageable(CurrentSubject s, long memberId) {
        MemberRow row = loadRow(memberId);          // 不存在→404
        long subjOrg = orgIdLong(s);
        if (row.orgId() != subjOrg) {
            // 平台与非平台一致：写动作只能管本组织成员（平台对跨组织成员只读 BR-M1-04a）。
            throw new ApiException(BizError.PERM_403, "无权管理跨组织成员: " + memberId);
        }
        return row;
    }

    private MemberRow loadRow(long memberId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, org_id, username, name, phone, role_template, status, is_owner"
                            + " FROM account WHERE id = ?",
                    (rs, i) -> new MemberRow(
                            rs.getLong("id"), rs.getLong("org_id"), rs.getString("username"),
                            rs.getString("name"), rs.getString("phone"), rs.getString("role_template"),
                            rs.getString("status"), rs.getBoolean("is_owner")),
                    memberId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "成员不存在: " + memberId);
        }
    }

    /** manageable 派生（BR-M1-04a）：平台→row.orgId==subject.orgId；非平台→true（仅见本组织）。 */
    private MemberDto toDto(CurrentSubject s, MemberRow r) {
        boolean manageable = s.isPlatform()
                ? r.orgId() == orgIdLong(s)
                : true;
        return new MemberDto(String.valueOf(r.id()), String.valueOf(r.orgId()), r.username(),
                r.name(), r.phone(), r.role(), r.status(), r.isOwner(), manageable);
    }

    /** 停用释放：对该成员持有的私海案件逐件 CAS 转回服务商公海（承诺/待办随案保留 BR-M4-10）。 */
    private int releasePrivateCases(CurrentSubject s, long memberId) {
        List<Long> heldCaseIds = jdbc.queryForList(
                "SELECT id FROM \"case\" WHERE holder_id = ? AND pool = 'PRIVATE'",
                Long.class, memberId);
        int released = 0;
        for (Long caseId : heldCaseIds) {
            CaseSnapshot snap = state.lockCase(caseId);
            if (!CaseStateService.POOL_PRIVATE.equals(snap.pool())
                    || snap.holderId() == null || snap.holderId() != memberId) {
                continue;   // 并发已变更，跳过
            }
            // 回服务商公海（PROVIDER_SEA）：清 holder、source=RELEASE。期望持有人=被停用成员本人。
            Transition t = new Transition(
                    snap.status(), snap.pool(), memberId,
                    CaseStateService.ST_PROVIDER_SEA, CaseStateService.POOL_PROVIDER_SEA,
                    null, "RELEASE", null, null, null);
            int n = state.transition(caseId, t);
            if (n > 0) {
                released++;
                state.audit(s, "case.release", caseId, "成员停用自动释放私海", snap,
                        state.lockCase(caseId));
            }
        }
        return released;
    }

    // ── helpers：权限子集/JSONB/入参 ──────────────────────────────────────────

    /** permissions ⊆ 操作人权限集（BR-M1-03）；越子集→403 PERM_403。 */
    private void requireSubset(CurrentSubject s, List<String> permissions) {
        Set<String> allowed = s.permissions();
        for (String p : permissions) {
            if (p == null || !allowed.contains(p)) {
                throw new ApiException(BizError.PERM_403, "授予的权限点越出操作人权限子集: " + p);
            }
        }
    }

    /** 读 account.permissions（jsonb array）→ Map{"v": List<String>}（null 安全）。 */
    private Map<String, Object> readJsonbAsList(long memberId, String col) {
        String raw = jdbc.query("SELECT " + col + "::text AS v FROM account WHERE id = ?",
                rs -> rs.next() ? rs.getString("v") : null, memberId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", parseJsonNode(raw));
        return m;
    }

    /** 读 account.data_range（jsonb object）→ Map{"v": Object}（null 安全）。 */
    private Map<String, Object> readJsonbAsObject(long memberId, String col) {
        return readJsonbAsList(memberId, col);   // 同读法，Jackson readTree 区分 array/object
    }

    private Object parseJsonNode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode n = json.readTree(raw);
            return json.treeToValue(n, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJsonArrayOrNull(List<String> list) {
        if (list == null) return null;
        try {
            return json.writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJsonOrNull(Object o) {
        if (o == null) return null;
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringList(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        if (!(v instanceof List<?> raw)) {
            throw new ApiException(BizError.VALIDATION_422, key + " 须为字符串数组");
        }
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    private static String requireStr(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 " + key);
        }
        return String.valueOf(v).trim();
    }

    private static String optStr(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        return String.valueOf(v).trim();
    }

    private static boolean boolVal(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private String generatePassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        int len = 8 + rnd.nextInt(5);   // 8-12 位
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 路径 id 非法形态统一 404，避免存在性泄漏 / 防 5xx。 */
    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "成员不存在: " + id);
        }
    }

    private static long orgIdLong(CurrentSubject s) {
        try {
            return Long.parseLong(s.orgId());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.PERM_403, "无组织上下文");
        }
    }
}
