package com.youzheng.huicui.integration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 易保全签名（§4.1）与保全 hash（§5）算法单测——用文档自带示例断言，无需网络/凭据。
 */
class EbaoquanClientTest {

    /** 文档 §4.1 举例：appKey + param1/2/3 + secret=d5207ae9…af0 → sign=2523044EB55944A10324AAAA3DCCEB75。 */
    @Test
    void sign_matchesDocExample() {
        EbaoquanClient c = new EbaoquanClient(true, "https://bs.sandbox.ebaoquan.org",
                "a7ce728fbec40519", "d5207ae9f7bee0692a1e4014f90e1af0");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("appKey", "a7ce728fbec40519");
        params.put("param2", "paramValue2");   // 乱序传入，验证内部按 ASCII 排序
        params.put("param1", "paramValue1");
        params.put("param3", "paramValue3");
        assertEquals("2523044EB55944A10324AAAA3DCCEB75", c.sign(params));
    }

    /** 文档 §5 举例："1234567890" 的 SHA-512 hex。 */
    @Test
    void sha512Hex_matchesDocExample() {
        String expected = "12b03226a6d8be9c6e8cd5e55dc6c7920caaa39df14aab92d5e3ea9340d1c8a4"
                + "d3d0b8e4314f1f6ef131ba4bf1ceb9186ab87c801af0d5c95b1befb8cedae2b9";
        String actual = EbaoquanClient.sha512Hex("1234567890".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, actual);
    }

    /** 未配置凭据（空 appKey/secret）→ isEnabled()=false，调用方走占位。 */
    @Test
    void isEnabled_falseWhenUnconfigured() {
        assertFalse(new EbaoquanClient(true, "u", "", "").isEnabled());
        assertFalse(new EbaoquanClient(false, "u", "k", "s").isEnabled());
        assertTrue(new EbaoquanClient(true, "u", "k", "s").isEnabled());
    }
}
