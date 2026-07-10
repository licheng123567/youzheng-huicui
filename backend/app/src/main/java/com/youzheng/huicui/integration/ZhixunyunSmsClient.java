package com.youzheng.huicui.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 智讯云（028lk/凌凯系）短信客户端。文档 智讯云 V2.5.1，REST/JSON、明文鉴权（SecretName+SecretKey）。
 *
 * 分流（BR-M9 用户决策）：即时类（验证码/缴费链接）走普通短信 Send；营销/通知类走视频短信 videosms/Send。
 *   普通短信 POST {smsBaseUrl}/Sms/Api/Send      —— 可即时；Content 或 TemplateId+TemplateVars；SignName=【签名】。
 *   视频短信 POST {videoBaseUrl}/videosms/api/Send —— Timing 必填且须 now+>10min；TheSameVar{Phone,Params[]}。
 * 响应统一 {code(0成功),msg,data}；code!=0 抛 BIZ_SMS_FAILED。
 *   **对外一律 "短信发送失败，请稍后重试"**，网关 msg/code/HTTP 状态只进日志——
 *   /auth/sms-code 是公开端点，回显上游内部错误等于把网关状态暴露给任何人。
 * 未配置（enabled=false 或 SecretName/SecretKey 空）→ isEnabled()=false，调用方走占位、不触网关。
 * dry-run（huicui.sms.dry-run=true）→ isEnabled()=true 但**不触网关**，供预发演练整条链路。
 */
@Component
public class ZhixunyunSmsClient {

    private static final Logger log = LoggerFactory.getLogger(ZhixunyunSmsClient.class);

    private final boolean enabled;
    private final boolean dryRun;
    private final String secretName;
    private final String secretKey;
    private final String smsBaseUrl;
    private final String videoBaseUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ZhixunyunSmsClient(
            @Value("${huicui.sms.enabled:false}") boolean enabled,
            @Value("${huicui.sms.dry-run:false}") boolean dryRun,
            @Value("${huicui.sms.secret-name:}") String secretName,
            @Value("${huicui.sms.secret-key:}") String secretKey,
            @Value("${huicui.sms.sms-base-url:}") String smsBaseUrl,
            @Value("${huicui.sms.video-base-url:http://api.028lk.com}") String videoBaseUrl) {
        this.enabled = enabled;
        this.dryRun = dryRun;
        this.secretName = secretName;
        this.secretKey = secretKey;
        this.smsBaseUrl = trimSlash(smsBaseUrl);
        this.videoBaseUrl = trimSlash(videoBaseUrl);
    }

    /** 已启用且凭据非空才真触网关；普通短信另需配 sms-base-url。 */
    public boolean isEnabled() {
        return enabled && !secretName.isBlank() && !secretKey.isBlank();
    }

    /** dry-run：走完整条业务链路（冷却/流水/状态机），但**不触网关、不产生费用**。 */
    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * 普通短信（即时）。templateId 非空→用模板 + vars；否则用 content 完整内容。
     * signName 传【签名】或留空（自动用账号默认绑定签名）。返回批次 id。
     */
    public String sendSms(String mobileCsv, String content, String templateId, List<String> vars, String signName) {
        if (dryRun) return dryRunEcho("普通短信", mobileCsv, content, templateId, vars);
        if (smsBaseUrl.isBlank()) throw new ApiException(BizError.BIZ_SMS_FAILED, "未配置普通短信接口地址(sms-base-url)");
        ObjectNode b = SmsBodyBuilder.sms(secretName, secretKey, mobileCsv, content, templateId, vars, signName);
        JsonNode data = post(smsBaseUrl + "/Sms/Api/Send", b);
        return data == null ? null : data.asText(null);
    }

    /**
     * 视频短信（非即时，Timing 须 now+>10min）。同一套变量群发（TheSameVar）。
     * @param timing yyyyMMddHHmmss
     */
    public String sendVideoSms(String templateId, String timing, String phoneCsv, List<String> params) {
        if (dryRun) return dryRunEcho("视频短信", phoneCsv, null, templateId, params);
        ObjectNode b = SmsBodyBuilder.video(secretName, secretKey, templateId, timing, phoneCsv, params);
        JsonNode data = post(videoBaseUrl + "/videosms/api/Send", b);
        return data == null ? null : data.asText(null);
    }

    /**
     * dry-run 出口。**会把短信正文（含验证码）写进日志** —— 这是有意的：
     * 预发演练时人要能真的登录一次。正因如此，prod 开 dry-run 会被 ProdEnvironmentGuard 大声 WARN，
     * 且 dry-run 默认 false。生产绝不可长期开着。
     */
    private String dryRunEcho(String kind, String mobileCsv, String content, String templateId, List<String> vars) {
        String batchId = "DRYRUN-" + UUID.randomUUID();
        log.warn("[SMS DRY-RUN] 未触网关 kind={} phone={} template={} vars={} content={} batchId={}",
                kind, maskPhone(mobileCsv), templateId == null ? "(明文)" : templateId,
                vars == null ? "[]" : vars, content == null ? "(用模板)" : content, batchId);
        return batchId;
    }

    /** 138****1234 */
    static String maskPhone(String csv) {
        if (csv == null || csv.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : csv.split(",")) {
            if (sb.length() > 0) sb.append(',');
            String t = p.trim();
            sb.append(t.length() < 7 ? t : t.substring(0, 3) + "****" + t.substring(t.length() - 4));
        }
        return sb.toString();
    }

    /**
     * 对外统一文案。**绝不把上游网关的 msg/code/HTTP 状态回显给客户端**：
     * /auth/sms-code 是 `security: []` 的公开端点，任何人都能触发它并读到响应 message。
     * 上游细节只进日志（带 traceId，运维按 traceId 反查），排障不受影响。
     */
    private static final String PUBLIC_FAILURE_MSG = "短信发送失败，请稍后重试";

    // ── 内部：POST JSON + 统一响应解包（code=0 成功） ──
    private JsonNode post(String url, ObjectNode body) {
        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json;charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("短信网关不可达 traceId={} url={}: {}", traceId(), url, e.toString());
            throw new ApiException(BizError.BIZ_SMS_FAILED, PUBLIC_FAILURE_MSG);
        }
        if (res.statusCode() / 100 != 2) {
            log.error("短信网关返回非 2xx traceId={} url={} status={} body={}",
                    traceId(), url, res.statusCode(), truncate(res.body()));
            throw new ApiException(BizError.BIZ_SMS_FAILED, PUBLIC_FAILURE_MSG);
        }
        JsonNode root;
        try {
            root = json.readTree(res.body());
        } catch (Exception e) {
            log.error("短信网关响应非 JSON traceId={} url={} body={}", traceId(), url, truncate(res.body()));
            throw new ApiException(BizError.BIZ_SMS_FAILED, PUBLIC_FAILURE_MSG);
        }
        if (root.path("code").asInt(-1) != 0) {
            log.error("短信网关拒绝 traceId={} url={} code={} msg={}",
                    traceId(), url, root.path("code").asText("?"), root.path("msg").asText("未知错误"));
            throw new ApiException(BizError.BIZ_SMS_FAILED, PUBLIC_FAILURE_MSG);
        }
        return root.path("data");
    }

    private static String traceId() {
        String t = MDC.get("traceId");
        return t == null ? "-" : t;
    }

    /** 上游报文只截断入日志，避免超长/刷屏。 */
    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
