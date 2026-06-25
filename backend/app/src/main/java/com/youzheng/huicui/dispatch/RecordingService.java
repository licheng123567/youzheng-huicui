package com.youzheng.huicui.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import com.youzheng.huicui.security.CurrentSubject;
import com.youzheng.huicui.web.dto.CallRecordingDto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * M4 recordings 组共享助手（行锁/状态机 + case-actor 可见性 + CallRecording DTO 映射）。
 * 仿 {@link CaseStateService} 范式：加载（行锁）→ 校验前置态 → 原子 CAS UPDATE → 返回 DTO。
 *
 * 录音模型 BR-M4-01b：服务端绝不拉本机录音目录/主动外呼/感知拨打——那是 App 客户端本地行为。
 * 服务端只提供 upload + 状态轮询。状态机 CallRecStatus（call_recording.status，V1 chk 约束）：
 *   NO_FILE → UPLOADING → PARSING → READY | FAILED | QUOTA_BLOCKED。
 * 地基期不真正跑 ASR：upload/reprocess/parse 一律落 PARSING（演示数据可种子为 READY）。
 *
 * 表/列对齐 V1 DDL：call_recording(id, case_id, collector_id, source, status, recorded_at,
 *   duration_sec, phone, transcript, failure_code, failure_message, created_at, updated_at)；
 *   "case"(id, project_id, batch_id, status, holder_id, t_collector_deadline)；
 *   project(org_id)、batch(provider_id)；activity(case_id, type, actor_id, content, ref_type, ref_id, method)；
 *   ai_review(id, call_id, summary, dialogue, risks, suggestions, result_mark)（uq_ai_review_call）。
 */
