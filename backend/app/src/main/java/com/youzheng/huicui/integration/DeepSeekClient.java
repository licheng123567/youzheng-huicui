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

/**
 * DeepSeek（OpenAI 兼容的 chat/completions）。用途：通话后复盘小结 + 违规话术检测 + 话术建议。
 *
 * <p>{@link #chatJson} 强制 {@code response_format=json_object}——我们要的是能落库的结构化结果
 * （风险条目/建议列表），不是一段散文。即便如此仍**必须容错**：模型偶尔会包一层 ```json 代码块，
 * 或在 JSON 前后加寒暄。解析失败不抛 5xx，返回 null，调用方跳过本次复盘（录音本身照常 READY）。
 *
 * <p>未配置（enabled=false 或 apiKey 空）→ isEnabled()=false，调用方不生成复盘（维持既有占位行为）。
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    private static final String DEFAULT_BASE = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    private final IntegrationConfigService cfg;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public DeepSeekClient(IntegrationConfigService cfg) {
        this.cfg = cfg;
    }

    public boolean isEnabled() {
        return cfg.enabled(IntegrationConfigService.DEEPSEEK) && !apiKey().isBlank();
    }

    private String apiKey() {
        String v = cfg.secret(IntegrationConfigService.DEEPSEEK, "apiKey");
        return v == null ? "" : v;
    }

    private String baseUrl() {
        String u = cfg.setting(IntegrationConfigService.DEEPSEEK, "baseUrl");
        if (u == null || u.isBlank()) u = DEFAULT_BASE;
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    public String model() {
        String m = cfg.setting(IntegrationConfigService.DEEPSEEK, "model");
        return (m == null || m.isBlank()) ? DEFAULT_MODEL : m;
    }

    /**
     * 要一份 JSON 回来。system=角色/规则（来自 ai_config.prompts），user=待分析内容（转写文本）。
     * @return 解析后的 JSON；模型没吐出合法 JSON 或网关异常 → null（调用方跳过，不阻断主流程）
     */
    public JsonNode chatJson(String systemPrompt, String userContent, double temperature, int maxTokens) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model());
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        ObjectNode fmt = body.putObject("response_format");
        fmt.put("type", "json_object");

        ArrayNode msgs = body.putArray("messages");
        ObjectNode sys = msgs.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        ObjectNode usr = msgs.addObject();
        usr.put("role", "user");
        usr.put("content", userContent);

        String content;
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + "/chat/completions"))
                            .timeout(Duration.ofSeconds(60))              // 复盘是长输出，60s 不算保守
                            .header("Authorization", "Bearer " + apiKey())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("DeepSeek 返回非 2xx status={} body={}", resp.statusCode(), clip(resp.body()));
                return null;
            }
            content = json.readTree(resp.body()).path("choices").path(0)
                    .path("message").path("content").asText(null);
        } catch (Exception e) {
            log.warn("DeepSeek 不可达: {}", e.toString());
            return null;
        }
        if (content == null || content.isBlank()) return null;
        return parseLoose(content);
    }

    /** 即便要了 json_object，模型仍可能包 ```json 或前后加话——容错到「取第一个 { 到最后一个 }」。 */
    private JsonNode parseLoose(String content) {
        String t = content.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (nl > 0 && end > nl) t = t.substring(nl + 1, end).trim();
        }
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a >= 0 && b > a) t = t.substring(a, b + 1);
        try {
            return json.readTree(t);
        } catch (Exception e) {
            log.warn("DeepSeek 返回不是合法 JSON: {}", clip(content));
            return null;
        }
    }

    /** 纯文本输出（话术建议草稿等不需要结构化时用）。异常 → 抛 BIZ_LLM_FAILED（调用方是同步端点，要给用户反馈）。 */
    public String chatText(String systemPrompt, String userContent, double temperature, int maxTokens) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model());
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        ArrayNode msgs = body.putArray("messages");
        ObjectNode sys = msgs.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        ObjectNode usr = msgs.addObject();
        usr.put("role", "user");
        usr.put("content", userContent);
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + "/chat/completions"))
                            .timeout(Duration.ofSeconds(60))
                            .header("Authorization", "Bearer " + apiKey())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("DeepSeek 返回非 2xx status={} body={}", resp.statusCode(), clip(resp.body()));
                throw new ApiException(BizError.BIZ_LLM_FAILED, "AI 生成失败（HTTP " + resp.statusCode() + "）");
            }
            String c = json.readTree(resp.body()).path("choices").path(0)
                    .path("message").path("content").asText(null);
            if (c == null || c.isBlank()) {
                throw new ApiException(BizError.BIZ_LLM_FAILED, "AI 未返回内容");
            }
            return c.trim();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("DeepSeek 不可达: {}", e.toString());
            throw new ApiException(BizError.BIZ_LLM_FAILED, "AI 服务不可达");
        }
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
