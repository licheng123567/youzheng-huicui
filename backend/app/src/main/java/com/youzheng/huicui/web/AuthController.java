package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.JwtService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

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
                "SELECT a.id, a.name, a.role_template, a.password_hash, a.status, " +
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
                (String) row.get("role_template"), permissionsOf((String) row.get("role_template")));
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

    /** 骨架：按角色给代表性权限点（生产从 permission 表/角色模板加载）。 */
    private Set<String> permissionsOf(String role) {
        return switch (role) {
            // 平台：派单/再派/开放抢单/作废 + 结算/质检/主数据
            case "SA", "SE" -> Set.of("proj.edit", "batch.import", "case.dispatch", "case.void",
                    "payreq.complete", "qc.review", "qc.escalate", "member.manage", "report.export");
            // 物业负责人/协调员
            case "PL", "PC" -> Set.of("proj.edit", "reduce.policy.edit", "case.follow", "case.paylink",
                    "case.repay.mark", "case.reduce", "evidence.create", "legal.create");
            // 服务商负责人：承接/拒接/分配/退案
            case "VL" -> Set.of("case.accept", "case.assign", "case.return", "cocomm.manage");
            // 催收员：抢单/释放/跟进/通话/承诺/工单/缴费链接/标回款
            case "CO" -> Set.of("case.claim", "case.release", "case.follow", "case.call",
                    "case.promise", "case.ticket", "case.paylink", "case.repay.mark", "cocomm.self.view");
            default -> Set.of();
        };
    }
}
