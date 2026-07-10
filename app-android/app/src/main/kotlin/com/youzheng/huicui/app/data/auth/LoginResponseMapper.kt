package com.youzheng.huicui.app.data.auth

import com.youzheng.huicui.app.api.models.LoginResult
import com.youzheng.huicui.app.data.net.SmsCodeResult
import com.youzheng.huicui.app.data.net.parseApiError
import com.youzheng.huicui.app.data.net.parseJsonOrNull

/**
 * HTTP 结果 → 登录三态。**纯函数**：不碰 Retrofit/Android，可离线单测。
 *
 * 判定顺序有意如此：
 *   mustChangePassword 优先于 token —— 首登改密不可跳过（B-04方案A），
 *   即便后端同时给了可用 token（它给了，但 JwtAuthFilter 会把除 GET /me、POST /me/password
 *   之外的一切都挡成 403 MUST_CHANGE_PASSWORD）。
 */
object LoginResponseMapper {

    /** 后端拒绝时的兜底文案：绝不回显上游/网关原文。 */
    private const val FALLBACK_SERVER = "服务暂不可用，请稍后重试"

    fun map(httpCode: Int, body: LoginResult?, errorBody: String? = null): LoginOutcome {
        if (httpCode in 200..299) {
            if (body == null) return LoginOutcome.Failed(FailureKind.MALFORMED, "登录响应为空")

            val token = body.token
            if (token != null && body.mustChangePassword == true) {
                return LoginOutcome.MustChangePassword(token)
            }
            if (token != null) return LoginOutcome.Authenticated(token)

            val ticket = body.loginTicket
            val accounts = body.accounts.orEmpty()
            if (ticket != null && accounts.isNotEmpty()) {
                return LoginOutcome.ChoiceRequired(
                    loginTicket = ticket,
                    accounts = accounts.map {
                        AccountChoice(
                            accountId = it.accountId.orEmpty(),
                            name = it.name.orEmpty(),
                            orgName = it.orgName.orEmpty(),
                            role = it.role?.value ?: "",
                        )
                    },
                )
            }
            return LoginOutcome.Failed(FailureKind.MALFORMED, "登录响应既无 token 也无 loginTicket")
        }

        val msg = parseApiError(errorBody)?.message
        return when (httpCode) {
            401 -> LoginOutcome.Failed(FailureKind.BAD_CREDENTIALS, msg ?: "用户名或口令错误")
            403 -> LoginOutcome.Failed(FailureKind.FORBIDDEN, msg ?: "账号已停用")
            422 -> LoginOutcome.Failed(FailureKind.VALIDATION, msg ?: "请求参数不合法")
            429 -> LoginOutcome.Failed(FailureKind.RATE_LIMITED, msg ?: "请求过于频繁，请稍后再试")
            502 -> LoginOutcome.Failed(FailureKind.SMS_UNAVAILABLE, msg ?: "短信通道不可用")
            else -> LoginOutcome.Failed(FailureKind.SERVER, msg ?: FALLBACK_SERVER)
        }
    }

    /**
     * /auth/sms-code。429 与 502 是两个必须让用户看懂的不同世界：
     *   429 = 你太快了，等 retryAfterSeconds 秒；502 = 通道压根没开，等多久都没用。
     */
    fun mapSmsCode(httpCode: Int, body: SmsCodeResult?, errorBody: String? = null): SmsCodeOutcome {
        if (httpCode in 200..299) {
            return SmsCodeOutcome.Sent(body?.ttlSeconds)
        }
        // 429 走的是非信封形状（{sent,message,retryAfterSeconds}），按它自己的形状再解一次
        val err = parseApiError(errorBody)
        val retryAfter = parseJsonOrNull<SmsCodeResult>(errorBody)?.retryAfterSeconds

        return when (httpCode) {
            429 -> SmsCodeOutcome.Failed(
                FailureKind.RATE_LIMITED,
                err?.message ?: "请求过于频繁，请稍后再试",
                retryAfter,
            )
            502 -> SmsCodeOutcome.Failed(
                FailureKind.SMS_UNAVAILABLE,
                err?.message ?: "短信通道未启用，无法下发验证码",
            )
            422 -> SmsCodeOutcome.Failed(FailureKind.VALIDATION, err?.message ?: "手机号不合法")
            else -> SmsCodeOutcome.Failed(FailureKind.SERVER, err?.message ?: FALLBACK_SERVER)
        }
    }
}
