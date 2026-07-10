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

/**
 * 认证：登录签发 JWT（契约 /auth/login）。sms-code/select-account 为骨架占位。
 *
 * B-04方案A：
 *   - login：成功后检查 must_change_password；若 TRUE，LoginResult 携带 mustChangePassword=true，
 *     前端须强制跳转改密页（不可跳过）。token 仍签发，但前端应在改密完成前限制功能。
 *   - POST /auth/setup-password{token,newPassword}：消费一次性 setupToken（SHA-256 哈希匹配+
 *     TTL 24h+一次性 used_at），设 password_hash+must_change_password=FALSE；否则 401。
 *     此端点无需登录（security=[]）。
 *
 * ⚠ 单实例限定：loginTicket / 短信验证码 / 发送冷却 三者都存在**进程内存**里。
 *   多实例部署（或滚动发布期间新旧实例并存）会立刻出现：在 A 实例拿的票据到 B 实例换不到 token、
 *   冷却在实例间各算各的（限流形同虚设）。**横向扩容前必须先把这三者换成 Redis**。
 *   本次只补了过期清扫（sweepExpired），没有解决多实例问题 —— 别把它当成已解决。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final com.youzheng.huicui.integration.SmsService sms;
    private final org.springframework.core.env.Environment env;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final ObjectMapper om = new ObjectMapper();

    public AuthController(JdbcTemplate jdbc, JwtService jwt, com.youzheng.huicui.integration.SmsService sms,
                          org.springframework.core.env.Environment env) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.sms = sms;
        this.env = env;
    }

    /**
     * 契约 LoginResult：单账号返 token；多账号返 loginTicket+accounts(BR-M1-11)。
     * B-04方案A：mustChangePassword=true 时前端须强制跳转改密页（不可跳过）。
     */
    public record LoginResult(String token, String loginTicket, Object accounts, Boolean mustChangePassword) {}

    // 多账号临时票据 / 短信验证码（内存·带 TTL；生产换 Redis + 真实短信通道随机码）。
    private record Ticket(Set<Long> accountIds, long exp) {}
    private record SmsCode(String code, long exp) {}
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, SmsCode> smsCodes = new ConcurrentHashMap<>();
    private final Map<String, Long> smsLastSentAt = new ConcurrentHashMap<>();
    private static final long TICKET_TTL_MS = 5 * 60 * 1000L;
    private static final long SMS_TTL_MS = 5 * 60 * 1000L;
    private static final long SMS_RESEND_INTERVAL_MS = 60 * 1000L;

    /**
     * 过期清扫。三个 Map 原先只在「被正确消费」时删除条目 ——
     * 而 /auth/sms-code 是公开端点：攻击者用海量不同手机号刷它，就永远走不到消费那一步，
     * 条目只进不出 → 无上界内存增长。这里按 TTL 主动清。
     *
     * fixedDelay 60s：与最短的 SMS_RESEND_INTERVAL_MS 同量级，最坏多留一个周期的过期条目，无碍。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    void sweepExpired() {
        long now = System.currentTimeMillis();
        int before = tickets.size() + smsCodes.size() + smsLastSentAt.size();
        tickets.values().removeIf(t -> t.exp() < now);
        smsCodes.values().removeIf(c -> c.exp() < now);
        // 冷却记录只需保留一个 SMS_RESEND_INTERVAL_MS 窗口
        smsLastSentAt.entrySet().removeIf(e -> now - e.getValue() >= SMS_RESEND_INTERVAL_MS);
        int after = tickets.size() + smsCodes.size() + smsLastSentAt.size();
        if (before != after) log.debug("清扫过期鉴权临时态: {} → {}", before, after);
    }
    // dev 固定码：仅 dev profile 下发（smsCode 端点门控）；非 dev 且短信未启用 → 502 拒绝。
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
            // 码已被正确消费 → 解除该号的下发冷却：合法用户下次登录无需干等 60s。
            // 不削弱防轰炸：攻击者拿不到码就无法走到这里，冷却对其照常生效。
            smsLastSentAt.remove(phone);
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
            // B-04方案A：password_hash=NULL 说明账号尚未走 /auth/setup-password 设密，拒绝登录。
            if (hash == null || !bcrypt.matches(password, hash)) {
                throw new ApiException(BizError.AUTH_401, "用户名或口令错误");
            }
            if (!"ACTIVE".equals(row.get("status"))) throw new ApiException(BizError.PERM_403, "账号已停用");
            phone = (String) row.get("phone");
        }
        // 该 phone 全部 ACTIVE 账号（一号多账号 BR-M1-11）
        List<Map<String, Object>> accts = jdbc.queryForList(
                "SELECT a.id, a.name, a.role_template, o.name AS oname FROM account a JOIN org o ON o.id = a.org_id"
                        + " WHERE a.phone = ? AND a.status = 'ACTIVE' ORDER BY a.id", phone);
        if (accts.isEmpty()) throw new ApiException(BizError.AUTH_401, "无可用账号");
        if (accts.size() == 1) {
            long accountId = ((Number) accts.get(0).get("id")).longValue();
            boolean mustChange = mustChangePassword(accountId);
            return new LoginResult(issueFor(accountId), null, null, mustChange ? Boolean.TRUE : null);
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
        return new LoginResult(null, ticket, accounts, null);
    }

    @PostMapping("/sms-code")
    public org.springframework.http.ResponseEntity<Map<String, Object>> smsCode(@RequestBody Map<String, Object> body) {
        String phone = req(body, "phone");
        // 频控（契约声明 429）：同手机号 60s 内只发一次；已发未过期的 code 不失效，重试登录仍可用。
        long now = System.currentTimeMillis();
        Long last = smsLastSentAt.get(phone);
        if (last != null && now - last < SMS_RESEND_INTERVAL_MS) {
            return org.springframework.http.ResponseEntity.status(429).body(Map.of(
                    "sent", false, "message", "请求过于频繁，请稍后再试",
                    "retryAfterSeconds", (SMS_RESEND_INTERVAL_MS - (now - last)) / 1000));
        }
        // enabled：随机码经智讯云普通短信下发（绝不回显 code）。
        // 未启用：仅 dev profile 允许固定码 000000（供本地/E2E）；非 dev 一律拒绝——
        //   否则任意知道手机号的人可用固定码登录任意账号（上线评估阻断项，勿回退）。
        String code;
        if (sms.isEnabled()) {
            code = sms.sendVerificationCode(phone);
        } else if (env.matchesProfiles("dev")) {
            code = DEV_SMS_CODE;
        } else {
            throw new ApiException(BizError.BIZ_SMS_FAILED, "短信通道未启用，无法下发验证码");
        }
        smsLastSentAt.put(phone, now);
        smsCodes.put(phone, new SmsCode(code, now + SMS_TTL_MS));
        return org.springframework.http.ResponseEntity.ok(Map.of("sent", true, "ttlSeconds", SMS_TTL_MS / 1000));
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
        boolean mustChange = mustChangePassword(accountId);
        return new LoginResult(issueFor(accountId), null, null, mustChange ? Boolean.TRUE : null);
    }

    /**
     * B-04方案A：POST /auth/setup-password — 消费一次性 setupToken，设置账号密码并清除首登改密标志。
     *
     * 安全规则：
     *   1) token_hash 命中且 used_at IS NULL 且 expires_at > now() → 有效，继续；否则 401。
     *   2) 设 account.password_hash + must_change_password=FALSE。
     *   3) 设 credential_setup_token.used_at=now()（一次性，防重放）。
     *   4) 返回 200 + {ok:true}。
     *   此端点无需登录（security=[]，public）。
     */
    @PostMapping("/setup-password")
    public Map<String, Object> setupPassword(@RequestBody Map<String, Object> body) {
        String token = req(body, "token");
        String newPassword = req(body, "newPassword");
        if (newPassword.length() < 6) {
            throw new ApiException(BizError.VALIDATION_422, "新口令至少 6 位");
        }

        // 计算 SHA-256(token) → hash，与数据库比对。
        String hash = OrgSystemM1Controller.sha256hex(token);

        // 查有效令牌（未使用+未过期）并取 account_id。
        Long accountId = jdbc.query(
                "SELECT account_id FROM credential_setup_token"
                        + " WHERE token_hash = ? AND used_at IS NULL AND expires_at > now()"
                        + " LIMIT 1",
                rs -> rs.next() ? rs.getLong("account_id") : null,
                hash);
        if (accountId == null) {
            throw new ApiException(BizError.AUTH_401, "token 无效、已过期或已使用");
        }

        // 验证账号仍 ACTIVE（安全：不为已停用账号设密）。
        String status = jdbc.query(
                "SELECT status FROM account WHERE id = ?",
                rs -> rs.next() ? rs.getString("status") : null,
                accountId);
        if (!"ACTIVE".equals(status)) {
            throw new ApiException(BizError.PERM_403, "账号已停用，无法设置密码");
        }

        // 原子更新：设密码 + 清首登改密标志。
        String newHash = bcrypt.encode(newPassword);
        jdbc.update(
                "UPDATE account SET password_hash = ?, must_change_password = FALSE,"
                        + " updated_at = now() WHERE id = ?",
                newHash, accountId);

        // 消费 token（一次性：设 used_at，防重放）。
        jdbc.update(
                "UPDATE credential_setup_token SET used_at = now()"
                        + " WHERE token_hash = ? AND used_at IS NULL",
                hash);

        return Map.of("ok", true);
    }

    /** B-04方案A：查 account.must_change_password（列不存在时降级 false，避免启动期迁移未跑时 5xx）。 */
    private boolean mustChangePassword(long accountId) {
        try {
            Boolean v = jdbc.query(
                    "SELECT must_change_password FROM account WHERE id = ?",
                    rs -> rs.next() ? rs.getBoolean("must_change_password") : null,
                    accountId);
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            return false;   // 迁移未跑/列不存在时降级 false
        }
    }

    /** 按 accountId 加载账号+组织+有效权限 → 签发 JWT。 */
    private String issueFor(long accountId) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT a.id, a.name, a.role_template, a.permissions::text AS perms_json,"
                            + " a.data_range::text AS data_range_json, a.status,"
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
                effectivePerms((String) row.get("role_template"), (String) row.get("perms_json")),
                com.youzheng.huicui.security.DataRange.parse((String) row.get("data_range_json")));
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
