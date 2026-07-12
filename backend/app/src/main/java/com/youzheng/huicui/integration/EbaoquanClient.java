package com.youzheng.huicui.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 易保全（ebaoquan）证据保全 API 客户端（哈希保全型态）。文档 v2.0.5。
 *
 * 签名：sign = MD5(参数按参数名 ASCII 升序拼成 k=v&k2=v2… + appKeySecret).toUpperCase()。
 *   排序集合排除 sign 与文件参数；不 UrlEncode（拼接用原值）；区分大小写；只含实际发送参数。
 * 传输：POST application/x-www-form-urlencoded（body 中各值 UrlEncode，含中文 name/description）。
 * 响应：{success, code, message, data}，code=0 成功；否则抛 BIZ_EVIDENCE_FAILED（带易保全 message/code）。
 *
 * 未配置（enabled=false 或 appKey/secret 空）→ isEnabled()=false，调用方退回占位、不触达本客户端。
 */
@Component
public class EbaoquanClient {

    private static final Logger log = LoggerFactory.getLogger(EbaoquanClient.class);

    // v1.23.0：配置改由 IntegrationConfigService 在**调用时**解析（DB 后台配置 → yml 环境变量 → 默认）。
    // 此前是构造器 @Value 注入的 final 字段——那样后台改了 key 必须重启才生效，而「改完立刻生效」正是本功能的验收点。
    private final IntegrationConfigService cfg;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public EbaoquanClient(IntegrationConfigService cfg) {
        this.cfg = cfg;
    }

    /** 测试用：直接喂一组固定配置，不必起 Spring/DB（签名算法等纯逻辑用例靠它）。 */
    static EbaoquanClient forTest(boolean enabled, String baseUrl, String appKey, String appKeySecret) {
        return new EbaoquanClient(IntegrationConfigService.fixed(
                IntegrationConfigService.EBAOQUAN, enabled,
                java.util.Map.of("baseUrl", baseUrl),
                java.util.Map.of("appKey", appKey, "appKeySecret", appKeySecret)));
    }

    private String baseUrl() {
        String u = cfg.setting(IntegrationConfigService.EBAOQUAN, "baseUrl");
        if (u == null || u.isBlank()) u = "https://bs.sandbox.ebaoquan.org";
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private String appKey() {
        String v = cfg.secret(IntegrationConfigService.EBAOQUAN, "appKey");
        return v == null ? "" : v;
    }

    private String appKeySecret() {
        String v = cfg.secret(IntegrationConfigService.EBAOQUAN, "appKeySecret");
        return v == null ? "" : v;
    }

    /** 已配置且启用（appKey/secret 非空）才真触达易保全；否则调用方走占位。 */
    public boolean isEnabled() {
        return cfg.enabled(IntegrationConfigService.EBAOQUAN)
                && !appKey().isBlank() && !appKeySecret().isBlank();
    }

    // ── 3.1 证据 HASH 保全 → 平台证据 id ──
    public long createEvidenceHash(String fileHash, String name, String description, int type) {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("fileHash", fileHash);
        p.put("name", clip(name, 50));
        if (description != null && !description.isBlank()) p.put("description", clip(description, 50));
        p.put("type", String.valueOf(type));
        JsonNode data = post("/api/createEvidenceHash", p);
        return data.path("evidenceId").asLong();
    }

    // ── 3.3 证据详情（保全成功后 preservationId 非空） ──
    public JsonNode queryEvidenceDetail(long evidenceId) {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("evidenceId", String.valueOf(evidenceId));
        return post("/api/queryEvidenceDetail", p);
    }

    // ── 3.8 查询保全信息（链上交易 hash / 法院证据 id；成功 10min 后） ──
    public JsonNode preservationInfo(long preservationId) {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("preservationId", String.valueOf(preservationId));
        return post("/api/preservationInfo", p);
    }

    // ── 3.7 下载备案证书（多个 preservationId 逗号分隔）→ zip 字节 ──
    public byte[] downPreservationCert(String preservationIdsCsv) {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("preservationIds", preservationIdsCsv);
        JsonNode data = post("/api/downPreservationCert", p);
        String b64 = data.isTextual() ? data.asText() : data.path("data").asText("");
        if (b64 == null || b64.isBlank()) {
            throw new ApiException(BizError.BIZ_EVIDENCE_FAILED, "易保全: 备案证书为空（可能尚未就绪）");
        }
        return Base64.getDecoder().decode(b64.trim());
    }

    // ── 签名（可单测；不含 sign/文件参数） ──
    public String sign(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append(appKeySecret());
        return md5UpperHex(sb.toString());
    }

    /** SHA-512 hex（保全 hash 算法：文件二进制 → SHA-512 → 16 进制小写）。 */
    public static String sha512Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new ApiException(BizError.BIZ_EVIDENCE_FAILED, "SHA-512 计算失败");
        }
    }

    // ── 内部：签名 + form-urlencoded POST + 统一响应解包 ──
    private JsonNode post(String path, TreeMap<String, String> params) {
        params.put("appKey", appKey());                 // appKey 参与签名
        String signValue = sign(params);
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        body.append("&sign=").append(enc(signValue));
        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("易保全请求失败 {}: {}", path, e.toString());
            throw new ApiException(BizError.BIZ_EVIDENCE_FAILED, "易保全服务不可达");
        }
        if (res.statusCode() / 100 != 2) {
            throw new ApiException(BizError.BIZ_EVIDENCE_FAILED, "易保全 HTTP " + res.statusCode());
        }
        JsonNode root;
        try {
            root = json.readTree(res.body());
        } catch (Exception e) {
            throw new ApiException(BizError.BIZ_EVIDENCE_FAILED, "易保全响应解析失败");
        }
        if (!root.path("success").asBoolean(false) || root.path("code").asInt(-1) != 0) {
            throw new ApiException(BizError.BIZ_EVIDENCE_FAILED,
                    "易保全: " + root.path("message").asText("未知错误") + "（code=" + root.path("code").asText("?") + "）");
        }
        return root.path("data");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String md5UpperHex(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().withUpperCase().formatHex(md.digest(in.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ApiException(BizError.BIZ_EVIDENCE_FAILED, "MD5 计算失败");
        }
    }
}
