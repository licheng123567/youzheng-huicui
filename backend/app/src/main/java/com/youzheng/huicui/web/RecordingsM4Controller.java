package com.youzheng.huicui.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.common.Page;
import com.youzheng.huicui.common.Pageable;
import com.youzheng.huicui.common.RoleResponse;
import com.youzheng.huicui.dispatch.RecordingService;
import com.youzheng.huicui.dispatch.RecordingService.RecSnapshot;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.security.RequirePermission;
import com.youzheng.huicui.security.SubjectContext;
import com.youzheng.huicui.web.dto.AiReviewDto;
import com.youzheng.huicui.web.dto.CallRecordingDto;
import com.youzheng.huicui.web.dto.LatestRecordingDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M4 recordings 组端点（横切层范式 + scaffold {@link RecordingService} 行锁/状态机助手）。
 * 类名带 M4 后缀，与 M1/M2/M3 controller 物理隔离；只承载本组 6+1 端点。
 * 冻结契约：docs/api/openapi-core.yaml（recordings/{id}* + cases/{id}/recordings*）。PRD 04/05。
 *
 * 录音模型 BR-M4-01b：服务端绝不拉本机录音目录/主动外呼/感知拨打——那是 App 客户端本地行为。
 * 服务端只提供 upload + 状态轮询。地基期不真正跑 ASR，upload/reprocess/parse 一律落 PARSING。
 *
 * 端点（基路径 /v1 由 server.servlet.context-path 提供，注解写裸路径）：
 *   POST /cases/{id}/recordings        uploadRecording   | perm=case.call   scope=case-actor 幂等 multipart | 202 CallRecording
 *   GET  /cases/{id}/recordings/latest getLatestRecording| perm=case.call   scope=case-actor               | 200 LatestRecording
 *   GET  /recordings/{id}              getRecording      |                  scope=case-actor               | 200 CallRecording/404
 *   POST /recordings/{id}/reprocess    reprocessRecording| perm=case.call   scope=case-actor 幂等          | 202; 仅 FAILED→PARSING 否则 409
 *   POST /recordings/{id}/parse        parseRecording    | perm=case.call   scope=case-actor 幂等          | 202; QUOTA_BLOCKED/READY→PARSING
 *   GET  /recordings                   listRecordings    |                  scope=range                    | 200 CallRecordingPage
 *   GET  /recordings/{id}/ai-review    getAiReview       |                  scope=case-actor               | 200 AiReview/404
 *   POST /recordings/{id}/ai-review    markCallResult    | perm=case.follow scope=case-actor               | 200/403
 *
 * 优雅降级（绝不 5xx）：路径 id 非法/不存在→404；越 scope→403（存在性 404 优先于 403）；
 *   状态非法→409 STATE_409；同幂等键→409（IdempotencyInterceptor 兜底）；缺参/文件非法→422 VALIDATION_422。
 * 金额无涉；durationSec 为秒（整数），非 *_cents。
 */
@RestController
public class RecordingsM4Controller {

    private final JdbcTemplate jdbc;
    private final RecordingService rec;
    private final ObjectMapper json;

    public RecordingsM4Controller(JdbcTemplate jdbc, RecordingService rec, ObjectMapper json) {
        this.jdbc = jdbc;
        this.rec = rec;
        this.json = json;
    }

    // CFG-TC 缺省（settings TIMERS.tcSeconds 未配时兜底；与 M3 同量级 7d）。
    private static final long DEFAULT_TC_SECONDS = 7L * 24 * 3600;
    // 录音文件限制（地基期：仅做基础校验，避免 5xx；真正限额留运维层 multipart max-file-size）。
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;   // 200MB 软上限
    private static final Set<String> ALLOWED_EXT = Set.of(
            "mp3", "wav", "m4a", "aac", "amr", "ogg", "opus", "3gp", "wma", "flac");

