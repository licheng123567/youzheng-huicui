package com.youzheng.huicui.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 真 AI 管线（v1.24.0）：录音 →(百炼 ASR)→ 转写 →(DeepSeek)→ 复盘小结 + 违规检测 → 落 risk_record。
 *
 * <p><b>接上的是既有闭环，不是新造一套</b>：STT 计费与 QUOTA_BLOCKED、前端「补解析」、录音回听、
 * AI 复盘面板、质检 risk_record→复核→处置任务，全都早就在了，一直缺的只是引擎。
 *
 * <p><b>降级是第一原则</b>：没配 key / 通道未启用 → 维持既有占位行为（录音停在 PARSING，不报错、不失败）。
 * 绝不能因为平台没接 AI 就让催收员上传录音失败——那是把「增强功能」变成「必需依赖」。
 *
 * <p><b>为什么 LLM 失败不回滚转写</b>：转写是花了钱的（STT 按分钟扣费），复盘只是增值。
 * 模型抽风时录音照常 READY，复盘留空、下次可重跑——把两件事绑死会让用户为一次模型抖动重付转写费。
 */
@Service
public class AiPipelineService {

    private static final Logger log = LoggerFactory.getLogger(AiPipelineService.class);
    private static final SecureRandom RNG = new SecureRandom();

    /** 音频拉取凭据 TTL：给阿里侧留足排队时间，又不让链接长期有效。 */
    private static final int AUDIO_TOKEN_TTL_MINUTES = 30;
    /** 任务超时：卡在 PARSING 超过这个时长即判 FAILED，不能让录音永远转圈。 */
    private static final int TASK_TIMEOUT_MINUTES = 30;

