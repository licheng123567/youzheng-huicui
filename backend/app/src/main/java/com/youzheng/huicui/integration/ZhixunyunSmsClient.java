package com.youzheng.huicui.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 智讯云（028lk/凌凯系）短信客户端。文档 智讯云 V2.5.1，REST/JSON、明文鉴权（SecretName+SecretKey）。
 *
 * 分流（BR-M9 用户决策）：即时类（验证码/缴费链接）走普通短信 Send；营销/通知类走视频短信 videosms/Send。
 *   普通短信 POST {smsBaseUrl}/Sms/Api/Send      —— 可即时；Content 或 TemplateId+TemplateVars；SignName=【签名】。
 *   视频短信 POST {videoBaseUrl}/videosms/api/Send —— Timing 必填且须 now+>10min；TheSameVar{Phone,Params[]}。
 * 响应统一 {code(0成功),msg,data}；code!=0 抛 BIZ_SMS_FAILED（带网关 msg/code）。
 * 未配置（enabled=false 或 SecretName/SecretKey 空）→ isEnabled()=false，调用方走占位、不触网关。
 */
@Component
public class ZhixunyunSmsClient {

    private static final Logger log = LoggerFactory.getLogger(ZhixunyunSmsClient.class);

    private final boolean enabled;
    private final String secretName;
    private final String secretKey;
    private final String smsBaseUrl;
    private final String videoBaseUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ZhixunyunSmsClient(
            @Value("${huicui.sms.enabled:false}") boolean enabled,
            @Value("${huicui.sms.secret-name:}") String secretName,
            @Value("${huicui.sms.secret-key:}") String secretKey,
            @Value("${huicui.sms.sms-base-url:}") String smsBaseUrl,
            @Value("${huicui.sms.video-base-url:http://api.028lk.com}") String videoBaseUrl) {
        this.enabled = enabled;
        this.secretName = secretName;
        this.secretKey = secretKey;
        this.smsBaseUrl = trimSlash(smsBaseUrl);
        this.videoBaseUrl = trimSlash(videoBaseUrl);
    }

    /** 已启用且凭据非空才真触网关；普通短信另需配 sms-base-url。 */
    public boolean isEnabled() {
        return enabled && !secretName.isBlank() && !secretKey.isBlank();
    }

    /**
     * 普通短信（即时）。templateId 非空→用模板 + vars；否则用 content 完整内容。
     * signName 传【签名】或留空（自动用账号默认绑定签名）。返回批次 id。
     */
    public String sendSms(String mobileCsv, String content, String templateId, List<String> vars, String signName) {
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
        ObjectNode b = SmsBodyBuilder.video(secretName, secretKey, templateId, timing, phoneCsv, params);
        JsonNode data = post(videoBaseUrl + "/videosms/api/Send", b);
        return data == null ? null : data.asText(null);
    }

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
            log.error("短信网关请求失败 {}: {}", url, e.toString());
            throw new ApiException(BizError.BIZ_SMS_FAILED, "短信网关不可达");
        }
        if (res.statusCode() / 100 != 2) {
            throw new ApiException(BizError.BIZ_SMS_FAILED, "短信网关 HTTP " + res.statusCode());
        }
        JsonNode root;
        try {
            root = json.readTree(res.body());
        } catch (Exception e) {
            throw new ApiException(BizError.BIZ_SMS_FAILED, "短信网关响应解析失败");
        }
        if (root.path("code").asInt(-1) != 0) {
            throw new ApiException(BizError.BIZ_SMS_FAILED,
                    "短信发送失败: " + root.path("msg").asText("未知错误") + "（code=" + root.path("code").asText("?") + "）");
        }
        return root.path("data");
    }

    private static String trimSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
