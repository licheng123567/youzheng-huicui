package com.youzheng.huicui.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云百炼（DashScope）录音文件识别 —— paraformer-8k-v2（8k 电话音质专用）。
 *
 * <p>协议是**异步三段式**（不是一次请求就拿转写）：
 * <ol>
 *   <li>提交：POST {base}/api/v1/services/audio/asr/transcription，头带 {@code X-DashScope-Async: enable}，
 *       body 里给 {@code file_urls}——**阿里侧会主动来拉这个 URL**，所以必须是公网可达地址
 *       （我们用带签名 token 的公开音频端点，TTL 30min，见 V937 注释）。响应回 {@code task_id}。</li>
 *   <li>轮询：GET {base}/api/v1/tasks/{task_id} → {@code task_status} ∈ PENDING/RUNNING/SUCCEEDED/FAILED。</li>
 *   <li>取结果：SUCCEEDED 时给的是一个 {@code transcription_url}（**又一次 HTTP**，指向一份 JSON），
 *       里面才是句段。很多人在这一步栽跟头：以为轮询响应里直接有文本。</li>
 * </ol>
 *
 * <p>未配置（enabled=false 或 apiKey 空）→ {@link #isEnabled()}=false，调用方维持既有占位行为
 * （录音停在 PARSING，不报错）——绝不能因为没配 key 就让上传录音失败。
 */
@Component
public class BailianAsrClient {

    private static final Logger log = LoggerFactory.getLogger(BailianAsrClient.class);
    private static final String DEFAULT_BASE = "https://dashscope.aliyuncs.com";
    private static final String DEFAULT_MODEL = "paraformer-8k-v2";

    private final IntegrationConfigService cfg;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public BailianAsrClient(IntegrationConfigService cfg) {
        this.cfg = cfg;
    }

    /** 转写结果：整段纯文本 + 句段（说话人/时间轴）。 */
    public record Segment(int seq, String speaker, Integer beginMs, Integer endMs, String text) {}

    public record Transcription(String fullText, List<Segment> segments) {}

    public boolean isEnabled() {
        return cfg.enabled(IntegrationConfigService.BAILIAN) && !apiKey().isBlank();
    }

    private String apiKey() {
        String v = cfg.secret(IntegrationConfigService.BAILIAN, "apiKey");
        return v == null ? "" : v;
    }

    private String baseUrl() {
        String u = cfg.setting(IntegrationConfigService.BAILIAN, "baseUrl");
        if (u == null || u.isBlank()) u = DEFAULT_BASE;
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private String model() {
        String m = cfg.setting(IntegrationConfigService.BAILIAN, "model");
        return (m == null || m.isBlank()) ? DEFAULT_MODEL : m;
    }

    /** 热词：从 ai_config.asr.hotwords 传进来（物业费/滞纳金/分期…显著降低这些词的错字率）。 */
    public String submitTask(String audioUrl, List<String> hotwords) {
        ObjectNode input = json.createObjectNode();
        ArrayNode urls = input.putArray("file_urls");
        urls.add(audioUrl);

        ObjectNode params = json.createObjectNode();
        params.put("channel_id", 0);
        params.put("disfluency_removal_enabled", false);
        params.put("diarization_enabled", true);          // 区分坐席/业主，复盘面板要按说话人分栏
        if (hotwords != null && !hotwords.isEmpty()) {
            ArrayNode hw = params.putArray("vocabulary");
            hotwords.forEach(hw::add);
        }

        ObjectNode body = json.createObjectNode();
        body.put("model", model());
        body.set("input", input);
        body.set("parameters", params);

        JsonNode out = post("/api/v1/services/audio/asr/transcription", body, true);
        String taskId = out.path("output").path("task_id").asText(null);
        if (taskId == null || taskId.isBlank()) {
            throw new ApiException(BizError.BIZ_ASR_FAILED, "百炼未返回 task_id");
        }
        return taskId;
    }

    /** 任务状态：PENDING / RUNNING / SUCCEEDED / FAILED（其余按 FAILED 处理）。 */
    public record TaskState(String status, String transcriptionUrl, String message) {}

    public TaskState queryTask(String taskId) {
        JsonNode out = get("/api/v1/tasks/" + taskId);
        JsonNode o = out.path("output");
        String status = o.path("task_status").asText("FAILED");
        // 结果在 results[0].transcription_url —— 不是直接给文本（这一步最容易看漏）
        String url = o.path("results").path(0).path("transcription_url").asText(null);
        String msg = o.path("message").asText(o.path("results").path(0).path("message").asText(null));
        return new TaskState(status, url, msg);
    }

    /** 拉取转写 JSON 并解析成整段文本 + 句段。 */
    public Transcription fetchTranscription(String transcriptionUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(transcriptionUrl))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ApiException(BizError.BIZ_ASR_FAILED, "拉取转写结果失败: HTTP " + resp.statusCode());
            }
            JsonNode root = json.readTree(resp.body());
            List<Segment> segs = new ArrayList<>();
            StringBuilder full = new StringBuilder();
            int seq = 0;
            for (JsonNode t : root.path("transcripts")) {
                for (JsonNode s : t.path("sentences")) {
                    String text = s.path("text").asText("");
                    if (text.isBlank()) continue;
                    String speaker = s.hasNonNull("speaker_id") ? s.get("speaker_id").asText()
                            : (t.hasNonNull("channel_id") ? "ch" + t.get("channel_id").asText() : null);
                    segs.add(new Segment(seq++, speaker,
                            s.hasNonNull("begin_time") ? s.get("begin_time").asInt() : null,
                            s.hasNonNull("end_time") ? s.get("end_time").asInt() : null,
                            text));
                    full.append(text);
                }
            }
            return new Transcription(full.toString(), segs);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(BizError.BIZ_ASR_FAILED, "解析转写结果失败: " + e.getMessage());
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private JsonNode post(String path, ObjectNode body, boolean async) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey())
                    .header("Content-Type", "application/json");
            if (async) b.header("X-DashScope-Async", "enable");
            HttpResponse<String> resp = http.send(
                    b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            return parse(resp, path);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("百炼不可达 path={}: {}", path, e.toString());
            throw new ApiException(BizError.BIZ_ASR_FAILED, "语音转写服务不可达");
        }
    }

    private JsonNode get(String path) {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + path))
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Bearer " + apiKey())
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return parse(resp, path);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("百炼不可达 path={}: {}", path, e.toString());
            throw new ApiException(BizError.BIZ_ASR_FAILED, "语音转写服务不可达");
        }
    }

    /** 上游细节只进日志：对外一律「语音转写失败」——错误信息可能含 key 片段或内部地址。 */
    private JsonNode parse(HttpResponse<String> resp, String path) throws Exception {
        if (resp.statusCode() / 100 != 2) {
            log.warn("百炼返回非 2xx path={} status={} body={}", path, resp.statusCode(), clip(resp.body()));
            throw new ApiException(BizError.BIZ_ASR_FAILED, "语音转写失败（HTTP " + resp.statusCode() + "）");
        }
        return json.readTree(resp.body());
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
