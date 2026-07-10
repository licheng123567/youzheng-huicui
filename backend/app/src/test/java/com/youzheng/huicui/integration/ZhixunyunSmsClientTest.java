package com.youzheng.huicui.integration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智讯云短信请求体组装 + isEnabled 门控 离线单测（无网络/凭据）。
 * 断言 {@link SmsBodyBuilder}（生产 sendSms/sendVideoSms 共用同一组装）字段结构与文档一致。
 */
class ZhixunyunSmsClientTest {

    @Test
    void plainSms_content_hasPlaintextAuthAndSign() {
        ObjectNode b = SmsBodyBuilder.sms("API", "000000", "13000000000",
                "您的验证码是541254，5分钟内有效。", null, null, "【有证慧催】");
        assertEquals("API", b.get("SecretName").asText());
        assertEquals("000000", b.get("SecretKey").asText());
        assertTrue(b.get("TimeStamp").isNull());                 // 明文鉴权：TimeStamp 不填
        assertEquals("13000000000", b.get("Mobile").asText());
        assertEquals("您的验证码是541254，5分钟内有效。", b.get("Content").asText());
        assertEquals("【有证慧催】", b.get("SignName").asText());
        assertFalse(b.has("TemplateId"));                        // 明文方式不带模板
    }

    @Test
    void plainSms_template_hasTemplateIdAndVars() {
        ObjectNode b = SmsBodyBuilder.sms("API", "000000", "13000000000",
                null, "1001", List.of("541254", "5"), "");
        assertEquals("1001", b.get("TemplateId").asText());
        assertEquals("", b.get("Content").asText());
        assertEquals(2, b.get("TemplateVars").size());
        assertEquals("541254", b.get("TemplateVars").get(0).asText());
        assertEquals("5", b.get("TemplateVars").get(1).asText());
        assertFalse(b.has("SignName"));                          // 签名留空→账号默认绑定签名
    }

    @Test
    void videoSms_hasTheSameVarAndTiming() {
        ObjectNode b = SmsBodyBuilder.video("API", "000000", "1531545821621915648",
                "20260707120000", "13000000000,13000000001", List.of("张三", "100"));
        assertEquals("1531545821621915648", b.get("TemplateId").asText());
        assertEquals("20260707120000", b.get("Timing").asText());  // yyyyMMddHHmmss
        ObjectNode same = (ObjectNode) b.get("TheSameVar");
        assertEquals("13000000000,13000000001", same.get("Phone").asText());
        assertEquals(2, same.get("Params").size());
        assertEquals("张三", same.get("Params").get(0).asText());
    }

    @Test
    void isEnabled_falseWhenUnconfigured() {
        // 构造器第二参为 dry-run（不影响 isEnabled，只影响是否真触网关）
        assertFalse(new ZhixunyunSmsClient(true, false, "", "", "http://b", "http://v").isEnabled()); // 空凭据
        assertFalse(new ZhixunyunSmsClient(false, false, "k", "s", "http://b", "http://v").isEnabled()); // 未启用
        assertTrue(new ZhixunyunSmsClient(true, false, "k", "s", "http://b", "http://v").isEnabled());
    }
}
