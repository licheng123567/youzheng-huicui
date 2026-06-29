package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 个人中心自助改密 + 案件/业主搜索（v1.3.0 覆盖补点）。
 *   POST /me/password  changeOwnPassword —— 校验旧密码改本人密码(与管理员重置并存)，self。
 *   GET  /search       search           —— 案件搜索(业主/房号/户号/电话)，range 裁剪 + 未持有公海脱敏。
 */
@RestController
public class ProfileSearchController {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public ProfileSearchController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record SearchCaseHit(String caseId, String acctNo, String ownerName, String room,
                                Long dueCents, String status, String projectName) {}

    // ── POST /me/password ────────────────────────────────────────────────────
    @PostMapping("/me/password")
    public Map<String, Object> changeOwnPassword(@RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        String oldPw = reqStr(body, "oldPassword"), newPw = reqStr(body, "newPassword");
        if (newPw.length() < 6) throw new ApiException(BizError.VALIDATION_422, "新密码过弱(至少 6 位)");
        Long me = parseLong(s.accountId());
        if (me == null) throw new ApiException(BizError.AUTH_401, "无效主体");
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("SELECT password_hash FROM account WHERE id = ?", me);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.AUTH_401, "账号不存在");
        }
        String hash = (String) row.get("password_hash");
        if (hash == null || !bcrypt.matches(oldPw, hash)) throw new ApiException(BizError.AUTH_401, "旧密码错误");
        // M-a：改密同时清 must_change_password 标志（首次改密后解锁全部 API）。
        jdbc.update("UPDATE account SET password_hash = ?, must_change_password = FALSE, updated_at = now() WHERE id = ?",
                bcrypt.encode(newPw), me);
        return Map.of("ok", true);
    }

    // ── GET /search ──────────────────────────────────────────────────────────
    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam("q") String q,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        CurrentSubject s = SubjectContext.get();
        if (q == null || q.isBlank()) throw new ApiException(BizError.VALIDATION_422, "q 必填");
        String like = "%" + q.trim() + "%";
        String role = s.role() == null ? "" : s.role();
        Long me = parseLong(s.accountId());

        StringBuilder where = new StringBuilder(
            " WHERE (c.owner_name ILIKE ? OR c.room ILIKE ? OR c.acct_no ILIKE ?"
                + " OR EXISTS (SELECT 1 FROM contact ct WHERE ct.case_id = c.id AND ct.phone ILIKE ?))");
        List<Object> args = new ArrayList<>(List.of(like, like, like, like));
        // range 裁剪（统一收口）：SA 全量 / SE 三维 data_range / PROVIDER c.provider_id / PL p.org_id / PC 行级协调集。
        if (!s.isPlatform() && parseLong(s.orgId()) == null) {
            return Map.of("items", List.of(), "meta", meta(page, size, 0));
        }
        com.youzheng.huicui.common.DataScope.appendRange(
                s, where, args, "c.provider_id", "p.org_id", "p.area", "c.project_id", "c.batch_id");
        String base = "FROM \"case\" c JOIN project p ON p.id = c.project_id JOIN batch b ON b.id = c.batch_id" + where;
        Integer total = jdbc.queryForObject("SELECT count(*) " + base, Integer.class, args.toArray());
        int offset = Math.max(0, (page - 1) * size);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size); pageArgs.add(offset);
        boolean providerViewer = "CO".equals(role) || "VL".equals(role);
        List<SearchCaseHit> items = jdbc.query(
            "SELECT c.id, c.acct_no, c.owner_name, c.room, c.due_cents, c.status, c.project_name, c.pool, c.holder_id "
                + base + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
            (rs, i) -> {
                Long holder = (Long) rs.getObject("holder_id");
                boolean held = holder != null && me != null && holder.equals(me);
                // 未持有公海案件对服务商侧脱敏(BR-M3-21a)
                boolean masked = providerViewer && !held && !"PRIVATE".equals(rs.getString("pool"));
                String owner = masked ? "***" : rs.getString("owner_name");
                Long due = (Long) rs.getObject("due_cents");
                return new SearchCaseHit(String.valueOf(rs.getLong("id")), rs.getString("acct_no"),
                        owner, rs.getString("room"), due, rs.getString("status"), rs.getString("project_name"));
            }, pageArgs.toArray());
        return Map.of("items", items, "meta", meta(page, size, total == null ? 0 : total));
    }

    private static Map<String, Object> meta(int page, int size, int total) {
        return Map.of("page", page, "size", size, "total", total);
    }
    private String reqStr(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        String s = v == null ? null : String.valueOf(v);
        if (s == null || s.isBlank()) throw new ApiException(BizError.VALIDATION_422, k + " 必填");
        return s;
    }
    private static Long parseLong(String v) {
        try { return v == null ? null : Long.valueOf(v.trim()); } catch (RuntimeException e) { return null; }
    }
}
