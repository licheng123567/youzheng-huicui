package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 消息中心（BR-M4-23 互推闭环）。通知归属 recipient_account_id；纯本人读写，越权 404/403。
 *   GET  /notifications?unreadOnly&page&size  listNotifications
 *   GET  /notifications/unread-count          getUnreadCount   —— 导航红点
 *   POST /notifications/{id}/read             markNotificationRead —— 仅本人
 */
@RestController
public class NotificationController {

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record NotificationDto(String id, String type, String title, String body,
                                  String refType, String refId, boolean read, String createdAt) {}

    @GetMapping("/notifications")
    public Map<String, Object> listNotifications(
            @RequestParam(value = "unreadOnly", required = false) Boolean unreadOnly,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        CurrentSubject s = SubjectContext.get();
        Long me = parseLong(s.accountId());
        if (me == null) return Map.of("items", List.of(), "meta", meta(page, size, 0));
        boolean unread = Boolean.TRUE.equals(unreadOnly);
        String where = "recipient_account_id = ?" + (unread ? " AND read = FALSE" : "");
        Integer total = jdbc.queryForObject("SELECT count(*) FROM notification WHERE " + where, Integer.class, me);
        int offset = Math.max(0, (page - 1) * size);
        List<NotificationDto> items = jdbc.query(
            "SELECT id, type, title, body, ref_type, ref_id, read, created_at FROM notification WHERE " + where
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> new NotificationDto(String.valueOf(rs.getLong("id")), rs.getString("type"),
                rs.getString("title"), rs.getString("body"), rs.getString("ref_type"),
                rs.getObject("ref_id") == null ? null : String.valueOf(rs.getLong("ref_id")),
                rs.getBoolean("read"), String.valueOf(rs.getObject("created_at"))),
            me, size, offset);
        return Map.of("items", items, "meta", meta(page, size, total == null ? 0 : total));
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Object> getUnreadCount() {
        CurrentSubject s = SubjectContext.get();
        Long me = parseLong(s.accountId());
        Integer c = me == null ? 0 : jdbc.queryForObject(
            "SELECT count(*) FROM notification WHERE recipient_account_id = ? AND read = FALSE", Integer.class, me);
        return Map.of("count", c == null ? 0 : c);
    }

    @PostMapping("/notifications/{id}/read")
    public Map<String, Object> markNotificationRead(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        Long me = parseLong(s.accountId());
        Long nid = parseLong(id);
        if (nid == null) throw new ApiException(BizError.NOT_FOUND_404, "通知不存在");
        // 仅本人：UPDATE 命中 0 行 → 区分不存在(404) / 非本人(403)
        int n = me == null ? 0 : jdbc.update(
            "UPDATE notification SET read = TRUE WHERE id = ? AND recipient_account_id = ?", nid, me);
        if (n == 0) {
            Integer exists = jdbc.queryForObject("SELECT count(*) FROM notification WHERE id = ?", Integer.class, nid);
            if (exists != null && exists > 0) throw new ApiException(BizError.PERM_403, "无权操作该通知");
            throw new ApiException(BizError.NOT_FOUND_404, "通知不存在");
        }
        return Map.of("ok", true);
    }

    private static Map<String, Object> meta(int page, int size, int total) {
        return Map.of("page", page, "size", size, "total", total);
    }

    private static Long parseLong(String v) {
        try { return v == null ? null : Long.valueOf(v.trim()); } catch (RuntimeException e) { return null; }
    }
}
