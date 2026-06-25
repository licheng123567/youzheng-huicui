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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    // 契约 LoginResult：单账号返 token；多账号返 loginTicket+accounts(BR-M1-11)。
    public record LoginResult(String token, String loginTicket, Object accounts) {}

    // 多账号临时票据 / 短信验证码（骨架·内存；生产换 Redis 带 TTL）。
    private record Ticket(Set<Long> accountIds, long exp) {}
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, String> smsCodes = new ConcurrentHashMap<>();
    private static final long TICKET_TTL_MS = 5 * 60 * 1000L;
    private static final String DEV_SMS_CODE = "000000";   // 骨架：dev 固定码；真实经短信通道下发随机码

    @PostMapping("/login")
    public LoginResult login(@RequestBody Map<String, Object> body) {
        String mode = str(body, "mode");
        String phone;
        if ("sms".equals(mode)) {
            // 短信登录：phone+code（code 由 /auth/sms-code 下发；dev 固定 000000）
            phone = req(body, "phone"); String code = req(body, "code");
            String expected = smsCodes.get(phone);
            if (expected == null || !expected.equals(code)) throw new ApiException(BizError.AUTH_401, "验证码错误或已过期");
        } else {
            // 口令登录：username+password 认证后,取该账号 phone（一号多账号以 phone 聚合）
            String username = req(body, "username"), password = req(body, "password");
            Map<String, Object> row;
            try {
                row = jdbc.queryForMap("SELECT password_hash, status, phone FROM account WHERE username = ?", username);
            } catch (EmptyResultDataAccessException e) {
                throw new ApiException(BizError.AUTH_401, "用户名或口令错误");
            }
            String hash = (String) row.get("password_hash");
            if (hash == null || !bcrypt.matches(password, hash)) throw new ApiException(BizError.AUTH_401, "用户名或口令错误");
            if (!"ACTIVE".equals(row.get("status"))) throw new ApiException(BizError.PERM_403, "账号已停用");
            phone = (String) row.get("phone");
        }
        // 该 phone 全部 ACTIVE 账号（一号多账号 BR-M1-11）
        List<Map<String, Object>> accts = jdbc.queryForList(
                "SELECT a.id, a.name, a.role_template, o.name AS oname FROM account a JOIN org o ON o.id = a.org_id"
                        + " WHERE a.phone = ? AND a.status = 'ACTIVE' ORDER BY a.id", phone);
        if (accts.isEmpty()) throw new ApiException(BizError.AUTH_401, "无可用账号");
        if (accts.size() == 1) {
            return new LoginResult(issueFor(((Number) accts.get(0).get("id")).longValue()), null, null);
        }
        // 多账号 → 临时票据 + 账号列表（需 /auth/select-account 换 token）
        Set<Long> ids = new HashSet<>();
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (Map<String, Object> a : accts) {
            long aid = ((Number) a.get("id")).longValue();
            ids.add(aid);
            accounts.add(Map.of("accountId", String.valueOf(aid), "orgName", String.valueOf(a.get("oname")),
                    "role", String.valueOf(a.get("role_template")), "name", String.valueOf(a.get("name"))));
        }
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, new Ticket(ids, System.currentTimeMillis() + TICKET_TTL_MS));
        return new LoginResult(null, ticket, accounts);
    }

    @PostMapping("/sms-code")
    public Map<String, Object> smsCode(@RequestBody Map<String, Object> body) {
        String phone = req(body, "phone");
        // 骨架：dev 存固定码；生产经短信通道下发随机码 + 限流(429)。不回显 code。
        smsCodes.put(phone, DEV_SMS_CODE);
        return Map.of("sent", true, "ttlSeconds", 300);
    }

    @PostMapping("/select-account")
    public LoginResult selectAccount(@RequestBody Map<String, Object> body) {
        String ticketId = req(body, "loginTicket");
        long accountId;
        try { accountId = Long.parseLong(req(body, "accountId")); }
        catch (NumberFormatException e) { throw new ApiException(BizError.VALIDATION_422, "accountId 非法"); }
        Ticket tk = tickets.get(ticketId);
        if (tk == null || tk.exp() < System.currentTimeMillis()) {
            tickets.remove(ticketId);
            throw new ApiException(BizError.AUTH_401, "登录票据无效或已过期，请重新登录");
        }
        if (!tk.accountIds().contains(accountId)) {
            throw new ApiException(BizError.AUTH_401, "所选账号不在本次登录范围");
        }
        tickets.remove(ticketId);   // 一次性
        return new LoginResult(issueFor(accountId), null, null);
    }

    /** 按 accountId 加载账号+组织+有效权限 → 签发 JWT。 */
    private String issueFor(long accountId) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT a.id, a.name, a.role_template, a.permissions::text AS perms_json, a.status,"
                            + " o.id AS oid, o.type AS otype, o.name AS oname"
                            + " FROM account a JOIN org o ON a.org_id = o.id WHERE a.id = ?", accountId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.AUTH_401, "账号不存在");
        }
        if (!"ACTIVE".equals(row.get("status"))) throw new ApiException(BizError.PERM_403, "账号已停用");
        CurrentSubject s = new CurrentSubject(
                String.valueOf(row.get("id")), (String) row.get("name"),
                String.valueOf(row.get("oid")), (String) row.get("otype"), (String) row.get("oname"),
                (String) row.get("role_template"),
                effectivePerms((String) row.get("role_template"), (String) row.get("perms_json")));
        return jwt.issue(s);
    }

    private String str(Map<String, Object> b, String k) { Object v = b == null ? null : b.get(k); return v == null ? null : String.valueOf(v); }
    private String req(Map<String, Object> b, String k) {
        String v = str(b, k);
        if (v == null || v.isBlank()) throw new ApiException(BizError.VALIDATION_422, k + " 必填");
        return v;
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