    private static final String DEFAULT_REVIEW_PROMPT = """
            你是物业费催收通话的质检员。阅读通话转写文本，输出严格的 JSON：
            {"summary":"120字以内的复盘小结","risks":[{"type":"辱骂威胁|违规承诺|用语不规范|承诺口径不当","level":"HIGH|MID|LOW","quote":"原文片段","reason":"为何违规"}],"suggestions":["改进建议1","改进建议2"]}
            没有风险时 risks 返回空数组。只输出 JSON，不要任何解释。""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BailianAsrClient asr;
    private final DeepSeekClient llm;
    private final String publicBaseUrl;

    public AiPipelineService(JdbcTemplate jdbc, ObjectMapper json, BailianAsrClient asr, DeepSeekClient llm,
                             @Value("${huicui.sms.public-base-url:}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.json = json;
        this.asr = asr;
        this.llm = llm;
        // 复用缴费链接那套公网域名（prod 已强制它必须是真实可达域名，见 ProdEnvironmentGuard）
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    // ── 提交转写 ──────────────────────────────────────────────────────────

    /**
     * 把一条 PARSING 的录音提交给百炼。未启用/无音频 → 什么都不做（维持占位，不报错）。
     * 由 parse/reprocess/upload 调用；已提交过（asr_task_id 非空）则跳过，天然幂等。
     */
    @Transactional
    public void submitAsr(long recordingId) {
        if (!asr.isEnabled()) return;                       // 降级：没接 AI 就维持占位
        Map<String, Object> rec;
        try {
            rec = jdbc.queryForMap("SELECT status, asr_task_id, audio_bytes IS NOT NULL AS has_audio"
                    + " FROM call_recording WHERE id = ?", recordingId);
        } catch (Exception e) {
            return;
        }
        if (rec.get("asr_task_id") != null) return;         // 已提交过
        if (!Boolean.TRUE.equals(rec.get("has_audio"))) {
            // 没有音频文件（App 还没上传/演示数据）→ 转写无从谈起，保持现状不报错
            return;
        }
        if (publicBaseUrl.isBlank()) {
            log.warn("未配置 public-base-url，百炼无法回拉音频，跳过转写 recordingId={}", recordingId);
            return;
        }

        String token = randomToken();
        jdbc.update("INSERT INTO recording_pub_token(token, recording_id, expires_at)"
                        + " VALUES (?, ?, now() + make_interval(mins => ?))",
                token, recordingId, AUDIO_TOKEN_TTL_MINUTES);
        String audioUrl = publicBaseUrl + "/v1/pub/recordings/" + token;

        try {
            String taskId = asr.submitTask(audioUrl, hotwords());
            jdbc.update("UPDATE call_recording SET asr_task_id = ?, asr_submitted_at = now(),"
                    + " status = 'PARSING', updated_at = now() WHERE id = ?", taskId, recordingId);
            log.info("已提交转写 recordingId={} taskId={}", recordingId, taskId);
        } catch (Exception e) {
            // 提交失败不阻断上传/补解析（调用方已扣费，重试由「补解析」按钮承担）
            log.warn("提交转写失败 recordingId={}: {}", recordingId, e.getMessage());
            jdbc.update("UPDATE call_recording SET status = 'FAILED', failure_code = 'ASR_SUBMIT',"
                    + " failure_message = ?, updated_at = now() WHERE id = ?",
                    clip(e.getMessage()), recordingId);
        }
    }

    // ── 轮询推进 ──────────────────────────────────────────────────────────

    /** 一轮：把所有在途任务推进一步。由 AiPollScheduler 定时调用。 */
    public void pollOnce() {
        if (!asr.isEnabled()) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, asr_task_id,"
                        + " (asr_submitted_at < now() - make_interval(mins => ?)) AS timed_out"
                        + " FROM call_recording"
                        + " WHERE status = 'PARSING' AND asr_task_id IS NOT NULL"
                        + " ORDER BY asr_submitted_at LIMIT 20",
                TASK_TIMEOUT_MINUTES);
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            String taskId = (String) r.get("asr_task_id");
            try {
                if (Boolean.TRUE.equals(r.get("timed_out"))) {
                    fail(id, "ASR_TIMEOUT", "转写任务超时（" + TASK_TIMEOUT_MINUTES + " 分钟未完成）");
                    continue;
                }
                BailianAsrClient.TaskState st = asr.queryTask(taskId);
                switch (st.status()) {
                    case "SUCCEEDED" -> complete(id, st.transcriptionUrl());
                    case "FAILED" -> fail(id, "ASR_FAILED", st.message() == null ? "转写失败" : clip(st.message()));
                    default -> { /* PENDING/RUNNING：下一轮再看 */ }
                }
            } catch (Exception e) {
                log.warn("轮询转写异常 recordingId={}: {}", id, e.toString());   // 不改状态，下轮重试
            }
        }
        // 顺手清过期凭据（不为此单开一个定时任务）
        jdbc.update("DELETE FROM recording_pub_token WHERE expires_at < now()");
    }

    /** 转写成功：落 transcript + 句段 → 立刻触发 LLM 复盘（失败不影响 READY）。 */
    @Transactional
    public void complete(long recordingId, String transcriptionUrl) {
        BailianAsrClient.Transcription t = asr.fetchTranscription(transcriptionUrl);
        jdbc.update("DELETE FROM transcript_segment WHERE recording_id = ?", recordingId);   // 重跑幂等
        for (BailianAsrClient.Segment s : t.segments()) {
            jdbc.update("INSERT INTO transcript_segment(recording_id, seq, speaker, begin_ms, end_ms, text)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    recordingId, s.seq(), s.speaker(), s.beginMs(), s.endMs(), s.text());
        }
        jdbc.update("UPDATE call_recording SET status = 'READY', transcript = ?, failure_code = NULL,"
                + " failure_message = NULL, updated_at = now() WHERE id = ?", t.fullText(), recordingId);
        jdbc.update("DELETE FROM recording_pub_token WHERE recording_id = ?", recordingId);  // 音频链接即刻失效
        log.info("转写完成 recordingId={} 句段={} 字数={}", recordingId, t.segments().size(), t.fullText().length());

        reviewWithLlm(recordingId, t.fullText());
    }

    private void fail(long recordingId, String code, String msg) {
        jdbc.update("UPDATE call_recording SET status = 'FAILED', failure_code = ?, failure_message = ?,"
                + " updated_at = now() WHERE id = ?", code, msg, recordingId);
        jdbc.update("DELETE FROM recording_pub_token WHERE recording_id = ?", recordingId);
        log.warn("转写失败 recordingId={} code={} msg={}", recordingId, code, msg);
    }

    // ── LLM 复盘 + 违规检测 ───────────────────────────────────────────────

    /**
     * 用 DeepSeek 出复盘小结 + 违规风险 + 改进建议；风险条目落 risk_record，直接汇入既有质检闭环
     * （平台复核 → 处置任务 → 归属方整改回执）。
     *
     * <p>LLM 挂了就跳过：录音照常 READY，复盘留空可重跑——不会让用户为模型抖动重付一次转写费。
     */
    public void reviewWithLlm(long recordingId, String transcript) {
        if (!llm.isEnabled() || transcript == null || transcript.isBlank()) return;
        Map<String, Object> rec;
        try {
            rec = jdbc.queryForMap("SELECT r.case_id, r.collector_id, r.ai_reviewed_at,"
                    + " c.provider_id, p.org_id AS property_id"
                    + " FROM call_recording r JOIN \"case\" c ON c.id = r.case_id"
                    + " JOIN project p ON p.id = c.project_id WHERE r.id = ?", recordingId);
        } catch (Exception e) {
            return;
        }
        if (rec.get("ai_reviewed_at") != null) return;      // 幂等：已复盘过不重复烧钱

        JsonNode out = llm.chatJson(reviewPrompt(), transcript, temperature(), maxTokens());
        if (out == null) {
            log.warn("LLM 复盘未产出可用结果 recordingId={}（录音保持 READY，可重跑）", recordingId);
            return;
        }

        String summary = out.path("summary").asText("");
        ArrayNode risks = json.createArrayNode();
        ArrayNode suggestions = json.createArrayNode();
        out.path("suggestions").forEach(suggestions::add);

        // 句段 → dialogue（复盘面板按说话人+时间轴渲染）
        ArrayNode dialogue = json.createArrayNode();
        jdbc.query("SELECT speaker, begin_ms, text FROM transcript_segment WHERE recording_id = ? ORDER BY seq",
                rs -> {
                    ObjectNode n = dialogue.addObject();
                    n.put("speaker", rs.getString("speaker"));
                    n.put("beginMs", rs.getInt("begin_ms"));
                    n.put("text", rs.getString("text"));
                }, recordingId);

        long caseId = num(rec.get("case_id"));
        long collectorId = num(rec.get("collector_id"));
        Long providerId = rec.get("provider_id") == null ? null : num(rec.get("provider_id"));
        long propertyId = num(rec.get("property_id"));

        for (JsonNode r : out.path("risks")) {
            String type = r.path("type").asText("用语不规范");
            String level = r.path("level").asText("LOW");
            if (!List.of("HIGH", "MID", "LOW").contains(level)) level = "LOW";
            ObjectNode n = risks.addObject();
            n.put("type", type);
            n.put("level", level);
            n.put("quote", r.path("quote").asText(""));
            n.put("reason", r.path("reason").asText(""));

            // provider_id 是 NOT NULL：案件还没派出去（无承接服务商）时不落风险——
            // 质检的处置对象是「谁的员工」，没有归属方就无人可处置。
            if (providerId == null) continue;
            jdbc.update("INSERT INTO risk_record(case_id, call_id, collector_id, provider_id, property_id,"
                            + " type, level, segment_ts)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    caseId, recordingId, collectorId, providerId, propertyId,
                    type, level, clip(r.path("quote").asText("")));
        }

        jdbc.update("INSERT INTO ai_review(call_id, summary, dialogue, risks, suggestions, model, generated_at)"
                        + " VALUES (?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, now())",
                recordingId, summary, str(dialogue), str(risks), str(suggestions), llm.model());
        jdbc.update("UPDATE call_recording SET ai_reviewed_at = now(), updated_at = now() WHERE id = ?", recordingId);
        log.info("AI 复盘完成 recordingId={} 风险={} 建议={}", recordingId, risks.size(), suggestions.size());
    }

    // ── ai_config 读取（复用既有 settings domain='AI'）────────────────────

    private String reviewPrompt() {
        String p = aiConfigText("prompts", "postReview");
        String rules = aiConfigText("prompts", "riskRules");
        if (p == null || p.isBlank() || p.startsWith("……")) return DEFAULT_REVIEW_PROMPT;
        // 平台自定义了提示词：仍要求 JSON 结构，否则解析不出来（结构约束不能交给运营去记）
        return p + "\n" + (rules == null || rules.startsWith("……") ? "" : ("风险判定规则：" + rules + "\n"))
                + "严格按此 JSON 输出：{\"summary\":\"\",\"risks\":[{\"type\":\"\",\"level\":\"HIGH|MID|LOW\",\"quote\":\"\",\"reason\":\"\"}],\"suggestions\":[]}";
    }

    private double temperature() {
        String v = aiConfigText("llm", "temperature");
        try {
            return v == null ? 0.3 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0.3;
        }
    }

    private int maxTokens() {
        String v = aiConfigText("llm", "maxTokens");
        try {
            return v == null ? 2048 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 2048;
        }
    }

    private List<String> hotwords() {
        String raw = jdbc.query("SELECT value->'asr'->>'hotwords' FROM settings WHERE domain = 'AI'"
                + " ORDER BY version DESC LIMIT 1", rs -> rs.next() ? rs.getString(1) : null);
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<String> out = new ArrayList<>();
            json.readTree(raw).forEach(n -> out.add(n.asText()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String aiConfigText(String group, String key) {
        return jdbc.query("SELECT value->?->>? FROM settings WHERE domain = 'AI' ORDER BY version DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, group, key);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private String str(Object node) {
        try {
            return json.writeValueAsString(node);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
