package com.youzheng.huicui.app.data.net

import com.youzheng.huicui.app.api.models.LoginByPassword
import com.youzheng.huicui.app.api.models.LoginBySms
import com.youzheng.huicui.app.api.models.LoginResult
import com.youzheng.huicui.app.api.models.SelectAccountRequest
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

@Serializable
data class SmsCodeRequest(val phone: String)

/**
 * /auth/sms-code 的 200 与 429 两种响应体的并集：
 *   200 → {"sent":true,"ttlSeconds":300}
 *   429 → {"sent":false,"message":"…","retryAfterSeconds":42}
 */
@Serializable
data class SmsCodeResult(
    val sent: Boolean? = null,
    val ttlSeconds: Int? = null,
    val retryAfterSeconds: Int? = null,
    val message: String? = null,
)

/**
 * 三个「生成器表达不了」的鉴权端点，手写 Retrofit 接口。其余端点一律用生成的 `api-client`。
 *
 * 1) POST /auth/login —— 请求体是 `oneOf(LoginByPassword, LoginBySms)` + discriminator `mode`。
 *    openapi-generator 7.23.0 把它生成为一个 **零子类的 sealed class `LoginRequest`**，
 *    且把两个分支的字段全部提成 non-null abstract val（`LoginByPassword` 根本没有 phone/code），
 *    编译得过但永远无法实例化。而两个分支类 `LoginByPassword`/`LoginBySms` 自身是干净可用的
 *    @Serializable data class，`mode` 就是普通字段 → 直接用两个方法打同一个 path，类型安全且零自写 DTO。
 *
 * 2) POST /auth/sms-code —— 契约 200 无 content，生成物是 `Response<Unit>`，
 *    拿不到 `ttlSeconds`，429 的 `retryAfterSeconds` 也无处落。倒计时必须要这两个值。
 *
 * 3) POST /auth/select-account —— 契约声明返回 `LoginTokenResult{token}`，
 *    而后端 `AuthController.selectAccount` 实际返回完整 `LoginResult`（含 `mustChangePassword`）。
 *    多账号用户的首登改密标志只在这里出现一次，故按后端实际形状收。
 *    【契约缺口·已记录】应在下一次契约小版本里把 select-account 的 200 改为 LoginResult（纯加字段，非破坏性）。
 *    在此之前，多账号 + 首登改密的用户仍会被后端 JwtAuthFilter 用 403 MUST_CHANGE_PASSWORD 兜住，不会绕过。
 */
interface AuthEdgeApi {

    @POST("auth/login")
    suspend fun loginByPassword(@Body body: LoginByPassword): Response<LoginResult>

    @POST("auth/login")
    suspend fun loginBySms(@Body body: LoginBySms): Response<LoginResult>

    @POST("auth/sms-code")
    suspend fun requestSmsCode(@Body body: SmsCodeRequest): Response<SmsCodeResult>

    @POST("auth/select-account")
    suspend fun selectAccount(@Body body: SelectAccountRequest): Response<LoginResult>
}