    // ── [1] POST /cases/{id}/recordings  uploadRecording ─────────────────────────
    // BR-M4-01b：multipart 接文件，地基期落 PARSING；BR-M4-03：上传者==holder 才重置 T_collector。
    @PostMapping("/cases/{id}/recordings")
    @RequirePermission("case.call")
    @Transactional
    public ResponseEntity<CallRecordingDto> uploadRecording(
            @PathVariable("id") String id,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "recordedAt", required = false) String recordedAt,
            @RequestParam(value = "durationSec", required = false) String durationSec,
            @RequestParam(value = "phone", required = false) String phone) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id, "案件");

        // 存在性 404 优先于可见性 403。
        if (!rec.caseExists(caseId)) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + id);
        }
        if (!rec.caseVisible(s, caseId)) {
            throw new ApiException(BizError.PERM_403, "无权查看该案件");
        }

        // file 必填 / 格式 / 大小 → 422。
        validateFile(file);

        String src = normalizeSource(source);
        Instant recAt = parseInstantOrNull(recordedAt);
        Integer dur = parseDurationOrNull(durationSec);
        long collectorId = parseAccountId(s);

        // 地基期不跑 ASR：直接置 PARSING（BR-M4-01c 同链路），返 202。
        long recId = rec.insertRecording(caseId, collectorId, src, RecordingService.ST_PARSING, recAt, dur, phone);

        // 同步写一条 activity(type=CALL, ref→录音)。
        rec.writeActivity(collectorId, caseId, "CALL", "通话录音上传", "call_recording", recId, "CALL");

        // BR-M4-03：上传通话本身（含时长）即触发持有催收员 T_collector 重置——仅当上传者==holder。
        // PL/PC 代打不重置（BR-M3-08）。
        Long holder = rec.holderOf(caseId);
        if (holder != null && holder == collectorId) {
            rec.resetTCollector(caseId, tcSeconds());
        }

        CallRecordingDto dto = rec.getDto(recId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(dto);
    }

    // ── [2] GET /cases/{id}/recordings/latest  getLatestRecording ────────────────
    // 查最近一通的录音/解析状态（非通话列表翻阅 BR-M4-01b）。
    @GetMapping("/cases/{id}/recordings/latest")
    @RequirePermission("case.call")
    public LatestRecordingDto getLatestRecording(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long caseId = parseId(id, "案件");

        if (!rec.caseExists(caseId)) {
            throw new ApiException(BizError.NOT_FOUND_404, "案件不存在: " + id);
        }
        if (!rec.caseVisible(s, caseId)) {
            throw new ApiException(BizError.PERM_403, "无权查看该案件");
        }

        boolean redact = redactPhoneForCase(s, caseId);
        List<CallRecordingDto> found = jdbc.query(
                "SELECT * FROM call_recording WHERE case_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                rec.recordingMapper(redact), caseId);
        if (found.isEmpty()) {
            return new LatestRecordingDto(false, null, "未检测到本机录音，请手动上传");
        }
        return new LatestRecordingDto(true, found.get(0), null);
    }

    // ── [3] GET /recordings/{id}  getRecording ───────────────────────────────────
    // 前端轮询解析进度。存在性 404 优先于可见性 403。
    @GetMapping("/recordings/{id}")
    public CallRecordingDto getRecording(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long recId = parseId(id, "录音");
        RecSnapshot snap = rec.findRecording(recId);          // 不存在→404
        if (!rec.caseVisible(s, snap.caseId())) {
            throw new ApiException(BizError.PERM_403, "无权查看该录音");
        }
        boolean redact = redactPhoneForCase(s, snap.caseId());
        return rec.getDto(recId, redact);
    }

    // ── [4] POST /recordings/{id}/reprocess  reprocessRecording ──────────────────
    // reprocess vs parse：reprocess 仅从 FAILED 重跑同一文件（BR-M5-08 失败不扣分钟）；非 FAILED→409。
    @PostMapping("/recordings/{id}/reprocess")
    @RequirePermission("case.call")
    @Transactional
    public ResponseEntity<CallRecordingDto> reprocessRecording(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long recId = parseId(id, "录音");
        RecSnapshot snap = rec.lockRecording(recId);          // 行锁；不存在→404
        if (!rec.caseVisible(s, snap.caseId())) {
            throw new ApiException(BizError.PERM_403, "无权操作该录音");
        }
        if (!RecordingService.ST_FAILED.equals(snap.status())) {
            throw new ApiException(BizError.STATE_409, "仅 FAILED 录音可重试解析");
        }
        int n = rec.setStatus(recId, RecordingService.ST_FAILED, RecordingService.ST_PARSING);
        if (n == 0) {
            throw new ApiException(BizError.STATE_409, "录音状态已变更，重试失败");
        }
        rec.writeActivity(parseAccountId(s), snap.caseId(), "CALL", "通话录音重试解析",
                "call_recording", recId, "CALL");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(rec.getDto(recId));
    }

    // ── [5] POST /recordings/{id}/parse  parseRecording ──────────────────────────
    // 面向"余额不足暂停→充值→手动补"：可从 QUOTA_BLOCKED 或 READY 触发，置 PARSING。
    // BR-M5-02 余额扣减/BIZ_QUOTA_EXHAUSTED 409 留 M5 接入；当前桩实现不抛余额错误。
    @PostMapping("/recordings/{id}/parse")
    @RequirePermission("case.call")
    @Transactional
    public ResponseEntity<CallRecordingDto> parseRecording(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long recId = parseId(id, "录音");
        RecSnapshot snap = rec.lockRecording(recId);          // 行锁；不存在→404
        if (!rec.caseVisible(s, snap.caseId())) {
            throw new ApiException(BizError.PERM_403, "无权操作该录音");
        }
        String cur = snap.status();
        if (!RecordingService.ST_QUOTA_BLOCKED.equals(cur) && !RecordingService.ST_READY.equals(cur)) {
            throw new ApiException(BizError.STATE_409, "仅 QUOTA_BLOCKED/READY 录音可手动补解析");
        }
        // TODO(M5)：BR-M5-02 余额扣减；余额不足则 throw BIZ_QUOTA_EXHAUSTED(409)。地基期直接置 PARSING。
        int n = rec.setStatus(recId, cur, RecordingService.ST_PARSING);
        if (n == 0) {
            throw new ApiException(BizError.STATE_409, "录音状态已变更，补解析失败");
        }
        rec.writeActivity(parseAccountId(s), snap.caseId(), "CALL", "通话录音手动补解析",
                "call_recording", recId, "CALL");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(rec.getDto(recId));
    }

    // ── [6] GET /recordings  listRecordings ──────────────────────────────────────
    // 多过滤 + range 三分支裁剪（与 CasesM2Controller.appendRangeScope 同口径）。结案对非平台/物业脱敏 phone。
    @GetMapping("/recordings")
    public Page<CallRecordingDto> listRecordings(
            @RequestParam(required = false) String caseId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) String collectorId,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String room,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        CurrentSubject s = SubjectContext.get();
        Pageable pg = Pageable.of(page, size);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        addEqLong(where, args, "cr.case_id", caseId);
        addEqLong(where, args, "c.project_id", projectId);
        addEqLong(where, args, "c.batch_id", batchId);
        addEqLong(where, args, "cr.collector_id", collectorId);
        addLike(where, args, "cr.phone", phone);
        addLike(where, args, "c.room", room);
        addInstant(where, args, "cr.created_at >= ?", from);
        addInstant(where, args, "cr.created_at <= ?", to);
        appendRangeScope(s, where, args);

        String base = "FROM call_recording cr"
                + " JOIN \"case\" c ON c.id = cr.case_id"
                + " JOIN project p ON p.id = c.project_id"
                + " JOIN batch b ON b.id = c.batch_id"
                + where;

        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pg.size);
        pageArgs.add(pg.offset);
        // 选 cr.* + c.status（脱敏判定）；用带结案判定的 mapper 逐行脱敏。
        boolean nonPlatformProperty = !(s.isPlatform() || "PROPERTY".equals(s.orgType()));
        List<CallRecordingDto> items = jdbc.query(
                "SELECT cr.*, c.status AS case_status " + base + " ORDER BY cr.created_at DESC, cr.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    boolean redact = nonPlatformProperty && isClosed(rs.getString("case_status"));
                    return new CallRecordingDto(
                            String.valueOf(rs.getLong("id")),
                            String.valueOf(rs.getLong("case_id")),
                            rs.getObject("collector_id") == null ? null : String.valueOf(rs.getLong("collector_id")),
                            rs.getString("source"),
                            rs.getString("status"),
                            rs.getTimestamp("recorded_at") == null ? null
                                    : java.time.format.DateTimeFormatter.ISO_INSTANT.format(rs.getTimestamp("recorded_at").toInstant()),
                            recDuration(rs),
                            redact ? "***" : rs.getString("phone"),
                            rs.getString("transcript"),
                            rs.getString("failure_code"),
                            rs.getString("failure_message"));
                },
                pageArgs.toArray());

        return Page.of(items, pg, total == null ? 0 : total);
    }

    // ── [7] GET /recordings/{id}/ai-review  getAiReview ──────────────────────────
    @GetMapping("/recordings/{id}/ai-review")
    public AiReviewDto getAiReview(@PathVariable("id") String id) {
        CurrentSubject s = SubjectContext.get();
        long recId = parseId(id, "录音");
        RecSnapshot snap = rec.findRecording(recId);          // 录音不存在→404
        if (!rec.caseVisible(s, snap.caseId())) {
            throw new ApiException(BizError.PERM_403, "无权查看该复盘");
        }
        AiReviewDto review = jdbc.query(
                "SELECT call_id, summary, dialogue, risks, suggestions FROM ai_review WHERE call_id = ?",
                rs -> {
                    if (!rs.next()) return null;
                    return new AiReviewDto(
                            String.valueOf(rs.getLong("call_id")),
                            rs.getString("summary"),
                            parseJsonArray(rs.getString("dialogue")),
                            parseJsonArray(rs.getString("risks")),
                            parseJsonArray(rs.getString("suggestions")));
                },
                recId);
        if (review == null) {
            throw new ApiException(BizError.NOT_FOUND_404, "暂无 AI 复盘: " + id);
        }
        return review;
    }

    // ── [8] POST /recordings/{id}/ai-review  markCallResult ──────────────────────
    // body.mark 必填→422 校 CFG-MARK-CODES。UPSERT ai_review.result_mark。
    // BR-M4-03/12：仅当 mark.effectiveFollowUp=true 且操作者==案件 holder 时重置 T_collector；PL/PC 不重置。
    @PostMapping("/recordings/{id}/ai-review")
    @RequirePermission("case.follow")
    @Transactional
    public Map<String, Object> markCallResult(@PathVariable("id") String id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        CurrentSubject s = SubjectContext.get();
        long recId = parseId(id, "录音");
        RecSnapshot snap = rec.lockRecording(recId);          // 行锁；不存在→404
        if (!rec.caseVisible(s, snap.caseId())) {
            throw new ApiException(BizError.PERM_403, "无权标记该通话结果");
        }

        Object markV = body == null ? null : body.get("mark");
        if (markV == null || String.valueOf(markV).isBlank()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少 mark");
        }
        String mark = String.valueOf(markV).trim();
        boolean effective = rec.validateMarkAndIsEffective(mark);   // 非法→422

        rec.upsertResultMark(recId, mark);

        long actorId = parseAccountId(s);
        Long holder = rec.holderOf(snap.caseId());
        if (effective && holder != null && holder == actorId) {
            rec.resetTCollector(snap.caseId(), tcSeconds());
        }
        rec.writeActivity(actorId, snap.caseId(), "CALL", "通话结果标记: " + mark,
                "call_recording", recId, "CALL");
        return Map.of("ok", true);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** range scope（与 CasesM2Controller 同口径）：平台全量；服务商 b.provider_id；其余 p.org_id。 */
    private void appendRangeScope(CurrentSubject s, StringBuilder where, List<Object> args) {
        if (s.isPlatform()) return;
        Long org = parseOrgIdOrThrow(s);
        if ("PROVIDER".equals(s.orgType())) {
            where.append(" AND b.provider_id = ?");
            args.add(org);
        } else {
            where.append(" AND p.org_id = ?");
            args.add(org);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(BizError.VALIDATION_422, "缺少录音文件 file");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(BizError.VALIDATION_422, "录音文件超过大小上限");
        }
        String name = file.getOriginalFilename();
        String ext = null;
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                ext = name.substring(dot + 1).toLowerCase();
            }
        }
        // 有扩展名则须在白名单；无扩展名时按内容类型放行（App 上传可能不带名），仍拒明显非音频。
        if (ext != null) {
            if (!ALLOWED_EXT.contains(ext)) {
                throw new ApiException(BizError.VALIDATION_422, "不支持的录音文件格式");
            }
        } else {
            String ct = file.getContentType();
            if (ct != null && !ct.startsWith("audio/") && !ct.equals("application/octet-stream")) {
                throw new ApiException(BizError.VALIDATION_422, "不支持的录音文件格式");
            }
        }
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) return "MANUAL";
        String v = source.trim().toUpperCase();
        if (!v.equals("APP_AUTO") && !v.equals("MANUAL")) {
            throw new ApiException(BizError.VALIDATION_422, "source 非法（APP_AUTO|MANUAL）");
        }
        return v;
    }

    private static Instant parseInstantOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Instant.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(BizError.VALIDATION_422, "recordedAt 时间格式非法（须 ISO-8601）");
        }
    }

    private static Integer parseDurationOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            int d = Integer.parseInt(v.trim());
            if (d < 0) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            throw new ApiException(BizError.VALIDATION_422, "durationSec 非法（须非负整数）");
        }
    }

    /** 结案脱敏判定：非平台/非物业看结案态时脱敏 phone（BR-M8-09）。 */
    private boolean redactPhoneForCase(CurrentSubject s, long caseId) {
        if (s.isPlatform() || "PROPERTY".equals(s.orgType())) return false;
        return isClosed(rec.caseStatus(caseId));
    }

    private static boolean isClosed(String status) {
        if (status == null) return false;
        return switch (status) {
            case "SETTLED", "WITHDRAWN", "BAD_DEBT", "VOIDED" -> true;
            default -> false;
        };
    }

    private static void addEqLong(StringBuilder where, List<Object> args, String col, String v) {
        if (v == null || v.isBlank()) return;
        try {
            args.add(Long.valueOf(v.trim()));
            where.append(" AND ").append(col).append(" = ?");
        } catch (NumberFormatException e) {
            // 非法数字过滤值：按"无匹配"处理，不抛 5xx 也不报错（宽松过滤语义）。
            where.append(" AND 1=0");
        }
    }

    private static void addLike(StringBuilder where, List<Object> args, String col, String v) {
        if (v == null || v.isBlank()) return;
        where.append(" AND ").append(col).append(" LIKE ?");
        args.add("%" + v.trim() + "%");
    }

    private static void addInstant(StringBuilder where, List<Object> args, String clause, String v) {
        if (v == null || v.isBlank()) return;
        try {
            args.add(java.sql.Timestamp.from(Instant.parse(v.trim())));
            where.append(" AND ").append(clause);
        } catch (DateTimeParseException e) {
            // 非法时间过滤值忽略（宽松），避免 5xx。
        }
    }

    private List<Object> parseJsonArray(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            return json.readValue(jsonText, new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Integer recDuration(java.sql.ResultSet rs) throws java.sql.SQLException {
        int v = rs.getInt("duration_sec");
        return rs.wasNull() ? null : v;
    }

    /** 路径 id 非法形态统一 404，避免存在性泄漏 / 防 5xx。 */
    private static long parseId(String id, String label) {
        try {
            return Long.parseLong(id);
        } catch (RuntimeException e) {
            throw new ApiException(BizError.NOT_FOUND_404, label + "不存在: " + id);
        }
    }

    private static long parseAccountId(CurrentSubject s) {
        try {
            return Long.parseLong(s.accountId());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.PERM_403, "无效主体上下文");
        }
    }

    private static Long parseOrgIdOrThrow(CurrentSubject s) {
        try {
            return Long.valueOf(s.orgId());
        } catch (RuntimeException e) {
            throw new ApiException(BizError.PERM_403, "无组织上下文");
        }
    }

    private long tcSeconds() {
        try {
            Long v = jdbc.query(
                    "SELECT timers ->> 'tcSeconds' AS v FROM settings WHERE domain = 'TIMERS'"
                            + " ORDER BY version DESC LIMIT 1",
                    rs -> {
                        if (!rs.next()) return null;
                        String raw = rs.getString("v");
                        if (raw == null || raw.isBlank()) return null;
                        try { return Long.valueOf(raw.trim()); } catch (NumberFormatException e) { return null; }
                    });
            return v == null ? DEFAULT_TC_SECONDS : v;
        } catch (RuntimeException e) {
            return DEFAULT_TC_SECONDS;
        }
    }
}