@Service
public class RecordingService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RecordingService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    // ── 状态常量（对齐 chk_call_recording_status / CallRecStatusEnum）──
    public static final String ST_NO_FILE       = "NO_FILE";
    public static final String ST_UPLOADING     = "UPLOADING";
    public static final String ST_PARSING       = "PARSING";
    public static final String ST_READY         = "READY";
    public static final String ST_FAILED        = "FAILED";
    public static final String ST_QUOTA_BLOCKED = "QUOTA_BLOCKED";

    // ── 录音行锁快照（含派生 case_id，供 case-actor 复核与 holder 判定）──
    public record RecSnapshot(long id, long caseId, String status, Long collectorId) {}

    // ── case-actor 可见性 ──────────────────────────────────────────────────────
    /**
     * case-actor scope（地基期口径）：平台全量；服务商按 batch.provider_id=本组织；
     * 物业及其它非平台按 project.org_id=本组织。CO 持有/关联 PL/PC/SA 代细粒度待精确化。
     * TODO(case-actor 精确化)：CO 仅本案持有(holder_id=accountId) + 关联 PL/PC + SA 代；
     *   现阶段以组织级裁剪（own-org on project/batch）为底线，与 CasesM2Controller.appendRangeScope 同口径。
     * 返回该 case 对当前主体是否可见（不存在亦返回 false，由调用方先判存在性区分 404/403）。
     */
    public boolean caseVisible(CurrentSubject s, long caseId) {
        if (s.isPlatform()) {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM \"case\" WHERE id = ?", Long.class, caseId);
            return n != null && n > 0;
        }
        Long org = parseOrgId(s);
        if (org == null) return false;
        String sql;
        if ("PROVIDER".equals(s.orgType())) {
            sql = "SELECT count(*) FROM \"case\" c JOIN batch b ON b.id = c.batch_id"
                    + " WHERE c.id = ? AND b.provider_id = ?";
        } else {
            sql = "SELECT count(*) FROM \"case\" c JOIN project p ON p.id = c.project_id"
                    + " WHERE c.id = ? AND p.org_id = ?";
        }
        Long n = jdbc.queryForObject(sql, Long.class, caseId, org);
        return n != null && n > 0;
    }

    /** case 是否存在（区分 404 优先于 403）。 */
    public boolean caseExists(long caseId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM \"case\" WHERE id = ?", Long.class, caseId);
        return n != null && n > 0;
    }

    // ── 录音行锁加载（须在 @Transactional 内）。不存在→404 ──
    public RecSnapshot lockRecording(long recordingId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, case_id, status, collector_id FROM call_recording WHERE id = ? FOR UPDATE",
                    (rs, i) -> new RecSnapshot(
                            rs.getLong("id"),
                            rs.getLong("case_id"),
                            rs.getString("status"),
                            (Long) rs.getObject("collector_id")),
                    recordingId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "录音不存在: " + recordingId);
        }
    }

    /** 不带锁的存在性读（GET /recordings/{id}：先判存在 404，再判可见 403）。 */
    public RecSnapshot findRecording(long recordingId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, case_id, status, collector_id FROM call_recording WHERE id = ?",
                    (rs, i) -> new RecSnapshot(
                            rs.getLong("id"),
                            rs.getLong("case_id"),
                            rs.getString("status"),
                            (Long) rs.getObject("collector_id")),
                    recordingId);
        } catch (EmptyResultDataAccessException e) {
            throw new ApiException(BizError.NOT_FOUND_404, "录音不存在: " + recordingId);
        }
    }

    /** CAS 置状态：WHERE 带前置 status 防并发覆盖。返回受影响行数（0=状态已变更）。 */
    public int setStatus(long recordingId, String fromStatus, String toStatus) {
        return jdbc.update(
                "UPDATE call_recording SET status = ?, updated_at = now() WHERE id = ? AND status = ?",
                toStatus, recordingId, fromStatus);
    }

    /**
     * 插入一条录音（upload）。collector_id=当前主体 accountId；status 由调用方传入（地基期 PARSING）。
     * 返回新行 id。recordedAt/durationSec/phone 可空。
     */
    public long insertRecording(long caseId, long collectorId, String source, String status,
                                Instant recordedAt, Integer durationSec, String phone) {
        return jdbc.queryForObject(
                "INSERT INTO call_recording(case_id, collector_id, source, status, recorded_at, duration_sec, phone)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class,
                caseId, collectorId, source, status,
                recordedAt == null ? null : Timestamp.from(recordedAt),
                durationSec, phone);
    }

    /** 写一条 activity（type=CALL/NOTE 等）。actor=当前主体；ref_type/ref_id 关联录音。 */
    public void writeActivity(Long actorId, long caseId, String type, String content,
                              String refType, Long refId, String method) {
        jdbc.update(
                "INSERT INTO activity(case_id, type, actor_id, content, ref_type, ref_id, method)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                caseId, type, actorId, content, refType, refId, method);
    }

    /** 取单条录音 DTO（按 id）。脱敏由调用方在出参层处理。 */
    public CallRecordingDto getDto(long recordingId) {
        return jdbc.queryForObject(
                "SELECT * FROM call_recording WHERE id = ?", recordingMapper(false), recordingId);
    }

    /** 取单条录音 DTO，按 redactPhone 决定是否脱敏 phone。 */
    public CallRecordingDto getDto(long recordingId, boolean redactPhone) {
        return jdbc.queryForObject(
                "SELECT * FROM call_recording WHERE id = ?", recordingMapper(redactPhone), recordingId);
    }

    /** CallRecording RowMapper：列名→契约字段；redactPhone=true 时脱敏 phone（BR-M8-09）。 */
    public RowMapper<CallRecordingDto> recordingMapper(boolean redactPhone) {
        return (rs, i) -> new CallRecordingDto(
                String.valueOf(rs.getLong("id")),
                String.valueOf(rs.getLong("case_id")),
                idOrNull(rs, "collector_id"),
                rs.getString("source"),
                rs.getString("status"),
                ts(rs.getTimestamp("recorded_at")),
                intOrNull(rs, "duration_sec"),
                redactPhone ? REDACTED_PHONE : rs.getString("phone"),
                rs.getString("transcript"),
                rs.getString("failure_code"),
                rs.getString("failure_message"));
    }

    private static final String REDACTED_PHONE = "***";

    // ── BR-M4-12：CFG-MARK-CODES 校验 + effectiveFollowUp 判定 ──────────────────

    /** 一条 mark code 配置（settings MARK_CODES.markCodes 元素）。 */
    public record MarkCode(String code, boolean enabled, boolean connected, boolean effectiveFollowUp) {}

    /**
     * 读 settings MARK_CODES.mark_codes（最新版本）的 markCodes 数组。读不到/解析失败返回空集。
     * 结构对齐契约 Settings.markCodes：[{code,label,enabled,connected,effectiveFollowUp}]。
     */
    public List<MarkCode> markCodes() {
        try {
            String rawJson = jdbc.query(
                    "SELECT mark_codes ->> 'markCodes' AS mc FROM settings"
                            + " WHERE domain = 'MARK_CODES' ORDER BY version DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString("mc") : null);
            if (rawJson == null || rawJson.isBlank()) return List.of();
            List<java.util.Map<String, Object>> rows =
                    json.readValue(rawJson, new TypeReference<List<java.util.Map<String, Object>>>() {});
            List<MarkCode> out = new java.util.ArrayList<>();
            for (java.util.Map<String, Object> r : rows) {
                Object code = r.get("code");
                if (code == null) continue;
                out.add(new MarkCode(
                        String.valueOf(code),
                        boolOf(r.get("enabled"), true),
                        boolOf(r.get("connected"), false),
                        boolOf(r.get("effectiveFollowUp"), false)));
            }
            return out;
        } catch (Exception e) {
            return List.of();   // 配置读异常不致 5xx
        }
    }

    /**
     * 校验 mark 合法（CFG-MARK-CODES）。返回该 mark 的 effectiveFollowUp。
     * 非法 mark → 422 VALIDATION_422。配置缺失时回退到 chk_ai_review_mark 约束内置码集（与 DDL 一致），
     * effectiveFollowUp 按 ERD 注（前四接通有效码 PROMISED/REFUSED/NEED_TICKET/FOLLOW_UP=true，NO_ANSWER=false）。
     */
    public boolean validateMarkAndIsEffective(String mark) {
        List<MarkCode> codes = markCodes();
        if (!codes.isEmpty()) {
            for (MarkCode mc : codes) {
                if (mc.code().equals(mark)) {
                    if (!mc.enabled()) {
                        throw new ApiException(BizError.VALIDATION_422, "通话结果标记已停用");
                    }
                    return mc.effectiveFollowUp();
                }
            }
            throw new ApiException(BizError.VALIDATION_422, "非法通话结果标记");
        }
        // 配置缺失回退：仅允许 DDL chk 约束内置码（否则 UPSERT 会撞 chk 约束→5xx，这里前置拦成 422）。
        Set<String> fallback = new HashSet<>(List.of(
                "PROMISED", "REFUSED", "NEED_TICKET", "FOLLOW_UP", "NO_ANSWER"));
        if (!fallback.contains(mark)) {
            throw new ApiException(BizError.VALIDATION_422, "非法通话结果标记");
        }
        return !"NO_ANSWER".equals(mark);   // 前四=接通有效跟进；NO_ANSWER=未接通无效
    }

    /** UPSERT ai_review.result_mark（uq_ai_review_call 唯一）。无 ai_review 行则插入占位摘要。 */
    public void upsertResultMark(long callId, String mark) {
        jdbc.update(
                "INSERT INTO ai_review(call_id, summary, result_mark) VALUES (?, ?, ?)"
                        + " ON CONFLICT (call_id) DO UPDATE SET result_mark = EXCLUDED.result_mark, updated_at = now()",
                callId, "", mark);
    }

    /** 重置案件 T_collector：t_collector_deadline = now() + seconds（BR-M4-03/12）。 */
    public void resetTCollector(long caseId, long seconds) {
        jdbc.update(
                "UPDATE \"case\" SET t_collector_deadline = ?, updated_at = now() WHERE id = ?",
                Timestamp.from(Instant.now().plusSeconds(seconds)), caseId);
    }

    /** 案件当前持有人 holder_id（无→null）。 */
    public Long holderOf(long caseId) {
        return jdbc.query("SELECT holder_id FROM \"case\" WHERE id = ?",
                rs -> rs.next() ? (Long) rs.getObject("holder_id") : null, caseId);
    }

    /** 案件 status（用于结案脱敏判定）。 */
    public String caseStatus(long caseId) {
        return jdbc.query("SELECT status FROM \"case\" WHERE id = ?",
                rs -> rs.next() ? rs.getString("status") : null, caseId);
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private static Long parseOrgId(CurrentSubject s) {
        try {
            return s.orgId() == null ? null : Long.valueOf(s.orgId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String ts(Timestamp t) {
        return t == null ? null : ISO.format(t.toInstant());
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static String idOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : String.valueOf(v);
    }

    private static boolean boolOf(Object v, boolean dflt) {
        if (v == null) return dflt;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
