package com.youzheng.huicui.app.data.auth

import com.youzheng.huicui.app.api.models.LoginResult
import com.youzheng.huicui.app.api.models.LoginResultAccountsInner
import com.youzheng.huicui.app.api.models.RoleTemplateEnum
import com.youzheng.huicui.app.data.net.SmsCodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录三态 + 失败分支。纯 JVM，不需要模拟器 —— 这是 M-A1 唯一能在 CI 里被机器证明的部分，
 * 「真机走通三种登录」仍必须人工验收。
 */
class LoginResponseMapperTest {

    // ── 三态 ────────────────────────────────────────────────────────────────

    @Test
    fun `单账号 token 直接进主界面`() {
        val r = LoginResponseMapper.map(200, LoginResult(token = "jwt-abc"))
        assertEquals(LoginOutcome.Authenticated("jwt-abc"), r)
    }

    @Test
    fun `多账号返回 loginTicket + accounts 需选账号`() {
        val body = LoginResult(
            loginTicket = "tkt-1",
            accounts = listOf(
                LoginResultAccountsInner(accountId = "7", orgName = "金信催收", role = RoleTemplateEnum.CO, name = "李催"),
                LoginResultAccountsInner(accountId = "9", orgName = "阳光物业", role = RoleTemplateEnum.PC, name = "李协"),
            ),
        )
        val r = LoginResponseMapper.map(200, body)
        assertTrue(r is LoginOutcome.ChoiceRequired)
        r as LoginOutcome.ChoiceRequired
        assertEquals("tkt-1", r.loginTicket)
        assertEquals(2, r.accounts.size)
        assertEquals(AccountChoice("7", "李催", "金信催收", "CO"), r.accounts[0])
        assertEquals("PC", r.accounts[1].role)
    }

    @Test
    fun `mustChangePassword 优先于 token —— 首登改密不可跳过`() {
        val r = LoginResponseMapper.map(200, LoginResult(token = "jwt-abc", mustChangePassword = true))
        assertEquals(LoginOutcome.MustChangePassword("jwt-abc"), r)
    }

    @Test
    fun `mustChangePassword=false 视同普通登录`() {
        val r = LoginResponseMapper.map(200, LoginResult(token = "jwt-abc", mustChangePassword = false))
        assertEquals(LoginOutcome.Authenticated("jwt-abc"), r)
    }

    // ── 失败分支 ────────────────────────────────────────────────────────────

    @Test
    fun `401 口令错`() {
        val r = LoginResponseMapper.map(401, null, """{"code":"AUTH_401","message":"用户名或口令错误"}""")
        assertEquals(LoginOutcome.Failed(FailureKind.BAD_CREDENTIALS, "用户名或口令错误"), r)
    }

    @Test
    fun `403 账号停用`() {
        val r = LoginResponseMapper.map(403, null, """{"code":"PERM_403","message":"账号已停用"}""")
        assertEquals(FailureKind.FORBIDDEN, (r as LoginOutcome.Failed).kind)
    }

    @Test
    fun `502 短信通道未启用`() {
        val r = LoginResponseMapper.map(502, null, """{"code":"BIZ_SMS_FAILED","message":"短信通道未启用，无法下发验证码"}""")
        assertEquals(FailureKind.SMS_UNAVAILABLE, (r as LoginOutcome.Failed).kind)
    }

    @Test
    fun `2xx 但既无 token 也无 loginTicket 判为契约不一致`() {
        val r = LoginResponseMapper.map(200, LoginResult())
        assertEquals(FailureKind.MALFORMED, (r as LoginOutcome.Failed).kind)
    }

    @Test
    fun `多账号但 accounts 为空 也判 MALFORMED`() {
        val r = LoginResponseMapper.map(200, LoginResult(loginTicket = "tkt", accounts = emptyList()))
        assertEquals(FailureKind.MALFORMED, (r as LoginOutcome.Failed).kind)
    }

    @Test
    fun `错误体不是 JSON 时用兜底文案 不回显原始报文`() {
        val raw = "<html>502 Bad Gateway from upstream nginx</html>"
        val r = LoginResponseMapper.map(500, null, raw) as LoginOutcome.Failed
        assertEquals(FailureKind.SERVER, r.kind)
        assertEquals("服务暂不可用，请稍后重试", r.message)
        assertTrue("兜底文案不得包含上游原文", !r.message.contains("nginx"))
    }

    // ── /auth/sms-code ──────────────────────────────────────────────────────

    @Test
    fun `sms-code 200 携带 ttlSeconds`() {
        val r = LoginResponseMapper.mapSmsCode(200, SmsCodeResult(sent = true, ttlSeconds = 300))
        assertEquals(SmsCodeOutcome.Sent(300), r)
    }

    @Test
    fun `sms-code 429 取出 retryAfterSeconds —— 非信封形状也要解得出`() {
        val body = """{"sent":false,"message":"请求过于频繁，请稍后再试","retryAfterSeconds":42}"""
        val r = LoginResponseMapper.mapSmsCode(429, null, body) as SmsCodeOutcome.Failed
        assertEquals(FailureKind.RATE_LIMITED, r.kind)
        assertEquals(42, r.retryAfterSeconds)
        assertEquals("请求过于频繁，请稍后再试", r.message)
    }

    @Test
    fun `sms-code 502 与 429 必须是两个不同的世界`() {
        val r = LoginResponseMapper.mapSmsCode(
            502, null, """{"code":"BIZ_SMS_FAILED","message":"短信通道未启用，无法下发验证码"}""",
        ) as SmsCodeOutcome.Failed
        assertEquals(FailureKind.SMS_UNAVAILABLE, r.kind)
        assertEquals(null, r.retryAfterSeconds)   // 等多久都没用，不给倒计时
    }

    @Test
    fun `sms-code 500 用兜底文案`() {
        val r = LoginResponseMapper.mapSmsCode(500, null, null) as SmsCodeOutcome.Failed
        assertEquals(FailureKind.SERVER, r.kind)
    }
}
