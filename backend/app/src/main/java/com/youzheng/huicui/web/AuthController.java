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

    // 多账号临时票据 / 短信验证码（内存·带 TTL；生产换 Redis + 真实短信通道随机码）。
    private record Ticket(Set<Long> accountIds, long exp) {}
    private record SmsCode(String code, long exp) {}
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, SmsCode> smsCodes = new ConcurrentHashMap<>();
    private static final long TICKET_TTL_MS = 5 * 60 * 1000L;
    private static final long SMS_TTL_MS = 5 * 60 * 1000L;
    // dev 固定码（仅 dev：DevSeeder 在跑即视为 dev）。生产务必改随机码经短信通道下发，且不得回显前端。
    private static final String DEV_SMS_CODE = "000000";

    @PostMapping("/login")
    public LoginResult login(@RequestBody Map<String, Object> body) {
        String mode = str(body, "mode");
        String phone;
        if ("sms".equals(mode)) {
            // 短信登录：phone+code（/auth/sms-code 下发）。校验 TTL + 一次性删除（防重放）。
            phone = req(body, "phone"); String code = req(body, "code");
            SmsCode sc = smsCodes.get(phone);
            if (sc == null || sc.exp() < System.currentTimeMillis() || !sc.code().equals(code)) {
                smsCodes.remove(phone);   // 过期/错误即清，避免残留可猜
                throw new ApiException(BizError.AUTH_401, "验证码错误或已过期");
            }
            smsCodes.remove(phone);       // 一次性：用后即焚，防重放
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
        // dev 存固定码 + TTL（生产：随机码经短信通道下发 + 限流429）。绝不回显 code。
        smsCodes.put(phone, new SmsCode(DEV_SMS_CODE, System.currentTimeMillis() + SMS_TTL_MS));
        return Map.of("sent", true, "ttlSeconds", SMS_TTL_MS / 1000);
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
