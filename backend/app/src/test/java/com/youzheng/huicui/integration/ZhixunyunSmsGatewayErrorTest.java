package com.youzheng.huicui.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关错误处理 + 泄露回归。用 JDK 自带 com.sun.net.httpserver 打桩，零新增依赖
 * （客户端本就用 java.net.http，不必引 MockWebServer）。
 *
 * 核心断言：POST /auth/sms-code 是 security:[] 的公开端点，
 * 所以任何上游文案（网关 msg / code / HTTP 状态 / 原始报文）都不得出现在抛给客户端的 message 里。
 */
class ZhixunyunSmsGatewayErrorTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    /** 起一个桩服务，把 /Sms/Api/Send 固定回成给定状态码与报文。 */
    private ZhixunyunSmsClient clientReturning(int status, String body) throws IOException {
        return clientReturning(status, body, false);
    }

    private ZhixunyunSmsClient clientReturning(int status, String body, boolean dryRun) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/Sms/Api/Send", (HttpExchange ex) -> {
            hits.incrementAndGet();
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return ZhixunyunSmsClient.forTest(true, dryRun, "API", "KEY", base, base);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private static void assertPublicSafe(ApiException e, String... mustNotContain) {
        assertEquals(BizError.BIZ_SMS_FAILED, e.error);
        assertEquals("短信发送失败，请稍后重试", e.getMessage());
        for (String s : mustNotContain) {
            assertFalse(e.getMessage().contains(s), "对外 message 泄露了上游内容: " + s);
        }
    }

    @Test
    void 网关500_不泄露上游报文() throws IOException {
        ZhixunyunSmsClient c = clientReturning(500, "upstream nginx crashed: pool exhausted");
        ApiException e = assertThrows(ApiException.class,
                () -> c.sendSms("13000000000", "hi", null, null, "【有证慧催】"));
        assertPublicSafe(e, "nginx", "500", "pool");
    }

    @Test
    void 网关code非0_不泄露msg与code() throws IOException {
        ZhixunyunSmsClient c = clientReturning(200,
                "{\"code\":1053,\"msg\":\"账户余额不足,请充值\",\"data\":null}");
        ApiException e = assertThrows(ApiException.class,
                () -> c.sendSms("13000000000", "hi", null, null, ""));
        assertPublicSafe(e, "余额", "1053", "充值");
    }

    @Test
    void 网关响应非JSON_不泄露原始报文() throws IOException {
        ZhixunyunSmsClient c = clientReturning(200, "<html><body>WAF blocked: rule 942100</body></html>");
        ApiException e = assertThrows(ApiException.class,
                () -> c.sendSms("13000000000", "hi", null, null, ""));
        assertPublicSafe(e, "WAF", "942100", "html");
    }

    @Test
    void 网关不可达_不泄露连接细节() throws IOException {
        // 起了再停，端口必然拒绝连接
        ZhixunyunSmsClient c = clientReturning(200, "{\"code\":0}");
        server.stop(0);
        server = null;
        ApiException e = assertThrows(ApiException.class,
                () -> c.sendSms("13000000000", "hi", null, null, ""));
        assertPublicSafe(e, "Connection", "refused", "127.0.0.1");
    }

    @Test
    void 成功_返回data作为批次id() throws IOException {
        ZhixunyunSmsClient c = clientReturning(200, "{\"code\":0,\"msg\":\"ok\",\"data\":\"BATCH-7\"}");
        assertEquals("BATCH-7", c.sendSms("13000000000", "hi", null, null, ""));
    }

    @Test
    void dryRun_不触网关_返回DRYRUN前缀() throws IOException {
        // 桩服务只要被碰到就回 500；dry-run 必须让它一次都碰不到
        ZhixunyunSmsClient c = clientReturning(500, "should never be called", true);
        hits.set(0);
        String id = c.sendSms("13000000000", "您的验证码是123456", null, null, "");
        assertNotNull(id);
        assertTrue(id.startsWith("DRYRUN-"), "dry-run 应返回 DRYRUN- 前缀的批次 id，实际: " + id);
        assertEquals(0, hits.get(), "dry-run 绝不能触网关");
    }

    @Test
    void dryRun_视频短信同样不触网关() {
        ZhixunyunSmsClient c = ZhixunyunSmsClient.forTest(true, true, "API", "KEY", "http://127.0.0.1:1", "http://127.0.0.1:1");
        String id = c.sendVideoSms("tpl-1", "20260707120000", "13000000000", List.of("张三"));
        assertTrue(id.startsWith("DRYRUN-"));
    }

    @Test
    void isEnabled真值表_dryRun不影响enabled() {
        assertTrue(ZhixunyunSmsClient.forTest(true, false, "A", "K", "u", "v").isEnabled());
        assertTrue(ZhixunyunSmsClient.forTest(true, true, "A", "K", "u", "v").isEnabled());
        assertFalse(ZhixunyunSmsClient.forTest(false, false, "A", "K", "u", "v").isEnabled());
        assertFalse(ZhixunyunSmsClient.forTest(true, false, "", "K", "u", "v").isEnabled());
        assertFalse(ZhixunyunSmsClient.forTest(true, false, "A", "", "u", "v").isEnabled());
        assertTrue(ZhixunyunSmsClient.forTest(true, true, "A", "K", "u", "v").isDryRun());
        assertFalse(ZhixunyunSmsClient.forTest(true, false, "A", "K", "u", "v").isDryRun());
    }

    @Test
    void 手机号脱敏() {
        assertEquals("138****1234", ZhixunyunSmsClient.maskPhone("13800001234"));
        assertEquals("138****1234,139****5678", ZhixunyunSmsClient.maskPhone("13800001234,13900005678"));
        assertEquals("123", ZhixunyunSmsClient.maskPhone("123"));   // 过短不脱敏（无信息量）
        assertEquals("", ZhixunyunSmsClient.maskPhone(null));
    }
}
