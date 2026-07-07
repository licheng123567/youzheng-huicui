package com.youzheng.huicui.web;

import com.youzheng.huicui.dispatch.RecordingService;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 跟进记录附件（图片/文件）：桌面直接选文件上传 + 扫码上传（手机公开上传页→桌面轮询自动附上）。
 * 附件字节存 Postgres bytea（dev/demo，同录音 audio_bytes V921）。非 OpenAPI 契约端点（含二进制流 + 公开上传）。
 *
 * 端点（裸路径，/v1 由 context-path 提供）：
 *   POST /cases/{id}/attachments         uploadAttachment      | case.follow + case-actor(caseVisible)      | {id,name,url}
 *   GET  /attachments/{id}               streamAttachment      | case-actor(caseVisible)                    | 二进制流/404
 *   POST /cases/{id}/upload-sessions     createUploadSession   | case.follow + case-actor                   | {token}
 *   GET  /upload-sessions/{token}        pollUploadSession      | case.follow + case-actor(session 的案件)    | {items:[{id,name,url}]}
 *   POST /upload-sessions/{token}/file   publicSessionUpload   | 公开·无鉴权（JwtAuthFilter 白名单）         | {ok:true}
 *
 * 复用 {@link RecordingService#caseVisible}/{@link RecordingService#caseExists} 做案件可见性（case-actor 裁剪）。
 */
@RestController
public class AttachmentController {

    private final JdbcTemplate jdbc;
    private final RecordingService rec;

    public AttachmentController(JdbcTemplate jdbc, RecordingService rec) {
        this.jdbc = jdbc;
        this.rec = rec;
    }

    private static final long MAX_BYTES = 20L * 1024 * 1024;   // 20MB 软上限
    private static final long SESSION_TTL_SECONDS = 15L * 60;   // 扫码会话 15 分钟

    private long parseId(String id) {
        try { return Long.parseLong(id); }
        catch (NumberFormatException e) { throw new ApiException(BizError.NOT_FOUND_404, "无效 id: " + id); }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少文件 file");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(BizError.VALIDATION_422, "文件超过 20MB 上限");
        }
    }

    private String safeName(MultipartFile file) {
        String n = file.getOriginalFilename();
        return (n == null || n.isBlank()) ? "attachment" : n;
    }

    private long insertAttachment(long caseId, String sessionToken, MultipartFile file, Long uploadedBy) {
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (Exception e) { throw new ApiException(BizError.VALIDATION_422, "文件读取失败"); }
        return jdbc.queryForObject(
                "INSERT INTO case_attachment(case_id, session_token, filename, content_type, bytes, uploaded_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, caseId, sessionToken, safeName(file),
                file.getContentType(), bytes, uploadedBy);
    }

    // ── [1] POST /cases/{id}/attachments ─────────────────────────────────────────
    @PostMapping("/cases/{id}/attachments")
    @RequirePermission("case.follow")
    @Transactional
    public Map<String, Object> uploadAttachment(@PathVariable("id") String id,
                                                @RequestParam(value = "file", required = false) MultipartFile file) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        if (!rec.caseExists(caseId)) throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + id);
        if (!rec.caseVisible(s, caseId)) throw new ApiException(BizError.PERM_403, "无权操作该案件");
        validateFile(file);
        Long me = parseUploader(s);
        long attId = insertAttachment(caseId, null, file, me);
        return Map.of("id", String.valueOf(attId), "name", safeName(file), "url", "/v1/attachments/" + attId);
    }

    // ── [2] GET /attachments/{id} ────────────────────────────────────────────────
    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> streamAttachment(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long attId = parseId(id);
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("SELECT case_id, content_type, bytes FROM case_attachment WHERE id = ?", attId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "附件不存在: " + id);
        }
        long caseId = ((Number) row.get("case_id")).longValue();
        if (!rec.caseVisible(s, caseId)) throw new ApiException(BizError.PERM_403, "无权查看该附件");
        String ct = row.get("content_type") == null ? "application/octet-stream" : (String) row.get("content_type");
        return ResponseEntity.ok().header("Content-Type", ct).header("Cache-Control", "private, max-age=60")
                .body((byte[]) row.get("bytes"));
    }

    // ── [3] POST /cases/{id}/upload-sessions ─────────────────────────────────────
    @PostMapping("/cases/{id}/upload-sessions")
    @RequirePermission("case.follow")
    @Transactional
    public Map<String, Object> createUploadSession(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id);
        if (!rec.caseExists(caseId)) throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + id);
        if (!rec.caseVisible(s, caseId)) throw new ApiException(BizError.PERM_403, "无权操作该案件");
        String token = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO upload_session(token, case_id, expires_at) VALUES (?, ?, ?)",
                token, caseId, Timestamp.from(Instant.now().plusSeconds(SESSION_TTL_SECONDS)));
        return Map.of("token", token);
    }

    // ── [4] GET /upload-sessions/{token} （桌面轮询） ─────────────────────────────
    @GetMapping("/upload-sessions/{token}")
    @RequirePermission("case.follow")
    public Map<String, Object> pollUploadSession(@PathVariable("token") String token) {
        CurrentSubject s = SubjectContext.get();
        Long caseId = sessionCaseId(token);   // 不存在/过期→404
        if (!rec.caseVisible(s, caseId)) throw new ApiException(BizError.PERM_403, "无权查看该会话");
        List<Map<String, Object>> items = jdbc.query(
                "SELECT id, filename FROM case_attachment WHERE session_token = ? ORDER BY id",
                (rs, i) -> Map.of("id", String.valueOf(rs.getLong("id")),
                        "name", rs.getString("filename"),
                        "url", "/v1/attachments/" + rs.getLong("id")),
                token);
        return Map.of("items", items);
    }

    // ── [5] POST /upload-sessions/{token}/file （公开·手机扫码上传） ───────────────
    @PostMapping("/upload-sessions/{token}/file")
    @Transactional
    public Map<String, Object> publicSessionUpload(@PathVariable("token") String token,
                                                   @RequestParam(value = "file", required = false) MultipartFile file) {
        Long caseId = sessionCaseId(token);   // 不存在/过期→404
        validateFile(file);
        insertAttachment(caseId, token, file, null);
        return Map.of("ok", true);
    }

    /** token → 有效会话的 case_id；不存在或已过期→404。 */
    private Long sessionCaseId(String token) {
        List<Long> ids = jdbc.query(
                "SELECT case_id FROM upload_session WHERE token = ? AND expires_at > now()",
                (rs, i) -> rs.getLong("case_id"), token);
        if (ids.isEmpty()) throw new ApiException(BizError.NOT_FOUND_404, "上传会话不存在或已过期");
        return ids.get(0);
    }

    /** 当前主体 account id（用于 uploaded_by）；解析失败返 null（不阻断）。 */
    private Long parseUploader(CurrentSubject s) {
        try { return Long.parseLong(s.accountId()); }
        catch (Exception e) { return null; }
    }
}
