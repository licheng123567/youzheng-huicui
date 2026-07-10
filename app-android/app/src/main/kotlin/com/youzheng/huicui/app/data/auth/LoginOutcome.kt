package com.youzheng.huicui.app.data.auth

/**
 * 登录三态（BR-M1-11 / B-04方案A），外加失败态。纯数据，无 Android/网络依赖 → 可纯 JVM 单测。
 *
 *   token 非空                 → Authenticated，直接进主界面
 *   token 非空 + mustChange    → MustChangePassword，强制改密，不可跳过
 *   loginTicket + accounts>1   → ChoiceRequired，选账号后调 /auth/select-account 换 token
 */
sealed interface LoginOutcome {
    data class Authenticated(val token: String) : LoginOutcome
    data class MustChangePassword(val token: String) : LoginOutcome
    data class ChoiceRequired(val loginTicket: String, val accounts: List<AccountChoice>) : LoginOutcome
    data class Failed(
        val kind: FailureKind,
        val message: String,
        val retryAfterSeconds: Int? = null,
    ) : LoginOutcome
}

data class AccountChoice(
    val accountId: String,
    val name: String,
    val orgName: String,
    val role: String,
)

enum class FailureKind {
    /** 401：用户名/口令错，或验证码错误已过期 */
    BAD_CREDENTIALS,

    /** 403：账号已停用 */
    FORBIDDEN,

    /** 422：入参不合法 */
    VALIDATION,

    /** 429：同手机号 60s 内只发一次 */
    RATE_LIMITED,

    /** 502：短信通道未启用/网关不可达（BIZ_SMS_FAILED）。非 dev 环境不存在固定验证码 */
    SMS_UNAVAILABLE,

    /** 5xx */
    SERVER,

    /** 连不上后端（DNS/超时/明文被拦） */
    NETWORK,

    /** 2xx 但响应体既无 token 也无 loginTicket —— 后端与契约不一致 */
    MALFORMED,
}

/** 短信验证码下发结果，与登录分开：它自身就有 429 / 502 两个必须区分的分支。 */
sealed interface SmsCodeOutcome {
    data class Sent(val ttlSeconds: Int?) : SmsCodeOutcome
    data class Failed(
        val kind: FailureKind,
        val message: String,
        val retryAfterSeconds: Int? = null,
    ) : SmsCodeOutcome
}
