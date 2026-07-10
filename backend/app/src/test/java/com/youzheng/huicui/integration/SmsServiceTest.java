package com.youzheng.huicui.integration;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SmsService 的流水落库语义。重点回归两条：
 *   1) 验证码短信必须落 sms_record（org_id=NULL, template=VERIFY_CODE）—— 否则短信轰炸不可见（V927）。
 *   2) dry-run 走完整条链路但 template 带 DRY_RUN: 前缀，不污染计费口径。
 */
class SmsServiceTest {

    private JdbcTemplate jdbc;
    private ZhixunyunSmsClient client;
    private final List<Object[]> inserted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        client = mock(ZhixunyunSmsClient.class);
        inserted.clear();
        // update(String sql, Object... args) 是 varargs：用 any(Object[].class) 匹配整个可变参数组。
        // getArguments() 返回展开后的 [sql, p1..p6]。
        doAnswer(inv -> {
            Object[] args = inv.getArguments();
            inserted.add(Arrays.copyOfRange(args, 1, args.length));
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));
    }

    private SmsService service() {
        return new SmsService(jdbc, client, "【有证慧催】", "https://h5.example.com", "", "");
    }

    /** inserted 行的列序：org_id, case_id, project_id, template, status, failure_reason */
    private Object[] onlyRow() {
        assertEquals(1, inserted.size(), "应恰好落一条流水，实际 " + inserted.size());
        return inserted.get(0);
    }

    @Test
    void 验证码成功_落流水_orgId为NULL且template为VERIFY_CODE() {
        when(client.isDryRun()).thenReturn(false);
        String code = service().sendVerificationCode("13800001234");

        assertEquals(6, code.length());
        Object[] row = onlyRow();
        assertNull(row[0], "验证码短信 org_id 必须为 NULL（登录前无 org），才只对平台可见");
        assertNull(row[1]);
        assertNull(row[2]);
        assertEquals("VERIFY_CODE", row[3]);
        assertEquals("SENT", row[4]);
        assertNull(row[5]);
    }

    @Test
    void 验证码网关失败_落FAILED并继续抛出() {
        when(client.isDryRun()).thenReturn(false);
        when(client.sendSms(anyString(), anyString(), any(), any(), anyString()))
                .thenThrow(new ApiException(BizError.BIZ_SMS_FAILED, "短信发送失败，请稍后重试"));

        ApiException e = assertThrows(ApiException.class, () -> service().sendVerificationCode("13800001234"));
        assertEquals("短信发送失败，请稍后重试", e.getMessage());

        Object[] row = onlyRow();
        assertNull(row[0]);
        assertEquals("VERIFY_CODE", row[3]);
        assertEquals("FAILED", row[4]);
        assertEquals("短信发送失败，请稍后重试", row[5], "failure_reason 也只存对外文案，不落上游原文");
    }

    @Test
    void dryRun_验证码流水带DRY_RUN前缀() {
        when(client.isDryRun()).thenReturn(true);
        service().sendVerificationCode("13800001234");
        assertEquals("DRY_RUN:VERIFY_CODE", onlyRow()[3]);
    }

    @Test
    void 缴费链接_案件无主号_落FAILED且不触网关() {
        when(client.isDryRun()).thenReturn(false);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());   // contact 表无主号

        service().sendPayLinkSms(7L, 3L, 5L, "tok-1");

        Object[] row = onlyRow();
        assertEquals(3L, row[0]);
        assertEquals(7L, row[1]);
        assertEquals(5L, row[2]);
        assertEquals("缴费链接", row[3]);
        assertEquals("FAILED", row[4]);
        assertEquals("案件无主号", row[5]);
    }

    @Test
    void 缴费链接_网关抛错_落FAILED但不向上抛_bestEffort() {
        when(client.isDryRun()).thenReturn(false);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("13800001234"));
        when(client.sendSms(anyString(), anyString(), any(), any(), anyString()))
                .thenThrow(new ApiException(BizError.BIZ_SMS_FAILED, "短信发送失败，请稍后重试"));

        service().sendPayLinkSms(7L, 3L, 5L, "tok-1");   // 不抛：best-effort，不回滚缴费链接

        Object[] row = onlyRow();
        assertEquals("缴费链接", row[3]);
        assertEquals("FAILED", row[4]);
    }

    @Test
    void 缴费链接_成功_落SENT且链接由publicBaseUrl拼出() {
        when(client.isDryRun()).thenReturn(false);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("13800001234"));

        service().sendPayLinkSms(7L, 3L, 5L, "tok-1");

        Object[] row = onlyRow();
        assertEquals("缴费链接", row[3]);
        assertEquals("SENT", row[4]);
        org.mockito.Mockito.verify(client).sendSms(
                eq("13800001234"),
                org.mockito.ArgumentMatchers.contains("https://h5.example.com/pay/tok-1"),
                any(), any(), eq("【有证慧催】"));
    }

    @Test
    void dryRun_缴费链接流水带前缀() {
        when(client.isDryRun()).thenReturn(true);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("13800001234"));

        service().sendPayLinkSms(7L, 3L, 5L, "tok-1");
        assertTrue(((String) onlyRow()[3]).startsWith("DRY_RUN:"));
    }
}
