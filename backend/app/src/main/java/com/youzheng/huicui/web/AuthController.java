package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 认证：登录签发 JWT（契约 /auth/login）。sms-code/select-account 为骨架占位。 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final ObjectMapper om = new ObjectMapper();

    public AuthController(JdbcTemplate jdbc, JwtService jwt) {
        this.jdbc = jdbc;
        this.jwt = jwt;
    }

    public record LoginByPassword(String mode, String username, String password) {}
    // 契约 LoginResult：单账号返 token；多账号返 loginTicket+accounts（本地基期均单账号）。
    public record LoginResult(String token, String loginTicket, Object accounts) {}

    @PostMapping("/login")
    public LoginResult login(@RequestBody LoginByPassword body) {
        if (body == null || body.username() == null || body.password() == null) {
            throw new ApiException(BizError.VALIDATION_422, "用户名/口令必填");
        }
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                "SELECT a.id, a.name, a.role_template, a.password_hash, a.status, a.permissions::text AS perms_json, " +
                "       o.id AS oid, o.type AS otype, o.name AS oname " +
                "FROM account a JOIN org o ON a.org_id = o.id WHERE a.username = ?", body.username());
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.AUTH_401, "用户名或口令错误");   // 不区分用户存在与否
        }
        String hash = (String) row.get("password_hash");
        if (hash == null || !bcrypt.matches(body.password(), hash)) {
            throw new ApiException(BizError.AUTH_401, "用户名或口令错误");
        }
        if (!"ACTIVE".equals(row.get("status"))) {
            throw new ApiException(BizError.PERM_403, "账号已停用");
        }
        CurrentSubject s = new CurrentSubject(
                String.valueOf(row.get("id")), (String) row.get("name"),
                String.valueOf(row.get("oid")), (String) row.get("otype"), (String) row.get("oname"),
                (String) row.get("role_template"),
                effectivePerms((String) row.get("role_template"), (String) row.get("perms_json")));
        return new LoginResult(jwt.issue(s), null, null);   // 单账号：token；loginTicket/accounts 为 null
    }

    @PostMapping("/sms-code")
    public Map<String, Object> smsCode(@RequestBody Map<String, Object> body) {
        // 骨架：真实实现接短信通道 + 限流 + 验证码存储（Redis）。
        return Map.of("sent", true, "ttlSeconds", 300);
    }

    @PostMapping("/select-account")
    public Map<String, Object> selectAccount(@RequestBody Map<String, Object> body) {
        // 骨架：校验 loginTicket → 按 accountId 签发 token。多账号链路 M1 完整实现。
        throw new ApiException(BizError.VALIDATION_422, "多账号选择待 M1 完整实现");
    }

    /**
     * 有效权限(审计 M-1 落地 BR-M1-03 子集授权)：角色全集 ∩ account.permissions 被授予子集。
     * account.permissions 为空/null → 用角色全集；非空 → 取交集(绝不超过角色，降权真正生效)。
     * 解析失败回退角色全集(不放大权限)。单一来源见 {@link com.youzheng.huicui.common.Permissions}。
     */
    private Set<String> effectivePerms(String role, String permsJson) {
        Set<String> rolePerms = com.youzheng.huicui.common.Permissions.of(role);
        if (permsJson == null || permsJson.isBlank()) return rolePerms;
        try {
            List<String> granted = om.readValue(permsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (granted == null || granted.isEmpty()) return rolePerms;
            Set<String> eff = new HashSet<>(rolePerms);
            eff.retainAll(granted);   // 交集：被授予子集真正缩小实际权限，且不可超过角色
            return eff;
        } catch (Exception e) {
            return rolePerms;
        }
    }
}
