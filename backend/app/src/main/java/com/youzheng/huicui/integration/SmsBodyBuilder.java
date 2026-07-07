package com.youzheng.huicui.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 智讯云短信请求体组装（纯函数，与 {@link ZhixunyunSmsClient} 发送共用；抽出便于离线单测断言字段结构）。
 * 明文鉴权：body 含 SecretName/SecretKey，TimeStamp 不填（置 null）。
 */
final class SmsBodyBuilder {
    private SmsBodyBuilder() {}

    private static final ObjectMapper M = new ObjectMapper();

    /** 普通短信 /Sms/Api/Send。templateId 非空→模板+vars；否则 content 完整内容。 */
    static ObjectNode sms(String secretName, String secretKey, String mobileCsv,
                          String content, String templateId, List<String> vars, String signName) {
        ObjectNode b = M.createObjectNode();
        b.put("SecretName", secretName);
        b.put("SecretKey", secretKey);
        b.putNull("TimeStamp");
        b.put("Mobile", mobileCsv);
        if (templateId != null && !templateId.isBlank()) {
            b.put("Content", "");
            b.put("TemplateId", templateId);
            ArrayNode arr = b.putArray("TemplateVars");
            if (vars != null) vars.forEach(arr::add);
        } else {
            b.put("Content", content == null ? "" : content);
        }
        if (signName != null && !signName.isBlank()) b.put("SignName", signName);
        return b;
    }

    /** 视频短信 /videosms/api/Send，TheSameVar{Phone,Params[]}。 */
    static ObjectNode video(String secretName, String secretKey, String templateId,
                            String timing, String phoneCsv, List<String> params) {
        ObjectNode b = M.createObjectNode();
        b.put("SecretName", secretName);
        b.put("SecretKey", secretKey);
        b.put("TemplateId", templateId);
        b.put("Timing", timing);
        b.put("CustomId", "");
        ObjectNode same = b.putObject("TheSameVar");
        same.put("Phone", phoneCsv);
        ArrayNode arr = same.putArray("Params");
        if (params != null) params.forEach(arr::add);
        return b;
    }
}
